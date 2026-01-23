package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.util.UuidUtils
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Represents a conflict item ready for UI display.
 */
data class ConflictItem(
    val conflictId: String,
    val entityType: String,
    val entityId: Long,
    val entityUuid: String,
    val entityName: String,
    val projectName: String?,
    val localVersion: Map<String, Any?>,
    val remoteVersion: Map<String, Any?>,
    val conflictType: String,
    val detectedAt: Date,
    val changedFields: List<String>
)

/**
 * Resolution strategy for conflicts.
 */
enum class ConflictResolution {
    KEEP_LOCAL,
    KEEP_SERVER
}

/**
 * Repository for managing sync conflicts.
 * Provides methods to observe, retrieve, and resolve conflicts.
 */
class ConflictRepository(
    private val localDataService: LocalDataService,
    private val gson: Gson,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Observes unresolved conflicts as ConflictItem objects for UI display.
     */
    fun observeConflicts(): Flow<List<ConflictItem>> {
        return localDataService.observeUnresolvedConflicts().map { conflicts ->
            conflicts.mapNotNull { entity -> entityToConflictItem(entity) }
        }
    }

    /**
     * Observes the count of unresolved conflicts.
     */
    fun observeUnresolvedCount(): Flow<Int> = localDataService.observeUnresolvedConflictCount()

    /**
     * Gets a single conflict by ID.
     */
    suspend fun getConflict(conflictId: String): ConflictItem? = withContext(ioDispatcher) {
        localDataService.getConflict(conflictId)?.let { entityToConflictItem(it) }
    }

    /**
     * Records a new conflict.
     */
    suspend fun recordConflict(conflict: OfflineConflictResolutionEntity) = withContext(ioDispatcher) {
        localDataService.upsertConflict(conflict)
    }

    /**
     * Resolves a conflict by keeping the local version.
     * Re-enqueues the sync operation with a fresh timestamp to push local changes.
     */
    suspend fun resolveKeepLocal(conflictId: String) = withContext(ioDispatcher) {
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext

        // Mark conflict as resolved
        val resolved = conflict.copy(
            resolvedAt = Date(),
            resolution = "KEEP_LOCAL"
        )
        localDataService.upsertConflict(resolved)

        // Re-enqueue the entity for sync with fresh timestamp based on entity type
        when (conflict.entityType) {
            "room" -> reEnqueueRoom(conflict)
            "location" -> reEnqueueLocation(conflict)
        }

        // Delete the conflict record after successful resolution
        localDataService.resolveConflict(conflictId)
    }

    /**
     * Resolves a conflict by keeping the server version.
     * Saves server data locally and removes the conflict.
     */
    suspend fun resolveKeepServer(conflictId: String) = withContext(ioDispatcher) {
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext

        // Apply server version to local database based on entity type
        when (conflict.entityType) {
            "room" -> applyServerRoom(conflict)
            "location" -> applyServerLocation(conflict)
        }

        // Mark conflict as resolved and delete
        val resolved = conflict.copy(
            resolvedAt = Date(),
            resolution = "KEEP_SERVER"
        )
        localDataService.upsertConflict(resolved)
        localDataService.resolveConflict(conflictId)
    }

    private suspend fun reEnqueueRoom(conflict: OfflineConflictResolutionEntity) {
        val room = localDataService.getRoom(conflict.entityId) ?: return

        // Update the room with fresh updatedAt to force re-sync
        val updated = room.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveRooms(listOf(updated))

        // Enqueue sync operation with the conflict's local version as payload
        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "room",
                entityId = room.roomId,
                entityUuid = room.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = conflict.localVersion,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueLocation(conflict: OfflineConflictResolutionEntity) {
        val location = localDataService.getLocation(conflict.entityId) ?: return

        val updated = location.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveLocations(listOf(updated))

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "location",
                entityId = location.locationId,
                entityUuid = location.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = conflict.localVersion,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun applyServerRoom(conflict: OfflineConflictResolutionEntity) {
        val room = localDataService.getRoom(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

        // Create updated room from server data - only update fields that exist
        val updated = room.copy(
            title = serverData["title"] as? String ?: room.title,
            roomTypeId = (serverData["roomTypeId"] as? Number)?.toLong() ?: room.roomTypeId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = Date()
        )
        localDataService.saveRooms(listOf(updated))
    }

    private suspend fun applyServerLocation(conflict: OfflineConflictResolutionEntity) {
        val location = localDataService.getLocation(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

        val updated = location.copy(
            title = serverData["title"] as? String ?: location.title,
            isAccessible = serverData["isAccessible"] as? Boolean ?: location.isAccessible,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = Date()
        )
        localDataService.saveLocations(listOf(updated))
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseVersionData(data: ByteArray): Map<String, Any?> {
        return try {
            gson.fromJson(data.toString(Charsets.UTF_8), Map::class.java) as? Map<String, Any?>
                ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun entityToConflictItem(entity: OfflineConflictResolutionEntity): ConflictItem? {
        val localData = parseVersionData(entity.localVersion)
        val remoteData = parseVersionData(entity.remoteVersion)

        // Get entity name based on type
        val entityName = when (entity.entityType) {
            "room" -> localDataService.getRoom(entity.entityId)?.title ?: "Room"
            "location" -> localDataService.getLocation(entity.entityId)?.title ?: "Location"
            else -> entity.entityType.replaceFirstChar { it.uppercase() }
        }

        // Get project name
        val projectName = when (entity.entityType) {
            "room" -> {
                val room = localDataService.getRoom(entity.entityId)
                room?.let { localDataService.getProject(it.projectId)?.addressLine1 }
            }
            "location" -> {
                val location = localDataService.getLocation(entity.entityId)
                location?.let { localDataService.getProject(it.projectId)?.addressLine1 }
            }
            else -> null
        }

        // Find changed fields
        val changedFields = findChangedFields(localData, remoteData)

        return ConflictItem(
            conflictId = entity.conflictId,
            entityType = entity.entityType,
            entityId = entity.entityId,
            entityUuid = entity.entityUuid,
            entityName = entityName,
            projectName = projectName,
            localVersion = localData,
            remoteVersion = remoteData,
            conflictType = entity.conflictType,
            detectedAt = entity.detectedAt,
            changedFields = changedFields
        )
    }

    private fun findChangedFields(local: Map<String, Any?>, remote: Map<String, Any?>): List<String> {
        val allKeys = local.keys + remote.keys
        return allKeys.filter { key ->
            // Skip metadata fields
            key !in listOf("updatedAt", "lastSyncedAt", "isDirty", "syncStatus") &&
                local[key] != remote[key]
        }.distinct()
    }
}
