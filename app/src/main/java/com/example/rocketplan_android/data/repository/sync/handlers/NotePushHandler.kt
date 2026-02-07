package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.CreateNoteRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.CancellationException
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

        val lockUpdatedAt = (note.serverUpdatedAt ?: note.updatedAt).toApiTimestamp()
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
            try {
                ctx.api.updateNote(note.serverId, request).data
            } catch (error: HttpException) {
                if (error.code() == 409) {
                    Log.w(SYNC_TAG, "⚠️ [handlePendingNoteUpsert] 409 conflict for note ${note.serverId}; extracting fresh timestamp and retrying")
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Note update 409 conflict",
                        mapOf("noteServerId" to note.serverId.toString(), "noteUuid" to note.uuid)
                    )
                    // Extract updated_at directly from the 409 response body.
                    // This avoids the paginated getProjectNotes endpoint which
                    // may not contain the target note on the first page.
                    val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
                    if (freshUpdatedAt == null) {
                        Log.w(SYNC_TAG, "⚠️ [handlePendingNoteUpsert] Could not extract updated_at from 409 body for note ${note.serverId}; will retry later")
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Note update 409 recovery deferred - no updated_at in response body",
                            mapOf("noteServerId" to note.serverId.toString(), "noteUuid" to note.uuid)
                        )
                        return OperationOutcome.SKIP
                    }

                    // Retry with fresh updatedAt
                    val retryRequest = request.copy(updatedAt = freshUpdatedAt)
                    val retryResult = runCatching { ctx.api.updateNote(note.serverId, retryRequest) }
                        .onFailure { if (it is CancellationException) throw it }
                    retryResult.onFailure { retryError ->
                        if (retryError.isConflict()) {
                            Log.w(SYNC_TAG, "⚠️ [handlePendingNoteUpsert] Retry still got 409; recording conflict")
                            ctx.remoteLogger?.log(
                                LogLevel.WARN, SYNC_TAG, "Note update double-409 - recording conflict",
                                mapOf("noteServerId" to note.serverId.toString(), "noteUuid" to note.uuid)
                            )
                            val conflict = OfflineConflictResolutionEntity(
                                conflictId = UuidUtils.generateUuidV7(),
                                entityType = "note",
                                entityId = note.noteId,
                                entityUuid = note.uuid,
                                localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                    "body" to note.content,
                                    "categoryId" to note.categoryId
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
                        throw retryError
                    }
                    Log.d(SYNC_TAG, "✅ [handlePendingNoteUpsert] Retry update succeeded for note ${note.serverId}")
                    retryResult.getOrThrow().data
                } else {
                    throw error
                }
            }
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
        val lockUpdatedAt = (note.serverUpdatedAt ?: note.updatedAt).toApiTimestamp()

        val response = ctx.api.deleteNote(
            serverId,
            DeleteWithTimestampRequest(updatedAt = lockUpdatedAt)
        )
        if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
            throw HttpException(response)
        }

        val cleaned = note.copy(
            isDeleted = true,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveNote(cleaned)
        // Clear tombstone now that server confirmed deletion
        DeletionTombstoneCache.clearTombstone("note", serverId)
        return OperationOutcome.SUCCESS
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }
}
