package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.logging.LogLevel
import retrofit2.HttpException

/**
 * Handles pushing photo delete operations to the server.
 * Note: Photo uploads are handled by ImageProcessorQueueManager, not here.
 */
class PhotoPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val photo = ctx.localDataService.getPhoto(operation.entityId)
            ?: return OperationOutcome.DROP
        val serverId = photo.serverId
            ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = (photo.serverUpdatedAt ?: photo.updatedAt).toApiTimestamp()

        val response = ctx.api.deletePhoto(
            serverId,
            DeleteWithTimestampRequest(updatedAt = lockUpdatedAt)
        )
        if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
            if (response.code() == 422) {
                Log.w(SYNC_TAG, "Dropping photo delete ${photo.serverId}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Photo delete dropped - 422 validation error",
                    mapOf("serverId" to photo.serverId.toString())
                )
                return OperationOutcome.DROP
            }
            throw HttpException(response)
        }

        val cleaned = photo.copy(
            isDeleted = true,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.savePhotos(listOf(cleaned))
        // Clear tombstone now that server confirmed deletion
        DeletionTombstoneCache.clearTombstone("photo", serverId)
        return OperationOutcome.SUCCESS
    }
}
