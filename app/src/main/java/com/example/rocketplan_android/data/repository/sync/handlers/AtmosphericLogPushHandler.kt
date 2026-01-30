package com.example.rocketplan_android.data.repository.sync.handlers

import android.net.Uri
import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.AtmosphericLogRequest
import com.example.rocketplan_android.data.model.FileToUpload
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import retrofit2.HttpException
import java.io.File

/**
 * Handles pushing atmospheric log upsert/delete operations to the server.
 */
class AtmosphericLogPushHandler(private val ctx: PushHandlerContext) {

    companion object {
        private const val TAG = "AtmosLogPushHandler"
    }

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getAtmosphericLogByUuid(operation.entityUuid)
            ?: ctx.localDataService.getAtmosphericLog(operation.entityId)
            ?: return OperationOutcome.DROP

        if (log.isDeleted) return OperationOutcome.DROP

        val project = ctx.localDataService.getProject(log.projectId)
        val projectServerId = project?.serverId
            ?: return OperationOutcome.SKIP

        // If there's a room, we need its server ID
        val roomServerId: Long? = if (log.roomId != null) {
            val room = ctx.localDataService.getRoom(log.roomId)
            room?.serverId ?: return OperationOutcome.SKIP
        } else {
            null
        }

        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: log.updatedAt.toApiTimestamp()

        val request = AtmosphericLogRequest(
            uuid = log.uuid,
            date = log.date.toApiTimestamp(),
            temperature = log.temperature,
            relativeHumidity = log.relativeHumidity,
            dewPoint = log.dewPoint,
            gpp = log.gpp,
            pressure = log.pressure,
            windSpeed = log.windSpeed,
            isExternal = log.isExternal,
            isInlet = log.isInlet,
            inletId = log.inletId,
            roomUuid = log.roomId?.let { ctx.localDataService.getRoom(it)?.uuid },
            projectUuid = project.uuid,
            idempotencyKey = log.uuid,
            updatedAt = lockUpdatedAt
        )

        val dto = if (log.serverId == null) {
            // CREATE - route to room or project
            if (roomServerId != null) {
                ctx.api.createRoomAtmosphericLog(roomServerId, request)
            } else {
                ctx.api.createProjectAtmosphericLog(projectServerId, request)
            }
        } else {
            // UPDATE
            ctx.api.updateAtmosphericLog(log.serverId, request)
        }

        val synced = log.copy(
            serverId = dto.id,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveAtmosphericLogs(listOf(synced))

        // Check if there's a pending photo upload for this log
        if (shouldTriggerPhotoUpload(synced)) {
            triggerPhotoUpload(synced)
        }

        return OperationOutcome.SUCCESS
    }

    /**
     * Check if this atmospheric log has a pending photo that needs to be uploaded.
     */
    private fun shouldTriggerPhotoUpload(log: OfflineAtmosphericLogEntity): Boolean {
        // Need serverId for entity reference
        if (log.serverId == null) return false

        // Check for pending photo
        val localPath = log.photoLocalPath
        if (localPath.isNullOrBlank()) return false

        // Check if upload is pending (not already started or completed)
        if (log.photoUploadStatus != "pending") return false

        // Verify file still exists
        val file = File(localPath)
        return file.exists()
    }

    /**
     * Create an assembly to upload the photo for this atmospheric log.
     */
    private suspend fun triggerPhotoUpload(log: OfflineAtmosphericLogEntity) {
        val repository = ctx.imageProcessorRepositoryProvider() ?: run {
            Log.w(TAG, "⚠️ ImageProcessorRepository not available for photo upload")
            return
        }

        val localPath = log.photoLocalPath ?: return
        val serverId = log.serverId ?: return
        val file = File(localPath)

        if (!file.exists()) {
            Log.w(TAG, "⚠️ Photo file not found: $localPath")
            return
        }

        Log.d(TAG, "📸 Creating photo upload assembly for atmospheric log serverId=$serverId")

        val fileToUpload = FileToUpload(
            uri = Uri.fromFile(file),
            filename = file.name,
            deleteOnCompletion = true
        )

        val result = repository.createAssembly(
            roomId = null,
            projectId = log.projectId,
            filesToUpload = listOf(fileToUpload),
            templateId = "atmospheric_log_photo",
            entityType = "AtmosphericLog",
            entityId = serverId
        )

        result.fold(
            onSuccess = { assemblyId ->
                Log.d(TAG, "✅ Photo upload assembly created: $assemblyId")
                // Update log with assembly ID and status
                val updated = log.copy(
                    photoAssemblyId = assemblyId,
                    photoUploadStatus = "uploading"
                )
                ctx.localDataService.saveAtmosphericLogs(listOf(updated))

                // Trigger queue processing
                ctx.imageProcessorQueueManagerProvider()?.processNextQueuedAssembly()
            },
            onFailure = { error ->
                Log.e(TAG, "❌ Failed to create photo upload assembly", error)
                // Mark as failed so it can be retried later
                val updated = log.copy(
                    photoUploadStatus = "failed"
                )
                ctx.localDataService.saveAtmosphericLogs(listOf(updated))
            }
        )
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getAtmosphericLogByUuid(operation.entityUuid)
            ?: ctx.localDataService.getAtmosphericLog(operation.entityId)
            ?: return OperationOutcome.DROP

        val serverId = log.serverId
            ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: log.updatedAt.toApiTimestamp()

        try {
            val response = ctx.api.deleteAtmosphericLog(
                serverId,
                DeleteWithTimestampRequest(updatedAt = lockUpdatedAt)
            )
            if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
                throw HttpException(response)
            }
        } catch (error: Throwable) {
            if (!error.isMissingOnServer()) {
                throw error
            }
        }

        val cleaned = log.copy(
            isDirty = false,
            isDeleted = true,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveAtmosphericLogs(listOf(cleaned))
        return OperationOutcome.SUCCESS
    }

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
