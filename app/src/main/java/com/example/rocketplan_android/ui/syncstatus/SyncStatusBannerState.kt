package com.example.rocketplan_android.ui.syncstatus

/**
 * Represents the state of the sync status banner.
 */
sealed class SyncStatusBannerState {
    /** Banner is hidden - online with no pending operations */
    data object Hidden : SyncStatusBannerState()

    /** Offline state - show red/gray banner */
    data object Offline : SyncStatusBannerState()

    /** Online and syncing pending operations - show green banner with item names */
    data class Syncing(val items: List<SyncProgressItem>) : SyncStatusBannerState()

    /** Online and refreshing from server - show green banner with current job description */
    data class Refreshing(val description: String) : SyncStatusBannerState()
}

/**
 * Represents an item being synced, with human-readable display information.
 */
data class SyncProgressItem(
    val entityType: String,      // "project", "room", "photo", etc.
    val displayName: String,     // Human-readable: "Kitchen", "Master Bedroom"
    val projectName: String?,    // Parent project name for context
    val count: Int = 1           // For aggregation: "3 photos"
)
