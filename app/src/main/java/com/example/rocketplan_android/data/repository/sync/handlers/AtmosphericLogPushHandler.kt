package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.AtmosphericLogRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.util.UuidUtils
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

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

        val lockUpdatedAt = (log.serverUpdatedAt ?: log.updatedAt).toApiTimestamp()

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

        val dto = try {
            if (log.serverId == null) {
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.isConflict() && log.serverId != null) {
                return handle409Conflict(e as HttpException, log, request, operation)
            }
            if (e.isValidationError()) {
                Log.w(TAG, "Dropping atmospheric log ${log.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, TAG, "Atmospheric log dropped - 422 validation error",
                    mapOf("logUuid" to log.uuid, "serverId" to (log.serverId?.toString() ?: "null"))
                )
                return OperationOutcome.DROP
            }
            throw e
        }

        val synced = log.copy(
            serverId = dto.id,
            // Copy photoUrl from server response if available (e.g., after photo processing completed)
            photoUrl = dto.photoUrl ?: log.photoUrl,
            serverUpdatedAt = DateUtils.parseApiDate(dto.updatedAt) ?: ctx.now(),
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

    private suspend fun handle409Conflict(
        error: HttpException,
        log: OfflineAtmosphericLogEntity,
        request: AtmosphericLogRequest,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        Log.w(TAG, "⚠️ [syncPendingAtmosphericLog] 409 conflict for log ${log.serverId}; extracting fresh timestamp and retrying")
        ctx.remoteLogger?.log(
            LogLevel.WARN, TAG, "Atmospheric log update 409 conflict",
            mapOf("logServerId" to (log.serverId?.toString() ?: "null"), "logUuid" to log.uuid)
        )

        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        if (freshUpdatedAt == null) {
            Log.w(TAG, "⚠️ [syncPendingAtmosphericLog] Could not extract updated_at from 409 body for log ${log.serverId}; will retry later")
            return OperationOutcome.SKIP
        }

        // Retry with fresh timestamp
        val retryRequest = request.copy(updatedAt = freshUpdatedAt)
        val retryResult = runCatching { ctx.api.updateAtmosphericLog(log.serverId!!, retryRequest) }
            .onFailure { if (it is CancellationException) throw it }

        retryResult.onFailure { retryError ->
            if (retryError.isConflict()) {
                Log.w(TAG, "⚠️ [syncPendingAtmosphericLog] Retry still got 409; recording conflict for user resolution")
                val conflict = OfflineConflictResolutionEntity(
                    conflictId = UuidUtils.generateUuidV7(),
                    entityType = "atmospheric_log",
                    entityId = log.logId,
                    entityUuid = log.uuid,
                    localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                        "temperature" to log.temperature,
                        "relativeHumidity" to log.relativeHumidity,
                        "dewPoint" to log.dewPoint
                    )).toByteArray(Charsets.UTF_8),
                    remoteVersion = ctx.gson.toJson(mapOf<String, Any?>(
                        "updatedAt" to freshUpdatedAt
                    )).toByteArray(Charsets.UTF_8),
                    conflictType = "UPDATE_CONFLICT",
                    detectedAt = ctx.now(),
                    originalOperationId = operation.operationId
                )
                ctx.recordConflict(conflict)
                return OperationOutcome.CONFLICT_PENDING
            }
            if (retryError.isValidationError()) {
                Log.w(TAG, "Dropping atmospheric log ${log.uuid}: server validation error (422)")
                return OperationOutcome.DROP
            }
            throw retryError
        }

        // Retry succeeded - save
        val dto = retryResult.getOrThrow()
        val synced = log.copy(
            serverId = dto.id,
            photoUrl = dto.photoUrl ?: log.photoUrl,
            serverUpdatedAt = DateUtils.parseApiDate(dto.updatedAt) ?: ctx.now(),
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveAtmosphericLogs(listOf(synced))
        Log.d(TAG, "✅ [syncPendingAtmosphericLog] Retry update succeeded for log ${log.serverId}")
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
        val lockUpdatedAt = (log.serverUpdatedAt ?: log.updatedAt).toApiTimestamp()

        try {
            val response = ctx.api.deleteAtmosphericLog(
                serverId,
                DeleteWithTimestampRequest(updatedAt = lockUpdatedAt)
            )
            if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
                throw HttpException(response)
            }
        } catch (error: Throwable) {
            if (error.isValidationError()) {
                Log.w(TAG, "Dropping atmospheric log delete ${log.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, TAG, "Atmospheric log delete dropped - 422 validation error",
                    mapOf("logUuid" to log.uuid, "serverId" to (log.serverId?.toString() ?: "null"))
                )
                return OperationOutcome.DROP
            }
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
        // Clear tombstone now that server confirmed deletion
        DeletionTombstoneCache.clearTombstone("atmospheric_log", serverId)
        return OperationOutcome.SUCCESS
    }
}
