package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.repository.sync.FreshTimestampService
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
    val changedFields: List<String>,
    val requeueAttempts: Int = 0,
    val maxRequeueAttempts: Int = 3
)

/**
 * Resolution strategy for conflicts.
 */
enum class ConflictResolution {
    KEEP_LOCAL,
    KEEP_SERVER,
    DISMISS
}

/**
 * Repository for managing sync conflicts.
 * Provides methods to observe, retrieve, and resolve conflicts.
 *
 * Enhanced for iOS parity with:
 * - Fresh timestamp fetching before requeue
 * - Requeue attempt tracking to prevent infinite loops
 * - Dismiss/ignore option
 * - Extended entity support
 */
class ConflictRepository(
    private val localDataService: LocalDataService,
    private val gson: Gson,
    private val freshTimestampService: FreshTimestampService? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "ConflictRepository"
    }
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
     *
     * Note: For better conflict resolution with fresh timestamps from server,
     * use resolveKeepLocalWithFreshTimestamp() instead.
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
            "project" -> reEnqueueProject(conflict, conflict.localVersion)
            "property" -> reEnqueueProperty(conflict, conflict.localVersion)
            "note" -> reEnqueueNote(conflict, conflict.localVersion)
            "equipment" -> reEnqueueEquipment(conflict, conflict.localVersion)
            "atmosphericLog" -> reEnqueueAtmosphericLog(conflict, conflict.localVersion)
            "moistureLog" -> reEnqueueMoistureLog(conflict, conflict.localVersion)
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
            "project" -> applyServerProject(conflict)
            "property" -> applyServerProperty(conflict)
            "note" -> applyServerNote(conflict)
            "equipment" -> applyServerEquipment(conflict)
            "atmosphericLog" -> applyServerAtmosphericLog(conflict)
            "moistureLog" -> applyServerMoistureLog(conflict)
        }

        // Mark conflict as resolved and delete
        val resolved = conflict.copy(
            resolvedAt = Date(),
            resolution = "KEEP_SERVER"
        )
        localDataService.upsertConflict(resolved)
        localDataService.resolveConflict(conflictId)
    }

    /**
     * Resolves a conflict by keeping the local version with a fresh server timestamp.
     * This prevents immediate re-conflict on the next sync by using the server's
     * current timestamp for the entity.
     *
     * @param conflictId The conflict to resolve
     * @return True if resolution succeeded, false if max requeue attempts exceeded or error
     */
    suspend fun resolveKeepLocalWithFreshTimestamp(conflictId: String): Boolean = withContext(ioDispatcher) {
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext false

        // Check if we've exceeded max requeue attempts
        if (!incrementRequeueAttempt(conflictId)) {
            Log.w(TAG, "Conflict $conflictId exceeded max requeue attempts (${conflict.requeueAttempts}/${conflict.maxRequeueAttempts})")
            return@withContext false
        }

        // Try to fetch fresh timestamp from server
        val freshTimestamp = if (freshTimestampService != null && conflict.entityId > 0) {
            val serverId = getServerIdForConflict(conflict)
            if (serverId != null) {
                freshTimestampService.fetchFreshTimestamp(conflict.entityType, serverId)
            } else {
                null
            }
        } else {
            null
        }

        // Use fresh timestamp or current time as fallback
        val timestamp = freshTimestamp ?: java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            java.util.Locale.US
        ).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())

        Log.d(TAG, "Resolving conflict $conflictId with timestamp: $timestamp (fresh: ${freshTimestamp != null})")

        // Update payload with fresh timestamp
        val updatedPayload = updatePayloadTimestamp(conflict.localVersion, timestamp)

        // Mark conflict as resolved
        val resolved = conflict.copy(
            resolvedAt = Date(),
            resolution = "KEEP_LOCAL_WITH_FRESH_TIMESTAMP",
            lastRequeueAt = Date()
        )
        localDataService.upsertConflict(resolved)

        // Re-enqueue with updated payload
        when (conflict.entityType) {
            "room" -> reEnqueueRoomWithPayload(conflict, updatedPayload)
            "location" -> reEnqueueLocationWithPayload(conflict, updatedPayload)
            "project" -> reEnqueueProject(conflict, updatedPayload)
            "property" -> reEnqueueProperty(conflict, updatedPayload)
            "note" -> reEnqueueNote(conflict, updatedPayload)
            "equipment" -> reEnqueueEquipment(conflict, updatedPayload)
            "atmosphericLog" -> reEnqueueAtmosphericLog(conflict, updatedPayload)
            "moistureLog" -> reEnqueueMoistureLog(conflict, updatedPayload)
            else -> {
                Log.w(TAG, "Unsupported entity type for requeue: ${conflict.entityType}")
            }
        }

        // Delete the conflict record after successful resolution
        localDataService.resolveConflict(conflictId)
        true
    }

    /**
     * Dismisses a conflict without taking any action.
     * The conflict is marked as resolved but no data is changed.
     *
     * Use this when the user wants to ignore the conflict and keep both versions as-is
     * (local data stays local, server data is not fetched).
     */
    suspend fun resolveDismiss(conflictId: String) = withContext(ioDispatcher) {
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext

        Log.d(TAG, "Dismissing conflict $conflictId for ${conflict.entityType}:${conflict.entityId}")

        // Mark conflict as resolved with DISMISS resolution
        val resolved = conflict.copy(
            resolvedAt = Date(),
            resolution = "DISMISS",
            notes = (conflict.notes ?: "") + "\nDismissed by user at ${Date()}"
        )
        localDataService.upsertConflict(resolved)
        localDataService.resolveConflict(conflictId)
    }

    /**
     * Increments the requeue attempt counter for a conflict.
     *
     * @param conflictId The conflict to update
     * @return True if increment succeeded and attempts are within limits, false if max exceeded
     */
    suspend fun incrementRequeueAttempt(conflictId: String): Boolean = withContext(ioDispatcher) {
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext false

        val newAttempts = conflict.requeueAttempts + 1
        if (newAttempts > conflict.maxRequeueAttempts) {
            Log.w(TAG, "Conflict $conflictId has exceeded max requeue attempts ($newAttempts > ${conflict.maxRequeueAttempts})")
            return@withContext false
        }

        val updated = conflict.copy(
            requeueAttempts = newAttempts,
            lastRequeueAt = Date()
        )
        localDataService.upsertConflict(updated)

        Log.d(TAG, "Incremented requeue attempts for conflict $conflictId: $newAttempts/${conflict.maxRequeueAttempts}")
        true
    }

    /**
     * Checks if a conflict can be requeued (has attempts remaining).
     */
    suspend fun canRequeue(conflictId: String): Boolean = withContext(ioDispatcher) {
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext false
        conflict.requeueAttempts < conflict.maxRequeueAttempts
    }

    // Helper to get server ID from conflict entity
    private suspend fun getServerIdForConflict(conflict: OfflineConflictResolutionEntity): Long? {
        return when (conflict.entityType) {
            "room" -> localDataService.getRoom(conflict.entityId)?.serverId
            "location" -> localDataService.getLocation(conflict.entityId)?.serverId
            "project" -> localDataService.getProject(conflict.entityId)?.serverId
            "note" -> localDataService.getNote(conflict.entityId)?.serverId
            "equipment" -> localDataService.getEquipment(conflict.entityId)?.serverId
            "atmosphericLog" -> localDataService.getAtmosphericLog(conflict.entityId)?.serverId
            "moistureLog" -> localDataService.getMoistureLog(conflict.entityId)?.serverId
            else -> null
        }
    }

    // Helper to update updatedAt in payload
    @Suppress("UNCHECKED_CAST")
    private fun updatePayloadTimestamp(payload: ByteArray, timestamp: String): ByteArray {
        return try {
            val data = gson.fromJson(payload.toString(Charsets.UTF_8), Map::class.java) as? MutableMap<String, Any?>
                ?: mutableMapOf()
            data["updatedAt"] = timestamp
            data["updated_at"] = timestamp  // Handle both formats
            gson.toJson(data).toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update payload timestamp", e)
            payload
        }
    }

    // Extended requeue methods for additional entity types

    private suspend fun reEnqueueRoomWithPayload(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
        val room = localDataService.getRoom(conflict.entityId) ?: return

        val updated = room.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveRooms(listOf(updated))

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "room",
                entityId = room.roomId,
                entityUuid = room.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueLocationWithPayload(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
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
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueProject(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
        val project = localDataService.getProject(conflict.entityId) ?: return

        val updated = project.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveProjects(listOf(updated))

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "project",
                entityId = project.projectId,
                entityUuid = project.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueProperty(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
        // Property doesn't have isDirty flag in current schema, just enqueue
        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "property",
                entityId = conflict.entityId,
                entityUuid = conflict.entityUuid,
                operationType = SyncOperationType.UPDATE,
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueNote(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
        val note = localDataService.getNote(conflict.entityId) ?: return

        val updated = note.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveNotes(listOf(updated))

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "note",
                entityId = note.noteId,
                entityUuid = note.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueEquipment(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
        val equipment = localDataService.getEquipment(conflict.entityId) ?: return

        val updated = equipment.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveEquipment(listOf(updated))

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "equipment",
                entityId = equipment.equipmentId,
                entityUuid = equipment.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueAtmosphericLog(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
        val log = localDataService.getAtmosphericLog(conflict.entityId) ?: return

        val updated = log.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveAtmosphericLogs(listOf(updated))

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "atmosphericLog",
                entityId = log.logId,
                entityUuid = log.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueMoistureLog(conflict: OfflineConflictResolutionEntity, payload: ByteArray) {
        val log = localDataService.getMoistureLog(conflict.entityId) ?: return

        val updated = log.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveMoistureLogs(listOf(updated))

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "moistureLog",
                entityId = log.logId,
                entityUuid = log.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = payload,
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    // Extended server apply methods for additional entity types

    private suspend fun applyServerProject(conflict: OfflineConflictResolutionEntity) {
        val project = localDataService.getProject(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

        val updated = project.copy(
            title = serverData["title"] as? String ?: project.title,
            status = serverData["status"] as? String ?: project.status,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = Date()
        )
        localDataService.saveProjects(listOf(updated))
    }

    private suspend fun applyServerProperty(conflict: OfflineConflictResolutionEntity) {
        // Property entity is immutable after creation in current schema
        Log.d(TAG, "applyServerProperty: Property ${conflict.entityId} - no mutable fields to update")
    }

    private suspend fun applyServerNote(conflict: OfflineConflictResolutionEntity) {
        val note = localDataService.getNote(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

        val updated = note.copy(
            content = serverData["body"] as? String ?: serverData["content"] as? String ?: note.content,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = Date()
        )
        localDataService.saveNotes(listOf(updated))
    }

    private suspend fun applyServerEquipment(conflict: OfflineConflictResolutionEntity) {
        val equipment = localDataService.getEquipment(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

        val updated = equipment.copy(
            type = serverData["type"] as? String ?: equipment.type,
            status = serverData["status"] as? String ?: equipment.status,
            quantity = (serverData["quantity"] as? Number)?.toInt() ?: equipment.quantity,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = Date()
        )
        localDataService.saveEquipment(listOf(updated))
    }

    private suspend fun applyServerAtmosphericLog(conflict: OfflineConflictResolutionEntity) {
        val log = localDataService.getAtmosphericLog(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

        val updated = log.copy(
            relativeHumidity = (serverData["relativeHumidity"] as? Number)?.toDouble() ?: log.relativeHumidity,
            temperature = (serverData["temperature"] as? Number)?.toDouble() ?: log.temperature,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = Date()
        )
        localDataService.saveAtmosphericLogs(listOf(updated))
    }

    private suspend fun applyServerMoistureLog(conflict: OfflineConflictResolutionEntity) {
        val log = localDataService.getMoistureLog(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

        val updated = log.copy(
            moistureContent = (serverData["moistureContent"] as? Number)?.toDouble() ?: log.moistureContent,
            location = serverData["location"] as? String ?: log.location,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = Date()
        )
        localDataService.saveMoistureLogs(listOf(updated))
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
            "project" -> localDataService.getProject(entity.entityId)?.title ?: "Project"
            "property" -> localDataService.getProperty(entity.entityId)?.address ?: "Property"
            "note" -> localDataService.getNote(entity.entityId)?.content?.take(50) ?: "Note"
            "equipment" -> localDataService.getEquipment(entity.entityId)?.type ?: "Equipment"
            "atmosphericLog" -> "Atmospheric Log"
            "moistureLog" -> "Moisture Log"
            else -> entity.entityType.replaceFirstChar { it.uppercase() }
        }

        // Get project name for context
        val projectName = when (entity.entityType) {
            "room" -> {
                val room = localDataService.getRoom(entity.entityId)
                room?.let { localDataService.getProject(it.projectId)?.title ?: localDataService.getProject(it.projectId)?.addressLine1 }
            }
            "location" -> {
                val location = localDataService.getLocation(entity.entityId)
                location?.let { localDataService.getProject(it.projectId)?.title ?: localDataService.getProject(it.projectId)?.addressLine1 }
            }
            "project" -> {
                localDataService.getProject(entity.entityId)?.title
            }
            "note" -> {
                val note = localDataService.getNote(entity.entityId)
                note?.let { localDataService.getProject(it.projectId)?.title ?: localDataService.getProject(it.projectId)?.addressLine1 }
            }
            "equipment" -> {
                val equipment = localDataService.getEquipment(entity.entityId)
                equipment?.let { localDataService.getProject(it.projectId)?.title ?: localDataService.getProject(it.projectId)?.addressLine1 }
            }
            "atmosphericLog" -> {
                val log = localDataService.getAtmosphericLog(entity.entityId)
                log?.let { localDataService.getProject(it.projectId)?.title ?: localDataService.getProject(it.projectId)?.addressLine1 }
            }
            "moistureLog" -> {
                val log = localDataService.getMoistureLog(entity.entityId)
                log?.let { localDataService.getProject(it.projectId)?.title ?: localDataService.getProject(it.projectId)?.addressLine1 }
            }
            else -> entity.projectId?.let { localDataService.getProject(it)?.title }
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
            changedFields = changedFields,
            requeueAttempts = entity.requeueAttempts,
            maxRequeueAttempts = entity.maxRequeueAttempts
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
