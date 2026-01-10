package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
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
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: photo.updatedAt.toApiTimestamp()

        val response = ctx.api.deletePhoto(
            serverId,
            DeleteWithTimestampRequest(updatedAt = lockUpdatedAt)
        )
        if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
            throw HttpException(response)
        }

        val cleaned = photo.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.savePhotos(listOf(cleaned))
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
