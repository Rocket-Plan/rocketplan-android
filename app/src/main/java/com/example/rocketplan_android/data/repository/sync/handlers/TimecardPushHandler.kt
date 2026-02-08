package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineTimecardEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toCreateRequest
import com.example.rocketplan_android.data.repository.mapper.toUpdateRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handles pushing timecard upsert/delete operations to the server.
 */
class TimecardPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val timecard = ctx.localDataService.getTimecardByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (timecard.isDeleted) return OperationOutcome.DROP

        val projectServerId = resolveServerProjectId(timecard.projectId)
        if (projectServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Timecard waiting for project to sync",
                mapOf(
                    "timecardUuid" to timecard.uuid,
                    "projectId" to timecard.projectId.toString()
                )
            )
            return OperationOutcome.SKIP
        }

        return try {
            val synced = pushPendingTimecardUpsert(timecard, projectServerId, operation)
            synced?.let { ctx.localDataService.saveTimecard(it) }
            if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
        } catch (e: Exception) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping timecard ${timecard.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Timecard dropped - 422 validation error",
                    mapOf("timecardUuid" to timecard.uuid, "serverId" to (timecard.serverId?.toString() ?: "null"))
                )
                OperationOutcome.DROP
            } else throw e
        }
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val timecard = ctx.localDataService.getTimecardByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val lockUpdatedAt = (timecard.serverUpdatedAt ?: timecard.updatedAt).toApiTimestamp()
        return try {
            val synced = pushPendingTimecardDeletion(timecard, lockUpdatedAt)
            synced?.let { ctx.localDataService.saveTimecard(it) }
            if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
        } catch (e: Exception) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping timecard delete ${timecard.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Timecard delete dropped - 422 validation error",
                    mapOf("timecardUuid" to timecard.uuid, "serverId" to (timecard.serverId?.toString() ?: "null"))
                )
                OperationOutcome.DROP
            } else throw e
        }
    }

    private suspend fun pushPendingTimecardUpsert(
        timecard: OfflineTimecardEntity,
        projectServerId: Long,
        operation: OfflineSyncQueueEntity
    ): OfflineTimecardEntity? {
        if (timecard.serverId == null) {
            // Create new timecard - no conflict possible
            val dto = runCatching {
                ctx.api.createTimecard(projectServerId, timecard.toCreateRequest())
            }.onFailure { error ->
                val errorBody = (error as? HttpException)?.response()?.errorBody()?.string()
                Log.w(SYNC_TAG, "⚠️ [syncPendingTimecard] Failed to create timecard ${timecard.uuid}: $errorBody", error)
            }.getOrElse { throw it }

            return dto.toEntity(timecard.companyId).copy(
                timecardId = timecard.timecardId,
                uuid = timecard.uuid,
                projectId = timecard.projectId,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = false,
                lastSyncedAt = ctx.now()
            )
        }

        // Update existing timecard - handle 409 conflicts
        val lockUpdatedAt = (timecard.serverUpdatedAt ?: timecard.updatedAt).toApiTimestamp()
        val updateRequest = timecard.toUpdateRequest(lockUpdatedAt)

        val dto = try {
            ctx.api.updateTimecard(timecard.serverId, updateRequest)
        } catch (error: HttpException) {
            if (error.code() == 409) {
                Log.w(SYNC_TAG, "⚠️ [syncPendingTimecard] 409 conflict for timecard ${timecard.serverId}; extracting fresh timestamp and retrying")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Timecard update 409 conflict",
                    mapOf(
                        "timecardServerId" to timecard.serverId.toString(),
                        "timecardUuid" to timecard.uuid,
                        "lockUpdatedAt" to (lockUpdatedAt ?: "null"),
                        "serverUpdatedAt" to (timecard.serverUpdatedAt?.time?.toString() ?: "null")
                    )
                )

                val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
                if (freshUpdatedAt == null) {
                    Log.w(SYNC_TAG, "⚠️ [syncPendingTimecard] Could not extract updated_at from 409 body for timecard ${timecard.serverId}; will retry later")
                    return null // SKIP
                }

                // Retry with fresh timestamp
                val retryRequest = timecard.toUpdateRequest(freshUpdatedAt)
                val retryResult = runCatching { ctx.api.updateTimecard(timecard.serverId, retryRequest) }
                    .onFailure { if (it is CancellationException) throw it }

                retryResult.onFailure { retryError ->
                    if (retryError.isConflict()) {
                        Log.w(SYNC_TAG, "⚠️ [syncPendingTimecard] Retry still got 409; recording conflict for user resolution")
                        val conflict = OfflineConflictResolutionEntity(
                            conflictId = UuidUtils.generateUuidV7(),
                            entityType = "timecard",
                            entityId = timecard.timecardId,
                            entityUuid = timecard.uuid,
                            localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "timeIn" to timecard.timeIn.toApiTimestamp(),
                                "timeOut" to timecard.timeOut?.toApiTimestamp(),
                                "notes" to timecard.notes,
                                "timecardTypeId" to timecard.timecardTypeId
                            )).toByteArray(Charsets.UTF_8),
                            remoteVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "updatedAt" to freshUpdatedAt
                            )).toByteArray(Charsets.UTF_8),
                            conflictType = "UPDATE_CONFLICT",
                            detectedAt = ctx.now(),
                            originalOperationId = operation.operationId
                        )
                        ctx.localDataService.upsertConflict(conflict)
                        return null // CONFLICT_PENDING handled via conflict record
                    }
                    throw retryError
                }

                retryResult.getOrThrow()
            } else if (error.isMissingOnServer()) {
                // Server doesn't have this timecard, re-create
                val createRequest = timecard.toCreateRequest()
                ctx.api.createTimecard(projectServerId, createRequest)
            } else {
                throw error
            }
        }

        return dto.toEntity(timecard.companyId).copy(
            timecardId = timecard.timecardId,
            uuid = timecard.uuid,
            projectId = timecard.projectId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = ctx.now()
        )
    }

    private suspend fun pushPendingTimecardDeletion(
        timecard: OfflineTimecardEntity,
        lockUpdatedAt: String? = null
    ): OfflineTimecardEntity? {
        if (timecard.serverId == null) {
            // Never reached server; treat as resolved locally
            return timecard.copy(
                isDeleted = true,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }

        val deleteRequest = DeleteWithTimestampRequest(
            updatedAt = lockUpdatedAt ?: (timecard.serverUpdatedAt ?: timecard.updatedAt).toApiTimestamp()
        )
        return runCatching {
            ctx.api.deleteTimecard(timecard.serverId, deleteRequest)
            // Clear tombstone now that server confirmed deletion
            DeletionTombstoneCache.clearTombstone("timecard", timecard.serverId)
            timecard.copy(
                isDeleted = true,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() -> {
                    // Clear tombstone - item is already gone from server
                    DeletionTombstoneCache.clearTombstone("timecard", timecard.serverId)
                    timecard.copy(
                        isDeleted = true,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        lastSyncedAt = ctx.now()
                    )
                }
                else -> throw error
            }
        }.onFailure {
            Log.w(SYNC_TAG, "⚠️ [syncPendingTimecard] Failed to delete timecard ${timecard.uuid}", it)
        }.getOrElse { throw it }
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }
}
