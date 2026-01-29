package com.example.rocketplan_android.ui.syncstatus

import android.util.Log
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.network.SyncNetworkMonitor
import com.example.rocketplan_android.data.sync.SyncQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Manages the sync status banner state by combining network availability,
 * pending sync operations, and active refresh jobs.
 */
class SyncStatusBannerManager(
    private val syncNetworkMonitor: SyncNetworkMonitor,
    private val localDataService: LocalDataService,
    private val syncQueueManager: SyncQueueManager
) {
    companion object {
        private const val TAG = "SyncStatusBannerManager"
        private const val MAX_DISPLAY_ITEMS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _bannerState = MutableStateFlow<SyncStatusBannerState>(SyncStatusBannerState.Hidden)
    val bannerState: StateFlow<SyncStatusBannerState> = _bannerState.asStateFlow()

    init {
        observeState()
    }

    @OptIn(FlowPreview::class)
    private fun observeState() {
        scope.launch(Dispatchers.IO) {
            // Combine network state, pending operations, and current sync progress
            combine(
                syncNetworkMonitor.isOnline,
                localDataService.observeSyncOperations(SyncStatus.PENDING),
                localDataService.observeSyncOperations(SyncStatus.SYNCING),
                syncQueueManager.currentSyncProgress
            ) { isOnline, pendingOps, syncingOps, currentProgress ->
                BannerInputs(isOnline, pendingOps, syncingOps, currentProgress)
            }
                .debounce(300) // Debounce to avoid rapid updates
                .distinctUntilChanged()
                .collect { inputs ->
                    val state = computeBannerState(inputs)
                    Log.d(TAG, "Banner state updated: $state")
                    _bannerState.value = state
                }
        }
    }

    private data class BannerInputs(
        val isOnline: Boolean,
        val pendingOps: List<OfflineSyncQueueEntity>,
        val syncingOps: List<OfflineSyncQueueEntity>,
        val currentSyncProgress: SyncQueueManager.SyncProgress?
    )

    private fun computeBannerState(inputs: BannerInputs): SyncStatusBannerState {
        val (isOnline, pendingOps, syncingOps, currentProgress) = inputs

        // If offline, show offline banner
        if (!isOnline) {
            return SyncStatusBannerState.Offline
        }

        // Combine pending and syncing operations (outgoing changes)
        val allActiveOps = pendingOps + syncingOps

        // If there's current sync progress (incoming sync from server), show it
        if (currentProgress != null) {
            // Use the detailed description from SyncProgress
            val description = currentProgress.phaseDescription
            // If we also have pending operations, mention them too
            return if (allActiveOps.isNotEmpty()) {
                val items = aggregateByTypeCounts(allActiveOps)
                val pendingText = items.joinToString(", ") { it.displayName }
                SyncStatusBannerState.Refreshing("$description, uploading $pendingText")
            } else {
                SyncStatusBannerState.Refreshing(description)
            }
        }

        // If we have pending operations but no active sync job, show syncing state
        if (allActiveOps.isNotEmpty()) {
            val items = aggregateByTypeCounts(allActiveOps)
            return SyncStatusBannerState.Syncing(items)
        }

        // Nothing active, hide banner
        return SyncStatusBannerState.Hidden
    }

    /**
     * Fast aggregation by type counts - no database lookups.
     */
    private fun aggregateByTypeCounts(
        operations: List<OfflineSyncQueueEntity>
    ): List<SyncProgressItem> {
        val items = mutableListOf<SyncProgressItem>()

        // Group operations by entity type and count
        val countsByType = operations.groupingBy { it.entityType }.eachCount()

        // Process each type with human-readable labels
        for ((entityType, count) in countsByType) {
            val displayName = when (entityType) {
                "project" -> if (count == 1) "1 project" else "$count projects"
                "room" -> if (count == 1) "1 room" else "$count rooms"
                "photo" -> if (count == 1) "1 photo" else "$count photos"
                "note" -> if (count == 1) "1 note" else "$count notes"
                "equipment" -> if (count == 1) "1 equipment" else "$count equipment"
                "location" -> if (count == 1) "1 location" else "$count locations"
                "property" -> if (count == 1) "1 property" else "$count properties"
                "atmospheric_log" -> if (count == 1) "1 reading" else "$count readings"
                "moisture_log" -> if (count == 1) "1 reading" else "$count readings"
                "damage" -> if (count == 1) "1 damage" else "$count damages"
                "work_scope" -> if (count == 1) "1 scope item" else "$count scope items"
                else -> if (count == 1) "1 item" else "$count items"
            }

            items.add(
                SyncProgressItem(
                    entityType = entityType,
                    displayName = displayName,
                    projectName = null,
                    count = count
                )
            )
        }

        // Sort by count (largest first) and limit
        return items.sortedByDescending { it.count }.take(MAX_DISPLAY_ITEMS)
    }

    /**
     * Clean up resources when no longer needed.
     */
    fun destroy() {
        scope.cancel()
    }
}
