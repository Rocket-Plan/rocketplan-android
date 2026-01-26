package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.repository.mapper.PendingLocationCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomCreationPayload
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of an ID remapping operation.
 */
data class RemapResult(
    val entityType: String,
    val oldId: Long,
    val newId: Long,
    val operationsUpdated: Int
)

/**
 * Service for remapping IDs in pending sync operations when parent entities are synced.
 *
 * When an entity is created locally (with negative/local ID) and later synced to the server
 * (receiving a positive server ID), any pending child operations that reference the old
 * local ID need to be updated to use the new server ID.
 *
 * This service provides centralized methods for each entity type hierarchy:
 * - Project → Property, Location, Room, Note, Equipment, Logs
 * - Property → Location
 * - Location → Room
 * - Room → Note, Equipment, MoistureLog, AtmosphericLog
 */
class IdRemapService(
    private val localDataService: LocalDataService,
    private val gson: Gson,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Remaps project ID in all child entity pending operations.
     * Called after a project is synced and receives its server ID.
     *
     * @param localProjectId The local (negative) project ID
     * @param serverId The server-assigned project ID
     * @return The number of operations updated
     */
    suspend fun remapProjectId(localProjectId: Long, serverId: Long): Int = withContext(ioDispatcher) {
        if (localProjectId == serverId) return@withContext 0

        var totalUpdated = 0
        Log.d(TAG, "🔄 Remapping project ID: local=$localProjectId → server=$serverId")

        // Update property creation payloads that reference this project
        totalUpdated += remapPropertyPayloads(localProjectId, serverId)

        // Update location creation payloads
        totalUpdated += remapLocationPayloads(localProjectId, serverId)

        // Update room creation payloads
        totalUpdated += remapRoomPayloads(localProjectId, serverId)

        Log.d(TAG, "✅ Project ID remap complete: updated $totalUpdated operations")
        totalUpdated
    }

    /**
     * Remaps property ID in pending location operations.
     * Called after a property is synced and receives its server ID.
     *
     * @param localPropertyId The local (negative) property ID
     * @param serverId The server-assigned property ID
     * @return The number of operations updated
     */
    suspend fun remapPropertyId(localPropertyId: Long, serverId: Long): Int = withContext(ioDispatcher) {
        if (localPropertyId == serverId) return@withContext 0

        var totalUpdated = 0
        Log.d(TAG, "🔄 Remapping property ID: local=$localPropertyId → server=$serverId")

        // Get all pending location operations
        val pendingOps = localDataService.getPendingOperationsForEntityType("location")
        for (op in pendingOps) {
            val payload = runCatching {
                gson.fromJson(
                    String(op.payload, Charsets.UTF_8),
                    PendingLocationCreationPayload::class.java
                )
            }.getOrNull() ?: continue

            if (payload.propertyLocalId == localPropertyId) {
                val updatedPayload = payload.copy(propertyLocalId = serverId)
                val updatedOp = op.copy(
                    payload = gson.toJson(updatedPayload).toByteArray(Charsets.UTF_8)
                )
                localDataService.enqueueSyncOperation(updatedOp)
                totalUpdated++
            }
        }

        Log.d(TAG, "✅ Property ID remap complete: updated $totalUpdated location operations")
        totalUpdated
    }

    /**
     * Remaps location ID in pending room operations.
     * Called after a location is synced and receives its server ID.
     *
     * @param localLocationId The local (negative) location ID
     * @param serverId The server-assigned location ID
     * @param uuid The location UUID for fallback matching
     * @return The number of operations updated
     */
    suspend fun remapLocationId(
        localLocationId: Long,
        serverId: Long,
        uuid: String
    ): Int = withContext(ioDispatcher) {
        if (localLocationId == serverId) return@withContext 0

        var totalUpdated = 0
        Log.d(TAG, "🔄 Remapping location ID: local=$localLocationId → server=$serverId (uuid=$uuid)")

        // Get all pending room operations
        val pendingOps = localDataService.getPendingOperationsForEntityType("room")
        for (op in pendingOps) {
            val payload = runCatching {
                gson.fromJson(
                    String(op.payload, Charsets.UTF_8),
                    PendingRoomCreationPayload::class.java
                )
            }.getOrNull() ?: continue

            var updated = false
            var updatedPayload = payload

            // Update levelServerId if it matches
            if (payload.levelServerId == localLocationId || payload.levelUuid == uuid) {
                updatedPayload = updatedPayload.copy(levelServerId = serverId)
                updated = true
            }

            // Update locationServerId if it matches
            if (payload.locationServerId == localLocationId || payload.locationUuid == uuid) {
                updatedPayload = updatedPayload.copy(locationServerId = serverId)
                updated = true
            }

            if (updated) {
                val updatedOp = op.copy(
                    payload = gson.toJson(updatedPayload).toByteArray(Charsets.UTF_8)
                )
                localDataService.enqueueSyncOperation(updatedOp)
                totalUpdated++
            }
        }

        Log.d(TAG, "✅ Location ID remap complete: updated $totalUpdated room operations")
        totalUpdated
    }

    /**
     * Remaps room ID in pending child operations (notes, equipment, logs).
     * Called after a room is synced and receives its server ID.
     *
     * @param localRoomId The local (negative) room ID
     * @param serverId The server-assigned room ID
     * @param uuid The room UUID for fallback matching
     * @return The number of operations updated
     */
    suspend fun remapRoomId(
        localRoomId: Long,
        serverId: Long,
        uuid: String
    ): Int = withContext(ioDispatcher) {
        if (localRoomId == serverId) return@withContext 0

        var totalUpdated = 0
        Log.d(TAG, "🔄 Remapping room ID: local=$localRoomId → server=$serverId (uuid=$uuid)")

        // Update room references in local entities
        // Notes
        totalUpdated += localDataService.migrateNoteRoomIds(localRoomId, serverId)

        // Equipment
        totalUpdated += localDataService.migrateEquipmentRoomIds(localRoomId, serverId)

        // Moisture logs
        totalUpdated += localDataService.migrateMoistureLogRoomIds(localRoomId, serverId)

        // Atmospheric logs
        totalUpdated += localDataService.migrateAtmosphericLogRoomIds(localRoomId, serverId)

        // Photos
        totalUpdated += localDataService.migratePhotoRoomIds(localRoomId, serverId)

        // Albums
        totalUpdated += localDataService.migrateAlbumRoomIds(localRoomId, serverId)

        // Damages
        totalUpdated += localDataService.migrateDamageRoomIds(localRoomId, serverId)

        // Work scopes
        totalUpdated += localDataService.migrateWorkScopeRoomIds(localRoomId, serverId)

        Log.d(TAG, "✅ Room ID remap complete: updated $totalUpdated child entities")
        totalUpdated
    }

    // Private helpers for payload remapping

    private suspend fun remapPropertyPayloads(localProjectId: Long, serverId: Long): Int {
        var updated = 0
        val pendingOps = localDataService.getPendingOperationsForEntityType("property")

        for (op in pendingOps) {
            val payload = runCatching {
                gson.fromJson(
                    String(op.payload, Charsets.UTF_8),
                    PendingPropertyCreationPayload::class.java
                )
            }.getOrNull() ?: continue

            if (payload.projectId == localProjectId) {
                val updatedPayload = payload.copy(projectId = serverId)
                val updatedOp = op.copy(
                    payload = gson.toJson(updatedPayload).toByteArray(Charsets.UTF_8)
                )
                localDataService.enqueueSyncOperation(updatedOp)
                updated++
            }
        }

        return updated
    }

    private suspend fun remapLocationPayloads(localProjectId: Long, serverId: Long): Int {
        var updated = 0
        val pendingOps = localDataService.getPendingOperationsForEntityType("location")

        for (op in pendingOps) {
            val payload = runCatching {
                gson.fromJson(
                    String(op.payload, Charsets.UTF_8),
                    PendingLocationCreationPayload::class.java
                )
            }.getOrNull() ?: continue

            if (payload.projectId == localProjectId) {
                val updatedPayload = payload.copy(projectId = serverId)
                val updatedOp = op.copy(
                    payload = gson.toJson(updatedPayload).toByteArray(Charsets.UTF_8)
                )
                localDataService.enqueueSyncOperation(updatedOp)
                updated++
            }
        }

        return updated
    }

    private suspend fun remapRoomPayloads(localProjectId: Long, serverId: Long): Int {
        var updated = 0
        val pendingOps = localDataService.getPendingOperationsForEntityType("room")

        for (op in pendingOps) {
            val payload = runCatching {
                gson.fromJson(
                    String(op.payload, Charsets.UTF_8),
                    PendingRoomCreationPayload::class.java
                )
            }.getOrNull() ?: continue

            if (payload.projectId == localProjectId) {
                val updatedPayload = payload.copy(projectId = serverId)
                val updatedOp = op.copy(
                    payload = gson.toJson(updatedPayload).toByteArray(Charsets.UTF_8)
                )
                localDataService.enqueueSyncOperation(updatedOp)
                updated++
            }
        }

        return updated
    }

    companion object {
        private const val TAG = "IdRemapService"
    }
}
