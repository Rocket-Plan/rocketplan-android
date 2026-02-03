package com.example.rocketplan_android.data.sync

import android.util.Log
import com.example.rocketplan_android.data.network.RetryingApiExecutor
import com.example.rocketplan_android.data.repository.sync.DependencySyncQueue
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates a full project sync with dependency-aware parallel execution.
 *
 * Features:
 * - Dependency-aware parallel execution using DependencySyncQueue
 * - Cascade cancellation when parent operations fail
 * - Network retry with increasing delays (2s, 4s, 6s) via RetryingApiExecutor
 * - 503 server overload handling with exponential backoff
 * - 30-second timeout safety net
 * - Comprehensive remote logging
 *
 * This orchestrator coordinates sync operations but delegates actual data fetching
 * and persistence to the provided sync functions (typically from OfflineSyncRepository).
 *
 * Dependency Graph:
 * ```
 *                         essentials (property + levels + locations + rooms)
 *                                          |
 *                    +---------------------+---------------------+
 *                    |                     |                     |
 *                metadata              photos              per-room data
 *          (notes, equipment,      (room + project)     (damages, moisture,
 *           atmos logs, damages)                          work scopes)
 * ```
 */
class ProjectSyncOrchestrator(
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Progress information for UI display.
     */
    data class SyncProgress(
        val currentOperation: String,
        val completedCount: Int,
        val totalCount: Int
    )

    /**
     * Result of a project sync operation.
     */
    sealed class SyncResult {
        data class Success(
            val itemsSynced: Int,
            val durationMs: Long
        ) : SyncResult()

        data class PartialSuccess(
            val itemsSynced: Int,
            val durationMs: Long,
            val failedOperations: List<String>,
            val cancelledOperations: List<String>
        ) : SyncResult()

        data class Failure(
            val error: Throwable?,
            val durationMs: Long,
            val failedOperations: List<String>,
            val cancelledOperations: List<String>
        ) : SyncResult()

        data class Timeout(
            val durationMs: Long,
            val missingOperations: List<String>
        ) : SyncResult()
    }

    /**
     * Sync operations that can be provided to the orchestrator.
     * Each operation is a suspend function that returns the number of items synced,
     * or throws an exception on failure.
     */
    data class SyncOperations(
        /** Sync essentials: property, levels, locations, rooms, albums, users */
        val syncEssentials: suspend () -> Int,
        /** Sync metadata: notes, equipment, damages, atmospheric logs */
        val syncMetadata: suspend () -> Int,
        /** Sync all room photos */
        val syncRoomPhotos: suspend () -> Int,
        /** Sync project-level photos */
        val syncProjectPhotos: suspend () -> Int
    )

    /**
     * Sync a project using the provided sync operations with retry and cascade cancellation.
     *
     * The operations are organized into a dependency graph:
     * - essentials runs first (property → levels/locations → rooms)
     * - metadata, room photos, and project photos run in parallel after essentials
     *
     * Each operation is wrapped with RetryingApiExecutor for automatic retry on failure.
     *
     * @param projectId Local project ID (for logging)
     * @param operations The sync operations to execute (provided by OfflineSyncRepository)
     * @param onProgress Callback for progress updates
     * @return SyncResult indicating success, partial success, failure, or timeout
     */
    suspend fun syncProject(
        projectId: Long,
        operations: SyncOperations,
        onProgress: (SyncProgress) -> Unit = {}
    ): SyncResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val itemCount = AtomicInteger(0)

        // Log sync start
        logSyncStart(projectId)

        // Build operation graph
        val queue = DependencySyncQueue(tag = "ProjectSync[$projectId]")

        // Set up callbacks for progress and logging
        queue.onItemStarted = { name ->
            val progress = SyncProgress(
                currentOperation = name,
                completedCount = queue.completedCount,
                totalCount = queue.totalItemCount
            )
            onProgress(progress)
            logOperationStart(name, projectId)
        }

        queue.onItemCompleted = { name, durationMs ->
            logOperationComplete(name, projectId, durationMs)
        }

        queue.onItemFailed = { name, error ->
            logOperationFailed(name, projectId, error)
        }

        queue.onItemCancelled = { itemName, dependencyName ->
            logCascadeCancel(itemName, dependencyName, projectId)
        }

        // === BUILD OPERATION GRAPH ===

        // 1. Essentials (root) - property, levels, locations, rooms, albums, users
        // This uses the existing syncProjectEssentials which handles all persistence
        val essentialsId = queue.addItem("essentials") {
            syncWithRetry("essentials") {
                val count = operations.syncEssentials()
                itemCount.addAndGet(count)
                true
            }
        }

        // 2. Metadata (depends on essentials) - notes, equipment, damages, logs
        // This uses the existing syncProjectMetadata which handles all persistence
        queue.addItem("metadata", dependsOn = listOf(essentialsId)) {
            syncWithRetry("metadata") {
                val count = operations.syncMetadata()
                itemCount.addAndGet(count)
                true
            }
        }

        // 3. Room photos (depends on essentials)
        // This uses the existing syncAllRoomPhotos which handles all persistence
        queue.addItem("room_photos", dependsOn = listOf(essentialsId)) {
            syncWithRetry("room_photos") {
                val count = operations.syncRoomPhotos()
                itemCount.addAndGet(count)
                true
            }
        }

        // 4. Project-level photos (depends on essentials)
        // This uses the existing syncProjectLevelPhotos which handles all persistence
        queue.addItem("project_photos", dependsOn = listOf(essentialsId)) {
            syncWithRetry("project_photos") {
                val count = operations.syncProjectPhotos()
                itemCount.addAndGet(count)
                true
            }
        }

        // Execute with 30-second timeout
        val result = withTimeoutOrNull(SYNC_TIMEOUT_MS) {
            queue.processAll()
        }

        val duration = System.currentTimeMillis() - startTime

        // Determine result based on queue state
        return@withContext when {
            result == null -> {
                // Timeout occurred - get names of operations that didn't complete
                val missing = queue.getPendingItemNames()
                logSyncTimeout(projectId, duration, missing)
                SyncResult.Timeout(duration, missing)
            }

            result == true -> {
                // Full success - processAll() returns true only when no failures, no cancellations, and no remaining items
                logSyncComplete(projectId, duration, itemCount.get())
                SyncResult.Success(itemCount.get(), duration)
            }

            queue.failedCount == 0 && queue.cancelledCount == 0 -> {
                // Deadlock - items remain due to unsatisfied dependencies (miswired dependency graph)
                val pendingOps = queue.getPendingItemNames()
                logSyncDeadlock(projectId, duration, pendingOps)
                SyncResult.Failure(
                    IllegalStateException("Dependency deadlock: ${pendingOps.size} items have unsatisfied dependencies: ${pendingOps.joinToString()}"),
                    duration,
                    failedOperations = emptyList(),
                    cancelledOperations = emptyList() // Not cascade-cancelled, just stuck - details in error message
                )
            }

            queue.completedCount > 0 -> {
                // Partial success - some operations completed
                val failedOps = queue.getFailedItemNames()
                val cancelledOps = queue.getCancelledItemNames()
                logSyncPartial(projectId, duration, itemCount.get(), failedOps, cancelledOps)
                SyncResult.PartialSuccess(itemCount.get(), duration, failedOps, cancelledOps)
            }

            else -> {
                // Complete failure
                val failedOps = queue.getFailedItemNames()
                val cancelledOps = queue.getCancelledItemNames()
                logSyncFailed(projectId, duration, failedOps, cancelledOps)
                SyncResult.Failure(null, duration, failedOps, cancelledOps)
            }
        }
    }

    /**
     * Execute a sync operation with retry logic.
     * Wraps the operation with RetryingApiExecutor for automatic retry on transient failures.
     * Throws the underlying error on failure so DependencySyncQueue can capture it for logging.
     */
    private suspend fun syncWithRetry(
        name: String,
        block: suspend () -> Boolean
    ): Boolean {
        val result = RetryingApiExecutor.execute(
            name = name,
            remoteLogger = remoteLogger
        ) {
            if (block()) Unit else throw RuntimeException("Sync operation '$name' returned false")
        }

        return when (result) {
            is RetryingApiExecutor.Result.Success -> true
            is RetryingApiExecutor.Result.Failure -> throw result.error
        }
    }

    // === LOGGING METHODS ===

    private fun logSyncStart(projectId: Long) {
        Log.d(TAG, "🚀 [syncProject] Starting sync for project $projectId")
        remoteLogger?.log(
            LogLevel.DEBUG,
            "user_sync_start",
            "Project sync started",
            mapOf("projectId" to projectId.toString())
        )
    }

    private fun logOperationStart(operationName: String, projectId: Long) {
        Log.d(TAG, "▶️ [$operationName] Starting for project $projectId")
    }

    private fun logOperationComplete(operationName: String, projectId: Long, durationMs: Long) {
        Log.d(TAG, "✅ [$operationName] Completed in ${durationMs}ms for project $projectId")
    }

    private fun logOperationFailed(operationName: String, projectId: Long, error: Throwable?) {
        Log.w(TAG, "❌ [$operationName] Failed for project $projectId", error)
    }

    private fun logCascadeCancel(itemName: String, dependencyName: String, projectId: Long) {
        Log.w(TAG, "⛔ [$itemName] Cascade cancelled (dependency '$dependencyName' failed) for project $projectId")
        remoteLogger?.log(
            LogLevel.ERROR,
            "api_queue_cascade_cancel",
            "Operation cascade cancelled",
            mapOf(
                "operation" to itemName,
                "failedDependency" to dependencyName,
                "projectId" to projectId.toString()
            )
        )
    }

    private fun logSyncComplete(projectId: Long, durationMs: Long, itemCount: Int) {
        Log.d(TAG, "✅ [syncProject] Completed in ${durationMs}ms with $itemCount items for project $projectId")
        remoteLogger?.log(
            LogLevel.INFO,
            "user_sync_all_complete",
            "Project sync completed",
            mapOf(
                "projectId" to projectId.toString(),
                "durationMs" to durationMs.toString(),
                "itemCount" to itemCount.toString()
            )
        )
    }

    private fun logSyncPartial(
        projectId: Long,
        durationMs: Long,
        itemCount: Int,
        failedOps: List<String>,
        cancelledOps: List<String>
    ) {
        Log.w(TAG, "⚠️ [syncProject] Partial success in ${durationMs}ms: $itemCount items, ${failedOps.size} failed, ${cancelledOps.size} cancelled")
        remoteLogger?.log(
            LogLevel.WARN,
            "user_sync_partial",
            "Project sync partial success",
            mapOf(
                "projectId" to projectId.toString(),
                "durationMs" to durationMs.toString(),
                "itemCount" to itemCount.toString(),
                "failedCount" to failedOps.size.toString(),
                "cancelledCount" to cancelledOps.size.toString(),
                "failedOps" to failedOps.joinToString(","),
                "cancelledOps" to cancelledOps.joinToString(",")
            )
        )
    }

    private fun logSyncFailed(
        projectId: Long,
        durationMs: Long,
        failedOps: List<String>,
        cancelledOps: List<String>
    ) {
        Log.e(TAG, "❌ [syncProject] Failed in ${durationMs}ms: ${failedOps.size} failed, ${cancelledOps.size} cancelled")
        remoteLogger?.log(
            LogLevel.ERROR,
            "user_sync_failed",
            "Project sync failed",
            mapOf(
                "projectId" to projectId.toString(),
                "durationMs" to durationMs.toString(),
                "failedCount" to failedOps.size.toString(),
                "cancelledCount" to cancelledOps.size.toString(),
                "failedOps" to failedOps.joinToString(","),
                "cancelledOps" to cancelledOps.joinToString(",")
            )
        )
    }

    private fun logSyncTimeout(projectId: Long, durationMs: Long, missingOps: List<String>) {
        Log.e(TAG, "⏰ [syncProject] Timeout after ${durationMs}ms, ${missingOps.size} operations didn't complete")
        remoteLogger?.log(
            LogLevel.ERROR,
            "user_sync_timeout",
            "Project sync timeout",
            mapOf(
                "projectId" to projectId.toString(),
                "durationMs" to durationMs.toString(),
                "missingCount" to missingOps.size.toString(),
                "missingOps" to missingOps.joinToString(",")
            )
        )
    }

    private fun logSyncDeadlock(projectId: Long, durationMs: Long, pendingOps: List<String>) {
        Log.e(TAG, "🔒 [syncProject] Deadlock in ${durationMs}ms: ${pendingOps.size} items have unsatisfied dependencies: $pendingOps")
        remoteLogger?.log(
            LogLevel.ERROR,
            "user_sync_deadlock",
            "Project sync deadlock - miswired dependencies",
            mapOf(
                "projectId" to projectId.toString(),
                "durationMs" to durationMs.toString(),
                "pendingCount" to pendingOps.size.toString(),
                "pendingOps" to pendingOps.joinToString(",")
            )
        )
    }

    companion object {
        private const val TAG = "ProjectSyncOrchestrator"

        /** 30-second timeout for the entire sync operation */
        private const val SYNC_TIMEOUT_MS = 30_000L
    }
}
