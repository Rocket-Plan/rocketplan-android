package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of a fresh timestamp fetch operation.
 */
data class FreshTimestampResult(
    val entityType: String,
    val serverId: Long,
    val updatedAt: String?,
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * Service for fetching fresh timestamps from the server for conflict resolution.
 *
 * When resolving a conflict by keeping the local version, we need to update
 * the payload with a fresh server timestamp to avoid immediate re-conflict
 * on the next sync attempt.
 *
 * This service provides centralized methods to fetch the current server timestamp
 * for each supported entity type.
 */
class FreshTimestampService(
    private val api: OfflineSyncApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Fetches the current server timestamp for an entity.
     *
     * @param entityType The type of entity (project, property, room, location, equipment, note, etc.)
     * @param serverId The server ID of the entity
     * @return The fresh updatedAt timestamp from the server, or null if fetch fails
     */
    suspend fun fetchFreshTimestamp(entityType: String, serverId: Long): String? =
        withContext(ioDispatcher) {
            try {
                val timestamp = when (entityType) {
                    "project" -> fetchProjectTimestamp(serverId)
                    "property" -> fetchPropertyTimestamp(serverId)
                    "room" -> fetchRoomTimestamp(serverId)
                    "location" -> fetchLocationTimestamp(serverId)
                    "equipment" -> fetchEquipmentTimestamp(serverId)
                    "note" -> fetchNoteTimestamp(serverId)
                    "atmosphericLog" -> fetchAtmosphericLogTimestamp(serverId)
                    "moistureLog" -> fetchMoistureLogTimestamp(serverId)
                    else -> {
                        Log.w(TAG, "Unsupported entity type for fresh timestamp: $entityType")
                        null
                    }
                }
                if (timestamp != null) {
                    Log.d(TAG, "Fetched fresh timestamp for $entityType:$serverId → $timestamp")
                }
                timestamp
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch fresh timestamp for $entityType:$serverId", e)
                null
            }
        }

    /**
     * Fetches fresh timestamps for multiple entities of the same type.
     *
     * @param entityType The type of entities
     * @param serverIds The server IDs to fetch
     * @return Map of serverId to fresh timestamp (only successful fetches included)
     */
    suspend fun fetchFreshTimestamps(
        entityType: String,
        serverIds: List<Long>
    ): Map<Long, String> = withContext(ioDispatcher) {
        val results = mutableMapOf<Long, String>()
        for (serverId in serverIds) {
            fetchFreshTimestamp(entityType, serverId)?.let { timestamp ->
                results[serverId] = timestamp
            }
        }
        results
    }

    /**
     * Fetches fresh timestamp with detailed result including error information.
     */
    suspend fun fetchFreshTimestampWithResult(
        entityType: String,
        serverId: Long
    ): FreshTimestampResult = withContext(ioDispatcher) {
        try {
            val timestamp = fetchFreshTimestamp(entityType, serverId)
            FreshTimestampResult(
                entityType = entityType,
                serverId = serverId,
                updatedAt = timestamp,
                success = timestamp != null,
                errorMessage = if (timestamp == null) "Failed to fetch timestamp" else null
            )
        } catch (e: Exception) {
            FreshTimestampResult(
                entityType = entityType,
                serverId = serverId,
                updatedAt = null,
                success = false,
                errorMessage = e.message
            )
        }
    }

    private suspend fun fetchProjectTimestamp(serverId: Long): String? {
        return try {
            val response = api.getProjectDetail(serverId)
            response.data.updatedAt
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch project timestamp for $serverId", e)
            null
        }
    }

    private suspend fun fetchPropertyTimestamp(serverId: Long): String? {
        return try {
            val response = api.getProperty(serverId)
            response.data.updatedAt
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch property timestamp for $serverId", e)
            null
        }
    }

    private suspend fun fetchRoomTimestamp(serverId: Long): String? {
        return try {
            val response = api.getRoomDetail(serverId)
            response.updatedAt
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch room timestamp for $serverId", e)
            null
        }
    }

    private suspend fun fetchLocationTimestamp(serverId: Long): String? {
        // The API doesn't have a direct location detail endpoint, but we can update
        // the location and get the response. However, this is risky as it might cause
        // changes. For now, we'll return null and log a warning - the caller should
        // handle this gracefully by using a current timestamp.
        Log.w(TAG, "Location timestamp fetch not available via API (no direct endpoint)")
        return null
    }

    private suspend fun fetchEquipmentTimestamp(serverId: Long): String? {
        // Equipment doesn't have a direct GET by ID endpoint, only list endpoints.
        // Similar to location, return null and let caller handle gracefully.
        Log.w(TAG, "Equipment timestamp fetch not available via API (no direct endpoint)")
        return null
    }

    private suspend fun fetchNoteTimestamp(serverId: Long): String? {
        // Notes don't have a direct GET by ID endpoint, only list endpoints.
        Log.w(TAG, "Note timestamp fetch not available via API (no direct endpoint)")
        return null
    }

    private suspend fun fetchAtmosphericLogTimestamp(serverId: Long): String? {
        // Atmospheric logs don't have a direct GET by ID endpoint.
        Log.w(TAG, "AtmosphericLog timestamp fetch not available via API (no direct endpoint)")
        return null
    }

    private suspend fun fetchMoistureLogTimestamp(serverId: Long): String? {
        // Moisture logs don't have a direct GET by ID endpoint.
        Log.w(TAG, "MoistureLog timestamp fetch not available via API (no direct endpoint)")
        return null
    }

    companion object {
        private const val TAG = "FreshTimestampService"
    }
}
