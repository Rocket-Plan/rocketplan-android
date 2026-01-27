package com.example.rocketplan_android.data.sync

import com.example.rocketplan_android.util.UuidUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Metrics for tracking operation outcomes by entity type.
 * Thread-safe for concurrent access during sync processing.
 */
data class OperationTypeMetrics(
    val entityType: String,
    private val _createCount: AtomicInteger = AtomicInteger(0),
    private val _updateCount: AtomicInteger = AtomicInteger(0),
    private val _deleteCount: AtomicInteger = AtomicInteger(0),
    private val _successCount: AtomicInteger = AtomicInteger(0),
    private val _failureCount: AtomicInteger = AtomicInteger(0),
    private val _skipCount: AtomicInteger = AtomicInteger(0),
    private val _conflictCount: AtomicInteger = AtomicInteger(0),
    private val _totalDurationMs: AtomicLong = AtomicLong(0)
) {
    val createCount: Int get() = _createCount.get()
    val updateCount: Int get() = _updateCount.get()
    val deleteCount: Int get() = _deleteCount.get()
    val successCount: Int get() = _successCount.get()
    val failureCount: Int get() = _failureCount.get()
    val skipCount: Int get() = _skipCount.get()
    val conflictCount: Int get() = _conflictCount.get()
    val totalDurationMs: Long get() = _totalDurationMs.get()

    fun incrementCreate() = _createCount.incrementAndGet()
    fun incrementUpdate() = _updateCount.incrementAndGet()
    fun incrementDelete() = _deleteCount.incrementAndGet()
    fun incrementSuccess() = _successCount.incrementAndGet()
    fun incrementFailure() = _failureCount.incrementAndGet()
    fun incrementSkip() = _skipCount.incrementAndGet()
    fun incrementConflict() = _conflictCount.incrementAndGet()
    fun addDuration(ms: Long) = _totalDurationMs.addAndGet(ms)

    val totalOperations: Int get() = createCount + updateCount + deleteCount

    fun toMap(): Map<String, Any> = mapOf(
        "entityType" to entityType,
        "createCount" to createCount,
        "updateCount" to updateCount,
        "deleteCount" to deleteCount,
        "successCount" to successCount,
        "failureCount" to failureCount,
        "skipCount" to skipCount,
        "conflictCount" to conflictCount,
        "totalDurationMs" to totalDurationMs
    )
}

/**
 * Operation outcome types for sync metrics tracking.
 */
enum class SyncOperationOutcome {
    SUCCESS,
    FAILURE,
    SKIP,
    DROP,
    CONFLICT_PENDING
}

/**
 * Tracks metrics for a single sync session.
 * Thread-safe for concurrent operation recording.
 */
class SyncSessionMetrics(
    val sessionId: String = UuidUtils.generateUuidV7().take(8),
    val startedAt: Long = System.currentTimeMillis()
) {
    @Volatile
    var endedAt: Long? = null
        private set

    private val _totalOperations = AtomicInteger(0)
    private val _successCount = AtomicInteger(0)
    private val _failureCount = AtomicInteger(0)
    private val _skipCount = AtomicInteger(0)
    private val _dropCount = AtomicInteger(0)
    private val _conflictCount = AtomicInteger(0)

    val totalOperations: Int get() = _totalOperations.get()
    val successCount: Int get() = _successCount.get()
    val failureCount: Int get() = _failureCount.get()
    val skipCount: Int get() = _skipCount.get()
    val dropCount: Int get() = _dropCount.get()
    val conflictCount: Int get() = _conflictCount.get()

    private val operationsByType = ConcurrentHashMap<String, OperationTypeMetrics>()

    val durationMs: Long
        get() = (endedAt ?: System.currentTimeMillis()) - startedAt

    /**
     * Records the result of a sync operation.
     *
     * @param entityType The type of entity (e.g., "room", "note")
     * @param operationType The operation type (CREATE, UPDATE, DELETE)
     * @param outcome The result of the operation
     * @param durationMs Time taken to process this operation
     */
    fun recordOperation(
        entityType: String,
        operationType: String,
        outcome: SyncOperationOutcome,
        durationMs: Long = 0
    ) {
        _totalOperations.incrementAndGet()

        when (outcome) {
            SyncOperationOutcome.SUCCESS -> _successCount.incrementAndGet()
            SyncOperationOutcome.FAILURE -> _failureCount.incrementAndGet()
            SyncOperationOutcome.SKIP -> _skipCount.incrementAndGet()
            SyncOperationOutcome.DROP -> _dropCount.incrementAndGet()
            SyncOperationOutcome.CONFLICT_PENDING -> _conflictCount.incrementAndGet()
        }

        val typeMetrics = operationsByType.getOrPut(entityType) {
            OperationTypeMetrics(entityType)
        }

        when (operationType.uppercase()) {
            "CREATE" -> typeMetrics.incrementCreate()
            "UPDATE" -> typeMetrics.incrementUpdate()
            "DELETE" -> typeMetrics.incrementDelete()
        }

        when (outcome) {
            SyncOperationOutcome.SUCCESS -> typeMetrics.incrementSuccess()
            SyncOperationOutcome.FAILURE -> typeMetrics.incrementFailure()
            SyncOperationOutcome.SKIP -> typeMetrics.incrementSkip()
            SyncOperationOutcome.DROP -> {} // Don't track drops per type
            SyncOperationOutcome.CONFLICT_PENDING -> typeMetrics.incrementConflict()
        }

        typeMetrics.addDuration(durationMs)
    }

    /**
     * Marks the session as ended.
     */
    fun endSession() {
        endedAt = System.currentTimeMillis()
    }

    /**
     * Gets metrics for all entity types processed in this session.
     */
    fun getOperationsByType(): Map<String, OperationTypeMetrics> = operationsByType.toMap()

    /**
     * Converts session metrics to a map for logging.
     */
    fun toMap(): Map<String, Any> = buildMap {
        put("sessionId", sessionId)
        put("startedAt", startedAt)
        put("durationMs", durationMs)
        put("totalOperations", totalOperations)
        put("successCount", successCount)
        put("failureCount", failureCount)
        put("skipCount", skipCount)
        put("dropCount", dropCount)
        put("conflictCount", conflictCount)
        put("operationsByType", operationsByType.mapValues { it.value.toMap() })
    }

    /**
     * Returns a formatted summary string for logging.
     */
    fun formatSummary(): String = buildString {
        appendLine("📊 Sync Session Summary")
        appendLine("├─ Session ID: $sessionId")
        appendLine("├─ Duration: ${durationMs}ms")
        appendLine("├─ Total: $totalOperations")
        append("├─ Success: $successCount, Failed: $failureCount, Skipped: $skipCount")
        if (dropCount > 0) append(", Dropped: $dropCount")
        if (conflictCount > 0) append(", Conflicts: $conflictCount")
        appendLine()

        if (operationsByType.isNotEmpty()) {
            appendLine("└─ By Type:")
            val types = operationsByType.keys.sorted()
            types.forEachIndexed { index, type ->
                val metrics = operationsByType[type] ?: return@forEachIndexed
                val prefix = if (index == types.lastIndex) "   └─" else "   ├─"
                append("$prefix $type: C=${metrics.createCount} U=${metrics.updateCount} D=${metrics.deleteCount}")
                if (metrics.failureCount > 0 || metrics.conflictCount > 0) {
                    append(" (F=${metrics.failureCount}")
                    if (metrics.conflictCount > 0) append(", Cf=${metrics.conflictCount}")
                    append(")")
                }
                appendLine()
            }
        }
    }
}
