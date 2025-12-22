package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

/**
 * Handles note CRUD operations and queues sync work via SyncQueueProcessor.
 */
class NoteSyncService(
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val logLocalDeletion: (String, Long, String?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    suspend fun createNote(
        projectId: Long,
        content: String,
        roomId: Long? = null,
        categoryId: Long? = null,
        photoId: Long? = null
    ): OfflineNoteEntity = withContext(ioDispatcher) {
        val timestamp = now()
        val pending = OfflineNoteEntity(
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            roomId = roomId,
            photoId = photoId,
            content = content,
            categoryId = categoryId,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false
        )
        localDataService.saveNotes(listOf(pending))
        val saved = localDataService.getNoteByUuid(pending.uuid) ?: pending
        syncQueueEnqueuer().enqueueNoteUpsert(saved)
        saved
    }

    suspend fun updateNote(
        note: OfflineNoteEntity,
        newContent: String
    ): OfflineNoteEntity = withContext(ioDispatcher) {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty() || trimmed == note.content) return@withContext note
        val updated = note.copy(
            content = trimmed,
            updatedAt = now(),
            isDirty = true,
            syncStatus = SyncStatus.PENDING
        )
        val lockUpdatedAt = note.updatedAt.toApiTimestamp()
        localDataService.saveNote(updated)
        syncQueueEnqueuer().enqueueNoteUpsert(updated, lockUpdatedAt)
        updated
    }

    suspend fun deleteNote(
        projectId: Long,
        note: OfflineNoteEntity
    ) = withContext(ioDispatcher) {
        val lockUpdatedAt = note.updatedAt.toApiTimestamp()
        val updated = note.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = now()
        )
        localDataService.saveNote(updated)

        if (note.serverId == null) {
            localDataService.removeSyncOperationsForEntity(entityType = "note", entityId = note.noteId)
            logLocalDeletion("note", note.noteId, note.uuid)
            localDataService.saveNote(
                updated.copy(
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = now()
                )
            )
        } else {
            syncQueueEnqueuer().enqueueNoteDeletion(updated, lockUpdatedAt)
        }
    }
}
