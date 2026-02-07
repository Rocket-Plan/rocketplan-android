package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineTimecardEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toCreateRequest
import com.example.rocketplan_android.data.repository.mapper.toUpdateRequest
import com.example.rocketplan_android.logging.LogLevel

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

        val lockUpdatedAt = (timecard.serverUpdatedAt ?: timecard.updatedAt).toApiTimestamp()
        return try {
            val synced = pushPendingTimecardUpsert(timecard, projectServerId, lockUpdatedAt)
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
        lockUpdatedAt: String? = null
    ): OfflineTimecardEntity? {
        val synced = runCatching {
            val dto = if (timecard.serverId == null) {
                // Create new timecard
                val createRequest = timecard.toCreateRequest()
                ctx.api.createTimecard(projectServerId, createRequest)
            } else {
                // Update existing timecard
                val updateRequest = timecard.toUpdateRequest(lockUpdatedAt)
                ctx.api.updateTimecard(timecard.serverId, updateRequest)
            }
            dto.toEntity(timecard.companyId).copy(
                timecardId = timecard.timecardId,
                uuid = timecard.uuid,
                projectId = timecard.projectId,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = false,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                timecard.serverId != null && error.isMissingOnServer() -> {
                    // Server doesn't have this timecard, create it
                    val createRequest = timecard.toCreateRequest()
                    val created = ctx.api.createTimecard(projectServerId, createRequest)
                    created.toEntity(timecard.companyId).copy(
                        timecardId = timecard.timecardId,
                        uuid = timecard.uuid,
                        projectId = timecard.projectId,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = ctx.now()
                    )
                }
                else -> throw error
            }
        }.onFailure { error ->
            val errorBody = (error as? retrofit2.HttpException)?.response()?.errorBody()?.string()
            Log.w(SYNC_TAG, "⚠️ [syncPendingTimecard] Failed to push timecard ${timecard.uuid}: $errorBody", error)
        }.getOrElse { throw it }

        return synced
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
