package com.example.rocketplan_android.data.sync

import android.util.Log
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages sync session logging with batch metrics aggregation.
 * Provides comprehensive logging for sync operations including:
 * - Session-level metrics (total ops, success/failure rates)
 * - Operation type breakdowns (create/update/delete counts per entity)
 * - Performance tracking (duration per operation and session)
 *
 * Thread-safe for concurrent access during sync processing.
 */
class SyncQueueLogger(
    private val remoteLogger: RemoteLogger? = null,
    private val tag: String = TAG
) {
    private val currentSession = AtomicReference<SyncSessionMetrics?>(null)

    /**
     * Starts a new sync session and returns the metrics tracker.
     * If a session is already in progress, it will be ended first.
     */
    fun startSession(): SyncSessionMetrics {
        // End any existing session
        currentSession.get()?.let { existing ->
            if (existing.endedAt == null) {
                Log.w(tag, "⚠️ Previous sync session ${existing.sessionId} was not ended properly")
                endSession()
            }
        }

        val session = SyncSessionMetrics()
        currentSession.set(session)
        Log.d(tag, "🚀 Sync session ${session.sessionId} started")
        return session
    }

    /**
     * Records the result of a sync operation.
     *
     * @param entityType The type of entity (e.g., "room", "note", "project")
     * @param operationType The operation type (CREATE, UPDATE, DELETE)
     * @param outcome The result of the operation
     * @param durationMs Time taken to process this operation
     */
    fun recordOperationResult(
        entityType: String,
        operationType: String,
        outcome: SyncOperationOutcome,
        durationMs: Long = 0
    ) {
        val session = currentSession.get()
        if (session == null) {
            Log.w(tag, "⚠️ Recording operation outside of active session: $entityType/$operationType")
            return
        }

        session.recordOperation(entityType, operationType, outcome, durationMs)

        // Log failures immediately for debugging
        if (outcome == SyncOperationOutcome.FAILURE) {
            Log.w(tag, "❌ Sync operation failed: $entityType/$operationType (${durationMs}ms)")
        }
    }

    /**
     * Ends the current sync session and logs the summary.
     * Returns the completed session metrics, or null if no session was active.
     */
    fun endSession(): SyncSessionMetrics? {
        val session = currentSession.getAndSet(null) ?: return null
        session.endSession()

        // Log summary locally
        val summary = session.formatSummary()
        Log.d(tag, summary)

        // Log to remote logger with structured data
        if (session.totalOperations > 0) {
            val level = when {
                session.failureCount > 0 -> LogLevel.WARN
                session.conflictCount > 0 -> LogLevel.INFO
                else -> LogLevel.INFO
            }

            remoteLogger?.log(
                level = level,
                tag = tag,
                message = "Sync session completed",
                metadata = buildMap {
                    put("sessionId", session.sessionId)
                    put("durationMs", session.durationMs.toString())
                    put("totalOps", session.totalOperations.toString())
                    put("success", session.successCount.toString())
                    put("failed", session.failureCount.toString())
                    put("skipped", session.skipCount.toString())
                    put("dropped", session.dropCount.toString())
                    put("conflicts", session.conflictCount.toString())

                    // Add per-type breakdown for sessions with failures or conflicts
                    if (session.failureCount > 0 || session.conflictCount > 0) {
                        session.getOperationsByType().forEach { (type, metrics) ->
                            if (metrics.failureCount > 0 || metrics.conflictCount > 0) {
                                put("${type}_failed", metrics.failureCount.toString())
                                put("${type}_conflicts", metrics.conflictCount.toString())
                            }
                        }
                    }
                }
            )
        }

        return session
    }

    /**
     * Gets the current active session, if any.
     */
    fun getCurrentSession(): SyncSessionMetrics? = currentSession.get()

    /**
     * Checks if a sync session is currently active.
     */
    fun isSessionActive(): Boolean = currentSession.get()?.endedAt == null

    companion object {
        private const val TAG = "SyncQueueLogger"
    }
}
