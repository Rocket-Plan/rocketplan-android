package com.example.rocketplan_android.data.repository

/**
 * Represents a distinct segment of the sync process.
 */
enum class SyncSegment {
    PROJECT_ESSENTIALS,  // Project + Property + Levels + Rooms + Albums + Users
    ROOM_PHOTOS,         // Photos for a single room
    ALL_ROOM_PHOTOS,     // Photos for all rooms (bulk)
    PROJECT_LEVEL_PHOTOS, // Floor/location/unit photos
    PROJECT_METADATA     // Notes, equipment, damages (future)
}

/**
 * Result of a sync operation.
 *
 * @param segment Which part of the sync this represents
 * @param success Whether the sync completed successfully
 * @param itemsSynced Number of items synced (projects, rooms, photos, etc.)
 * @param error The error that occurred, if any
 * @param durationMs How long the sync took in milliseconds
 */
data class SyncResult(
    val segment: SyncSegment,
    val success: Boolean,
    val itemsSynced: Int = 0,
    val error: Throwable? = null,
    val durationMs: Long = 0
) {
    companion object {
        fun success(segment: SyncSegment, items: Int, duration: Long) =
            SyncResult(segment, true, items, null, duration)

        fun failure(segment: SyncSegment, error: Throwable, duration: Long) =
            SyncResult(segment, false, 0, error, duration)
    }
}
