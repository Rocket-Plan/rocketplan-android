package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
 * Result status for a sync item after processing.
 */
enum class SyncItemStatus {
    /** Item completed successfully */
    SUCCESS,
    /** Item failed during execution */
    FAILED,
    /** Item was cancelled because a dependency failed (cascade cancellation) */
    CANCELLED
}

/**
 * A dependency-aware sync queue that executes items in parallel when their
 * dependencies are satisfied. Matches iOS DispatchGroup + dependsOn pattern.
 *
 * Supports cascade cancellation: when an item fails, all items that depend on it
 * (directly or transitively) are cancelled and not executed. This prevents orphaned
 * data and allows independent operations to complete.
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
    private val itemsById = mutableMapOf<String, SyncItem>()
    private val completed = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val cancelled = mutableSetOf<String>()
    private val mutex = Mutex()

    // Atomic counters for thread-safe reads from callbacks (avoids data race with mutex-protected sets)
    private val _completedCount = AtomicInteger(0)
    private val _failedCount = AtomicInteger(0)
    private val _cancelledCount = AtomicInteger(0)

    /**
     * Callback invoked when an item is cancelled due to a failed dependency.
     * @param itemName The name of the cancelled item
     * @param dependencyName The name of the failed dependency that caused the cancellation
     */
    var onItemCancelled: ((itemName: String, dependencyName: String) -> Unit)? = null

    /**
     * Callback invoked when an item fails during execution.
     * @param itemName The name of the failed item
     * @param error The exception that caused the failure, if any
     */
    var onItemFailed: ((itemName: String, error: Throwable?) -> Unit)? = null

    /**
     * Callback invoked when an item starts executing.
     * @param itemName The name of the item
     */
    var onItemStarted: ((itemName: String) -> Unit)? = null

    /**
     * Callback invoked when an item completes successfully.
     * @param itemName The name of the item
     * @param durationMs Duration of execution in milliseconds
     */
    var onItemCompleted: ((itemName: String, durationMs: Long) -> Unit)? = null

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
        itemsById[item.id] = item
        Log.d(tag, "➕ Queued: $name (deps: ${dependsOn.size})")
        return item.id
    }

    /**
     * Get the final status of an item by its ID.
     * Returns null if the item hasn't been processed yet.
     */
    fun getItemStatus(itemId: String): SyncItemStatus? {
        return when {
            itemId in completed -> SyncItemStatus.SUCCESS
            itemId in failed -> SyncItemStatus.FAILED
            itemId in cancelled -> SyncItemStatus.CANCELLED
            else -> null
        }
    }

    /**
     * Get all item IDs that were cancelled due to cascade cancellation.
     */
    fun getCancelledItems(): Set<String> = cancelled.toSet()

    /**
     * Get all item IDs that failed during execution.
     */
    fun getFailedItems(): Set<String> = failed.toSet()

    /**
     * Get the names of all items that were cancelled due to cascade cancellation.
     */
    fun getCancelledItemNames(): List<String> = cancelled.mapNotNull { itemsById[it]?.name }

    /**
     * Get the names of all items that failed during execution.
     */
    fun getFailedItemNames(): List<String> = failed.mapNotNull { itemsById[it]?.name }

    /**
     * Get the names of all items that have not yet completed (still pending or in progress).
     * Useful for identifying which operations didn't finish before a timeout.
     */
    fun getPendingItemNames(): List<String> = items.map { it.name }

    /**
     * Get the names of all items that completed successfully.
     */
    fun getCompletedItemNames(): List<String> = completed.mapNotNull { itemsById[it]?.name }

    /**
     * Process all items in the queue, respecting dependencies.
     * Items with satisfied dependencies run in parallel.
     *
     * Cascade cancellation: When an item fails, all items that depend on it
     * (directly or transitively) are immediately cancelled and not executed.
     *
     * @return true if all items completed successfully (no failures or cancellations)
     */
    suspend fun processAll(): Boolean = coroutineScope {
        val startTime = System.currentTimeMillis()
        var iterations = 0

        while (true) {
            iterations++
            val ready = mutex.withLock {
                // Find items that are ready to execute:
                // - Not cancelled
                // - All dependencies are completed, failed, or cancelled
                items.filter { item ->
                    item.id !in cancelled &&
                        item.dependsOn.all { dep ->
                            dep in completed || dep in failed || dep in cancelled
                        }
                }
            }

            if (ready.isEmpty()) {
                // No more items ready - either done or deadlocked
                break
            }

            // Check for items that should be cancelled (have a failed dependency)
            // Must hold mutex while reading failed/cancelled to avoid race with concurrent completions
            val (toCancel, toExecute) = mutex.withLock {
                ready.partition { item ->
                    item.dependsOn.any { dep -> dep in failed || dep in cancelled }
                }
            }

            // Cancel items with failed dependencies
            if (toCancel.isNotEmpty()) {
                mutex.withLock {
                    for (item in toCancel) {
                        cancelled.add(item.id)
                        _cancelledCount.incrementAndGet()
                        items.removeAll { it.id == item.id }

                        // Find the failed dependency for logging
                        val failedDep = item.dependsOn.firstOrNull { dep ->
                            dep in failed || dep in cancelled
                        }
                        val failedDepName = failedDep?.let { itemsById[it]?.name ?: it } ?: "unknown"

                        Log.w(tag, "⛔ ${item.name} cancelled (dependency '$failedDepName' failed)")
                        onItemCancelled?.invoke(item.name, failedDepName)
                    }
                }
            }

            if (toExecute.isEmpty()) {
                continue
            }

            Log.d(tag, "🚀 Iteration $iterations: executing ${toExecute.size} items in parallel: ${toExecute.map { it.name }}")

            // Execute ready items in parallel
            val results = toExecute.map { item ->
                async {
                    onItemStarted?.invoke(item.name)
                    val itemStart = System.currentTimeMillis()
                    var error: Throwable? = null
                    val success = try {
                        item.execute()
                    } catch (e: Exception) {
                        Log.e(tag, "❌ ${item.name} failed with exception", e)
                        error = e
                        false
                    }
                    val itemDuration = System.currentTimeMillis() - itemStart

                    mutex.withLock {
                        if (success) {
                            completed.add(item.id)
                            _completedCount.incrementAndGet()
                            Log.d(tag, "✅ ${item.name} completed in ${itemDuration}ms")
                            onItemCompleted?.invoke(item.name, itemDuration)
                        } else {
                            failed.add(item.id)
                            _failedCount.incrementAndGet()
                            Log.w(tag, "⚠️ ${item.name} failed in ${itemDuration}ms")
                            onItemFailed?.invoke(item.name, error)

                            // Cascade cancel all dependents
                            cascadeCancelDependents(item.id)
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
                val missingDeps = item.dependsOn.filter { it !in completed && it !in failed && it !in cancelled }
                Log.w(tag, "   - ${item.name} waiting on: $missingDeps")
            }
        }

        Log.d(tag, "🏁 Queue completed in ${duration}ms: ${completed.size} succeeded, ${failed.size} failed, ${cancelled.size} cancelled, $remaining skipped")
        failed.isEmpty() && cancelled.isEmpty() && remaining == 0
    }

    /**
     * Recursively cancel all items that depend on the failed item.
     * Must be called while holding the mutex.
     */
    private fun cascadeCancelDependents(failedItemId: String) {
        val failedItemName = itemsById[failedItemId]?.name ?: failedItemId

        // Find all items that depend on the failed item (directly or transitively)
        val toCancel = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(failedItemId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            // Find items that depend on current
            for (item in items) {
                if (item.id !in cancelled && item.id !in toCancel && current in item.dependsOn) {
                    toCancel.add(item.id)
                    queue.add(item.id)
                }
            }
        }

        // Cancel all identified items
        for (itemId in toCancel) {
            val item = itemsById[itemId]
            if (item != null && itemId !in cancelled) {
                cancelled.add(itemId)
                _cancelledCount.incrementAndGet()
                Log.w(tag, "⛔ ${item.name} cascade-cancelled (root: '$failedItemName')")
                onItemCancelled?.invoke(item.name, failedItemName)
            }
        }

        // Remove cancelled items from the pending list
        items.removeAll { it.id in toCancel }
    }

    /**
     * Clear the queue and reset state.
     */
    fun reset() {
        items.clear()
        itemsById.clear()
        completed.clear()
        failed.clear()
        cancelled.clear()
        _completedCount.set(0)
        _failedCount.set(0)
        _cancelledCount.set(0)
    }

    /**
     * Get the count of completed items (thread-safe).
     */
    val completedCount: Int get() = _completedCount.get()

    /**
     * Get the count of failed items (thread-safe).
     */
    val failedCount: Int get() = _failedCount.get()

    /**
     * Get the count of cancelled items (thread-safe).
     */
    val cancelledCount: Int get() = _cancelledCount.get()

    /**
     * Get the total count of processed items (completed + failed + cancelled).
     */
    val processedCount: Int get() = completedCount + failedCount + cancelledCount

    /**
     * Get the total number of items that have been added to the queue.
     * This includes completed, failed, cancelled, and pending items.
     */
    val totalItemCount: Int get() = itemsById.size
}
