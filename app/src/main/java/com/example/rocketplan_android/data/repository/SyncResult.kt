package com.example.rocketplan_android.data.repository

/**
 * Represents a distinct segment of the sync process.
 */
enum class SyncSegment {
    PROJECT_ESSENTIALS,  // Project + Property + Levels + Rooms + Albums + Users
    ROOM_PHOTOS,         // Photos for a single room
    ALL_ROOM_PHOTOS,     // Photos for all rooms (bulk)
    PROJECT_LEVEL_PHOTOS, // Floor/location/unit photos
    PROJECT_METADATA     // Notes, equipment, damages, work scopes, logs
}

enum class IncompleteReason {
    MISSING_PROPERTY,
    NO_COMPANY_CONTEXT
}

/**
 * Result of a sync operation.
 *
 * Success, failure, and incomplete states are explicit so callers can handle partial
 * syncs (e.g., missing property) without assuming full success.
 */
sealed class SyncResult {
    abstract val segment: SyncSegment
    abstract val itemsSynced: Int
    abstract val durationMs: Long
    open val error: Throwable? = null

    val success: Boolean get() = this is Success
    val incomplete: Boolean get() = this is Incomplete

    data class Success(
        override val segment: SyncSegment,
        override val itemsSynced: Int = 0,
        override val durationMs: Long = 0
    ) : SyncResult()

    data class Failure(
        override val segment: SyncSegment,
        val cause: Throwable,
        override val durationMs: Long = 0,
        override val itemsSynced: Int = 0
    ) : SyncResult() {
        override val error: Throwable = cause
    }

    data class Incomplete(
        override val segment: SyncSegment,
        val reason: IncompleteReason,
        override val durationMs: Long = 0,
        override val itemsSynced: Int = 0,
        val cause: Throwable? = null
    ) : SyncResult() {
        override val error: Throwable? = cause
    }

    companion object {
        fun success(segment: SyncSegment, items: Int, duration: Long) =
            Success(segment, items, duration)

        fun failure(segment: SyncSegment, error: Throwable, duration: Long) =
            Failure(segment, error, duration)

        fun incomplete(
            segment: SyncSegment,
            reason: IncompleteReason,
            duration: Long,
            items: Int = 0,
            error: Throwable? = null
        ) = Incomplete(segment, reason, duration, items, error)
    }
}
