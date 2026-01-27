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
     * This method rebuilds the proper sync payload from the local entity
     * and uses a generated timestamp. For fetching fresh timestamps from
     * the server, use resolveKeepLocalWithFreshTimestamp() instead.
     */
    suspend fun resolveKeepLocal(conflictId: String) = withContext(ioDispatcher) {
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext

        // Generate current timestamp for the lock field
        val timestamp = formatTimestamp(Date())

        // Mark conflict as resolved
        val resolved = conflict.copy(
            resolvedAt = Date(),
            resolution = "KEEP_LOCAL"
        )
        localDataService.upsertConflict(resolved)

        // Re-enqueue with properly rebuilt payload from entity
        reEnqueueEntityWithTimestamp(conflict, timestamp)

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
            "atmospheric_log" -> applyServerAtmosphericLog(conflict)
            "moisture_log" -> applyServerMoistureLog(conflict)
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
        // First increment and check requeue attempts atomically
        val canProceed = incrementRequeueAttempt(conflictId)
        if (!canProceed) {
            Log.w(TAG, "Conflict $conflictId exceeded max requeue attempts")
            return@withContext false
        }

        // Re-read conflict after increment to get updated state
        val conflict = localDataService.getConflict(conflictId) ?: return@withContext false

        // Try to fetch fresh timestamp from server
        val freshTimestamp = if (freshTimestampService != null) {
            val serverId = getServerIdForConflict(conflict)
            if (serverId != null && serverId > 0) {
                freshTimestampService.fetchFreshTimestamp(conflict.entityType, serverId)
            } else {
                null
            }
        } else {
            null
        }

        // Use fresh timestamp or current time as fallback
        val timestamp = freshTimestamp ?: formatTimestamp(Date())

        Log.d(TAG, "Resolving conflict $conflictId with timestamp: $timestamp (fresh: ${freshTimestamp != null})")

        // Mark conflict as resolved
        val resolved = conflict.copy(
            resolvedAt = Date(),
            resolution = "KEEP_LOCAL_WITH_FRESH_TIMESTAMP",
            lastRequeueAt = Date()
        )
        localDataService.upsertConflict(resolved)

        // Re-enqueue with properly rebuilt payload from entity
        reEnqueueEntityWithTimestamp(conflict, timestamp)

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

    // ========================================================================
    // Private helpers
    // ========================================================================

    private fun formatTimestamp(date: Date): String {
        return java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            java.util.Locale.US
        ).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(date)
    }

    /**
     * Gets the server ID for a conflict entity.
     * Returns null if entity not found or has no server ID.
     */
    private suspend fun getServerIdForConflict(conflict: OfflineConflictResolutionEntity): Long? {
        return when (conflict.entityType) {
            "room" -> localDataService.getRoom(conflict.entityId)?.serverId
            "location" -> localDataService.getLocation(conflict.entityId)?.serverId
            "project" -> localDataService.getProject(conflict.entityId)?.serverId
            "property" -> localDataService.getProperty(conflict.entityId)?.serverId
            "note" -> localDataService.getNote(conflict.entityId)?.serverId
            "equipment" -> localDataService.getEquipment(conflict.entityId)?.serverId
            "atmospheric_log" -> localDataService.getAtmosphericLog(conflict.entityId)?.serverId
            "moisture_log" -> localDataService.getMoistureLog(conflict.entityId)?.serverId
            else -> null
        }
    }

    /**
     * Re-enqueues an entity for sync with a proper payload built from the entity.
     * This is the central method that rebuilds proper Pending*UpdatePayload structures
     * that the sync handlers expect, rather than using display-only conflict data.
     */
    private suspend fun reEnqueueEntityWithTimestamp(
        conflict: OfflineConflictResolutionEntity,
        lockTimestamp: String
    ) {
        when (conflict.entityType) {
            "room" -> reEnqueueRoom(conflict, lockTimestamp)
            "location" -> reEnqueueLocation(conflict, lockTimestamp)
            "project" -> reEnqueueProject(conflict, lockTimestamp)
            "property" -> reEnqueueProperty(conflict, lockTimestamp)
            "note" -> reEnqueueNote(conflict, lockTimestamp)
            "equipment" -> reEnqueueEquipment(conflict, lockTimestamp)
            "atmospheric_log" -> reEnqueueAtmosphericLog(conflict, lockTimestamp)
            "moisture_log" -> reEnqueueMoistureLog(conflict, lockTimestamp)
            else -> {
                Log.w(TAG, "Unsupported entity type for requeue: ${conflict.entityType}")
            }
        }
    }

    // ========================================================================
    // Requeue methods - rebuild proper payloads from entities
    // ========================================================================

    private suspend fun reEnqueueRoom(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val room = localDataService.getRoom(conflict.entityId) ?: return

        // Parse the conflict's local version to get the intended changes
        val localData = parseVersionData(conflict.localVersion)

        // Update the room entity to mark it as dirty
        val updated = room.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveRooms(listOf(updated))

        // Build proper PendingRoomUpdatePayload that the handler expects
        val payload = mapOf(
            "roomId" to room.roomId,
            "roomUuid" to room.uuid,
            "projectId" to room.projectId,
            "locationId" to room.locationId,
            "isSource" to (localData["isSource"] as? Boolean ?: room.isAccessible),
            "levelId" to (localData["levelId"] as? Number)?.toLong(),
            "roomTypeId" to ((localData["roomTypeId"] as? Number)?.toLong() ?: room.roomTypeId),
            "lockUpdatedAt" to lockTimestamp
        )

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "room",
                entityId = room.roomId,
                entityUuid = room.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueLocation(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val location = localDataService.getLocation(conflict.entityId) ?: return

        // Parse the conflict's local version
        val localData = parseVersionData(conflict.localVersion)

        val updated = location.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveLocations(listOf(updated))

        // Build proper PendingLocationUpdatePayload
        val payload = mapOf(
            "locationId" to location.locationId,
            "locationUuid" to location.uuid,
            "name" to (localData["title"] as? String ?: localData["name"] as? String ?: location.title),
            "floorNumber" to (localData["floorNumber"] as? Number)?.toInt(),
            "isAccessible" to (localData["isAccessible"] as? Boolean ?: location.isAccessible),
            "lockUpdatedAt" to lockTimestamp
        )

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "location",
                entityId = location.locationId,
                entityUuid = location.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueProject(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val project = localDataService.getProject(conflict.entityId) ?: return

        val localData = parseVersionData(conflict.localVersion)

        val updated = project.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveProjects(listOf(updated))

        val payload = mapOf(
            "projectId" to project.projectId,
            "projectUuid" to project.uuid,
            "title" to (localData["title"] as? String ?: project.title),
            "status" to (localData["status"] as? String ?: project.status),
            "lockUpdatedAt" to lockTimestamp
        )

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "project",
                entityId = project.projectId,
                entityUuid = project.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueProperty(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val property = localDataService.getProperty(conflict.entityId)

        val payload = mapOf(
            "propertyId" to conflict.entityId,
            "propertyUuid" to conflict.entityUuid,
            "lockUpdatedAt" to lockTimestamp
        )

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "property",
                entityId = conflict.entityId,
                entityUuid = conflict.entityUuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueNote(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val note = localDataService.getNote(conflict.entityId) ?: return

        val localData = parseVersionData(conflict.localVersion)

        val updated = note.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveNotes(listOf(updated))

        val payload = mapOf(
            "noteId" to note.noteId,
            "noteUuid" to note.uuid,
            "projectId" to note.projectId,
            "roomId" to note.roomId,
            "body" to (localData["body"] as? String ?: localData["content"] as? String ?: note.content),
            "lockUpdatedAt" to lockTimestamp
        )

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "note",
                entityId = note.noteId,
                entityUuid = note.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueEquipment(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val equipment = localDataService.getEquipment(conflict.entityId) ?: return

        val localData = parseVersionData(conflict.localVersion)

        val updated = equipment.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveEquipment(listOf(updated))

        val payload = mapOf(
            "equipmentId" to equipment.equipmentId,
            "equipmentUuid" to equipment.uuid,
            "projectId" to equipment.projectId,
            "roomId" to equipment.roomId,
            "type" to (localData["type"] as? String ?: equipment.type),
            "status" to (localData["status"] as? String ?: equipment.status),
            "quantity" to ((localData["quantity"] as? Number)?.toInt() ?: equipment.quantity),
            "lockUpdatedAt" to lockTimestamp
        )

        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "equipment",
                entityId = equipment.equipmentId,
                entityUuid = equipment.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueAtmosphericLog(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val log = localDataService.getAtmosphericLog(conflict.entityId) ?: return

        val localData = parseVersionData(conflict.localVersion)

        val updated = log.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveAtmosphericLogs(listOf(updated))

        val payload = mapOf(
            "logId" to log.logId,
            "logUuid" to log.uuid,
            "projectId" to log.projectId,
            "roomId" to log.roomId,
            "relativeHumidity" to ((localData["relativeHumidity"] as? Number)?.toDouble() ?: log.relativeHumidity),
            "temperature" to ((localData["temperature"] as? Number)?.toDouble() ?: log.temperature),
            "lockUpdatedAt" to lockTimestamp
        )

        // Use correct entity type that matches SyncQueueProcessor
        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "atmospheric_log",  // Must match processor
                entityId = log.logId,
                entityUuid = log.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    private suspend fun reEnqueueMoistureLog(conflict: OfflineConflictResolutionEntity, lockTimestamp: String) {
        val log = localDataService.getMoistureLog(conflict.entityId) ?: return

        val localData = parseVersionData(conflict.localVersion)

        val updated = log.copy(
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        localDataService.saveMoistureLogs(listOf(updated))

        val payload = mapOf(
            "logId" to log.logId,
            "logUuid" to log.uuid,
            "projectId" to log.projectId,
            "roomId" to log.roomId,
            "moistureContent" to ((localData["moistureContent"] as? Number)?.toDouble() ?: log.moistureContent),
            "location" to (localData["location"] as? String ?: log.location),
            "lockUpdatedAt" to lockTimestamp
        )

        // Use correct entity type that matches SyncQueueProcessor
        localDataService.enqueueSyncOperation(
            OfflineSyncQueueEntity(
                operationId = UuidUtils.generateUuidV7(),
                entityType = "moisture_log",  // Must match processor
                entityId = log.logId,
                entityUuid = log.uuid,
                operationType = SyncOperationType.UPDATE,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                createdAt = Date(),
                scheduledAt = Date(),
                status = SyncStatus.PENDING
            )
        )
    }

    // ========================================================================
    // Apply server version methods
    // ========================================================================

    private suspend fun applyServerRoom(conflict: OfflineConflictResolutionEntity) {
        val room = localDataService.getRoom(conflict.entityId) ?: return
        val serverData = parseVersionData(conflict.remoteVersion)

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

    // ========================================================================
    // Parsing and display helpers
    // ========================================================================

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
            "atmospheric_log" -> "Atmospheric Log"
            "moisture_log" -> "Moisture Log"
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
            "atmospheric_log" -> {
                val log = localDataService.getAtmosphericLog(entity.entityId)
                log?.let { localDataService.getProject(it.projectId)?.title ?: localDataService.getProject(it.projectId)?.addressLine1 }
            }
            "moisture_log" -> {
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
            key !in listOf("updatedAt", "lastSyncedAt", "isDirty", "syncStatus", "lockUpdatedAt") &&
                local[key] != remote[key]
        }.distinct()
    }
}
