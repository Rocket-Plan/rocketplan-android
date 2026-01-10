package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.CreateNoteRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
import retrofit2.HttpException

/**
 * Handles pushing note upsert/delete operations to the server.
 */
class NotePushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val note = ctx.localDataService.getNoteByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (note.isDeleted) return OperationOutcome.DROP

        val projectServerId = resolveServerProjectId(note.projectId)
            ?: return OperationOutcome.SKIP

        // Resolve roomId to serverId
        val roomServerId = note.roomId?.let { roomId ->
            ctx.localDataService.getRoom(roomId)?.serverId
        }
        if (note.roomId != null && roomServerId == null) {
            Log.d(
                SYNC_TAG,
                "⏳ [handlePendingNoteUpsert] Note ${note.uuid} waiting for room ${note.roomId} to sync"
            )
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Sync skip - dependency not ready",
                mapOf(
                    "entityType" to "note",
                    "entityUuid" to note.uuid,
                    "waitingFor" to "room",
                    "dependencyId" to note.roomId.toString()
                )
            )
            return OperationOutcome.SKIP
        }

        // Resolve photoId to serverId
        val photoServerId = note.photoId?.let { photoId ->
            ctx.localDataService.getPhoto(photoId)?.serverId
        }
        if (note.photoId != null && photoServerId == null) {
            Log.d(
                SYNC_TAG,
                "⏳ [handlePendingNoteUpsert] Note ${note.uuid} attached to photo ${note.photoId} " +
                    "which hasn't uploaded yet; will retry"
            )
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Sync skip - dependency not ready",
                mapOf(
                    "entityType" to "note",
                    "entityUuid" to note.uuid,
                    "waitingFor" to "photo",
                    "dependencyId" to note.photoId.toString()
                )
            )
            return OperationOutcome.SKIP
        }

        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: note.updatedAt.toApiTimestamp()
        val request = CreateNoteRequest(
            projectId = projectServerId,
            roomId = roomServerId,
            body = note.content,
            photoId = photoServerId,
            categoryId = note.categoryId,
            idempotencyKey = note.uuid,
            updatedAt = lockUpdatedAt
        )

        val dto = if (note.serverId == null) {
            ctx.api.createProjectNote(projectServerId, request.copy(updatedAt = null)).data
        } else {
            ctx.api.updateNote(note.serverId, request).data
        }

        val entity = dto.toEntity()?.copy(
            noteId = note.noteId,
            uuid = note.uuid,
            projectId = note.projectId,
            roomId = note.roomId ?: dto.roomId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = ctx.now()
        ) ?: return OperationOutcome.SKIP

        ctx.localDataService.saveNote(entity)
        return OperationOutcome.SUCCESS
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val note = ctx.localDataService.getNoteByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val serverId = note.serverId
            ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: note.updatedAt.toApiTimestamp()

        val response = ctx.api.deleteNote(
            serverId,
            DeleteWithTimestampRequest(updatedAt = lockUpdatedAt)
        )
        if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
            throw HttpException(response)
        }

        val cleaned = note.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveNote(cleaned)
        return OperationOutcome.SUCCESS
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
