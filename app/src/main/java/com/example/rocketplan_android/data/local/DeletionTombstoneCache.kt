package com.example.rocketplan_android.data.local

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for recently deleted entities.
 * Provides an extra layer of protection against resurrection of deleted items
 * during the window between local deletion and server sync completion.
 *
 * This is "Layer 3" of the delete race condition fix, complementing:
 * - Layer 1: toEntity() mappers preserving isDeleted state
 * - Layer 2: hasPendingDelete() check in sync queue
 *
 * Tombstones expire after [TOMBSTONE_LIFETIME_MS] to prevent memory growth.
 */
object DeletionTombstoneCache {
    private const val TAG = "TombstoneCache"

    /**
     * How long to keep tombstones in memory (60 seconds).
     * This should be longer than a typical sync cycle but short enough
     * to not cause memory issues.
     */
    private const val TOMBSTONE_LIFETIME_MS = 60_000L

    /**
     * Map of entityType -> (serverId -> timestamp).
     * Uses ConcurrentHashMap for thread safety.
     */
    private val tombstones = ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>>()

    /**
     * Records that an entity was deleted locally.
     * Call this when a user deletes an item before queuing the sync operation.
     *
     * @param entityType The type of entity (e.g., "room", "photo", "note")
     * @param serverId The server ID of the deleted entity
     */
    fun recordDeletion(entityType: String, serverId: Long) {
        if (serverId <= 0) return // Only track server-synced entities
        tombstones.getOrPut(entityType) { ConcurrentHashMap() }[serverId] = System.currentTimeMillis()
        Log.d(TAG, "Recorded deletion: $entityType/$serverId")
    }

    /**
     * Checks if an entity was recently deleted locally.
     * Use this before saving API data to prevent resurrecting deleted items.
     *
     * @param entityType The type of entity (e.g., "room", "photo", "note")
     * @param serverId The server ID to check
     * @return true if the entity was deleted within the last [TOMBSTONE_LIFETIME_MS]
     */
    fun isRecentlyDeleted(entityType: String, serverId: Long): Boolean {
        if (serverId <= 0) return false

        val typeMap = tombstones[entityType] ?: return false
        val timestamp = typeMap[serverId] ?: return false

        val isRecent = System.currentTimeMillis() - timestamp < TOMBSTONE_LIFETIME_MS
        if (!isRecent) {
            // Tombstone expired, remove it
            typeMap.remove(serverId)
            Log.d(TAG, "Tombstone expired: $entityType/$serverId")
        }
        return isRecent
    }

    /**
     * Removes a tombstone after successful server deletion.
     * Call this when the DELETE operation completes on the server.
     *
     * @param entityType The type of entity
     * @param serverId The server ID of the deleted entity
     */
    fun clearTombstone(entityType: String, serverId: Long) {
        tombstones[entityType]?.remove(serverId)
        Log.d(TAG, "Cleared tombstone: $entityType/$serverId")
    }

    /**
     * Clears all tombstones for an entity type.
     * Useful for testing or when syncing deleted records from server.
     */
    fun clearAllForType(entityType: String) {
        tombstones.remove(entityType)
        Log.d(TAG, "Cleared all tombstones for: $entityType")
    }

    /**
     * Clears all tombstones.
     * Call this on logout to free memory.
     */
    fun clearAll() {
        tombstones.clear()
        Log.d(TAG, "Cleared all tombstones")
    }

    /**
     * Prunes expired tombstones to prevent memory growth.
     * Call this periodically (e.g., during sync cycles).
     */
    fun pruneExpired() {
        val now = System.currentTimeMillis()
        var pruned = 0
        tombstones.forEach { (entityType, typeMap) ->
            val iterator = typeMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value >= TOMBSTONE_LIFETIME_MS) {
                    iterator.remove()
                    pruned++
                }
            }
            // Remove empty type maps
            if (typeMap.isEmpty()) {
                tombstones.remove(entityType)
            }
        }
        if (pruned > 0) {
            Log.d(TAG, "Pruned $pruned expired tombstones")
        }
    }
}
