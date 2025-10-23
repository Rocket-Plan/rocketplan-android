package com.example.rocketplan_android.data.local

/**
 * Represents the current sync state of a locally stored entity.
 */
enum class SyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    CONFLICT,
    FAILED
}

/**
 * Sync priority for queued operations. Lower ordinal means higher priority.
 */
enum class SyncPriority(val level: Int) {
    CRITICAL(0),
    HIGH(1),
    MEDIUM(2),
    LOW(3);

    companion object {
        fun fromLevel(value: Int): SyncPriority = entries.firstOrNull { it.level == value } ?: LOW
    }
}

/**
 * Operation type for queued sync operations to the backend.
 */
enum class SyncOperationType {
    CREATE,
    UPDATE,
    DELETE;

    companion object {
        fun fromName(value: String?): SyncOperationType =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: UPDATE
    }
}
