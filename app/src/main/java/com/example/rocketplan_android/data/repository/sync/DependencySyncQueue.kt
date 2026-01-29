package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Represents a sync operation with dependencies.
 * Modeled after iOS OfflineSyncService's request-based dependency system.
 */
data class SyncItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dependsOn: List<String> = emptyList(),
    val execute: suspend () -> Boolean
) {
    override fun toString(): String = "SyncItem($name, deps=${dependsOn.size})"
}

/**
 * A dependency-aware sync queue that executes items in parallel when their
 * dependencies are satisfied. Matches iOS DispatchGroup + dependsOn pattern.
 *
 * Usage:
 * ```
 * val queue = DependencySyncQueue()
 * val propertyId = queue.addItem("property") { syncProperty() }
 * val levelsId = queue.addItem("levels", dependsOn = listOf(propertyId)) { syncLevels() }
 * val locationsId = queue.addItem("locations", dependsOn = listOf(propertyId)) { syncLocations() }
 * val roomsId = queue.addItem("rooms", dependsOn = listOf(levelsId, locationsId)) { syncRooms() }
 * queue.processAll()
 * ```
 */
class DependencySyncQueue(
    private val tag: String = "SyncQueue"
) {
    private val items = mutableListOf<SyncItem>()
    private val completed = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val mutex = Mutex()

    /**
     * Add a sync item to the queue.
     * @param name Descriptive name for logging
     * @param dependsOn List of item IDs that must complete before this item runs
     * @param execute The suspend function to execute
     * @return The unique ID of this item (use for dependsOn in other items)
     */
    fun addItem(
        name: String,
        dependsOn: List<String> = emptyList(),
        execute: suspend () -> Boolean
    ): String {
        val item = SyncItem(
            name = name,
            dependsOn = dependsOn,
            execute = execute
        )
        items.add(item)
        Log.d(tag, "➕ Queued: $name (deps: ${dependsOn.size})")
        return item.id
    }

    /**
     * Process all items in the queue, respecting dependencies.
     * Items with satisfied dependencies run in parallel.
     * @return true if all items completed successfully
     */
    suspend fun processAll(): Boolean = coroutineScope {
        val startTime = System.currentTimeMillis()
        var iterations = 0

        while (true) {
            iterations++
            val ready = mutex.withLock {
                // Find items with all dependencies satisfied (completed or failed)
                items.filter { item ->
                    item.dependsOn.all { dep -> dep in completed || dep in failed }
                }
            }

            if (ready.isEmpty()) {
                // No more items ready - either done or deadlocked
                break
            }

            Log.d(tag, "🚀 Iteration $iterations: executing ${ready.size} items in parallel: ${ready.map { it.name }}")

            // Execute ready items in parallel
            val results = ready.map { item ->
                async {
                    val itemStart = System.currentTimeMillis()
                    val success = try {
                        item.execute()
                    } catch (e: Exception) {
                        Log.e(tag, "❌ ${item.name} failed with exception", e)
                        false
                    }
                    val itemDuration = System.currentTimeMillis() - itemStart

                    mutex.withLock {
                        if (success) {
                            completed.add(item.id)
                            Log.d(tag, "✅ ${item.name} completed in ${itemDuration}ms")
                        } else {
                            failed.add(item.id)
                            Log.w(tag, "⚠️ ${item.name} failed in ${itemDuration}ms")
                        }
                    }
                    item to success
                }
            }.awaitAll()

            // Remove processed items
            mutex.withLock {
                val processedIds = results.map { it.first.id }.toSet()
                items.removeAll { it.id in processedIds }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val remaining = items.size

        if (remaining > 0) {
            Log.w(tag, "⚠️ Queue finished with $remaining items unprocessed (missing dependencies?)")
            items.forEach { item ->
                val missingDeps = item.dependsOn.filter { it !in completed && it !in failed }
                Log.w(tag, "   - ${item.name} waiting on: $missingDeps")
            }
        }

        Log.d(tag, "🏁 Queue completed in ${duration}ms: ${completed.size} succeeded, ${failed.size} failed, $remaining skipped")
        failed.isEmpty() && remaining == 0
    }

    /**
     * Clear the queue and reset state.
     */
    fun reset() {
        items.clear()
        completed.clear()
        failed.clear()
    }

    /**
     * Get the count of completed items.
     */
    val completedCount: Int get() = completed.size

    /**
     * Get the count of failed items.
     */
    val failedCount: Int get() = failed.size
}
