package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.AtmosphericLogRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import retrofit2.HttpException

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
            // Copy photoUrl from server response if available (e.g., after photo processing completed)
            photoUrl = dto.photoUrl ?: log.photoUrl,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveAtmosphericLogs(listOf(synced))

        // Promote any WAITING_FOR_ENTITY assemblies for this log now that it has a serverId
        if (synced.serverId != null && synced.photoAssemblyId != null) {
            promoteWaitingAssembly(synced)
        }

        return OperationOutcome.SUCCESS
    }

    /**
     * Promote WAITING_FOR_ENTITY assemblies to QUEUED now that the log has a serverId.
     * The assembly was created at photo capture time with entityUuid but no entityId.
     * Now we can provide the entityId and let the queue process the upload.
     */
    private suspend fun promoteWaitingAssembly(log: OfflineAtmosphericLogEntity) {
        val serverId = log.serverId ?: return
        val queueManager = ctx.imageProcessorQueueManagerProvider()

        if (queueManager == null) {
            Log.w(TAG, "⚠️ ImageProcessorQueueManager not available for assembly promotion")
            return
        }

        Log.d(TAG, "📸 Promoting waiting assembly for atmospheric log: uuid=${log.uuid} serverId=$serverId assemblyId=${log.photoAssemblyId}")

        queueManager.promoteWaitingAssembly(
            entityType = "AtmosphericLog", // Must match iOS/server expected format
            entityUuid = log.uuid,
            entityId = serverId
        )

        // Update log status to reflect the assembly is now queued for upload
        val updated = log.copy(
            photoUploadStatus = "uploading"
        )
        ctx.localDataService.saveAtmosphericLogs(listOf(updated))

        Log.d(TAG, "✅ Assembly promoted and log photo status updated to 'uploading'")
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
