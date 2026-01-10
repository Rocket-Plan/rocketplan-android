package com.example.rocketplan_android.data.repository.sync.handlers

/**
 * Outcome of processing a pending sync operation.
 */
enum class OperationOutcome {
    /** Operation succeeded; remove from queue */
    SUCCESS,
    /** Operation should be skipped for now; will retry later with backoff */
    SKIP,
    /** Operation failed but should retry immediately */
    RETRY,
    /** Operation should be dropped (e.g., entity no longer exists) */
    DROP
}
