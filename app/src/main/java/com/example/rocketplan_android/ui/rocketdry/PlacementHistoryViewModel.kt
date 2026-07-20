package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RP-FR-028 — one row in a unit's placement history (a single deploy → check-out span).
 */
data class PlacementRowUi(
    val placementId: Long,
    val roomName: String?,
    val projectName: String?,
    val dateRange: String,
    val isOpen: Boolean,
    val note: String?,
    /** UTC-midnight millis of date_in/date_out, for pre-filling the correction date pickers. */
    val dateInUtcMillis: Long?,
    val dateOutUtcMillis: Long?
)

/**
 * RP-FR-028 — sealed UI state for the placement-history screen. Mirrors the neighbourhood
 * convention ([SerializedAssetDetailUiState]); folds "no history" into an explicit [Empty]
 * state rather than a Ready(emptyList).
 */
sealed class PlacementHistoryUiState {
    object Loading : PlacementHistoryUiState()
    object Empty : PlacementHistoryUiState()
    data class Ready(val rows: List<PlacementRowUi>) : PlacementHistoryUiState()
}

/**
 * RP-FR-028 — read-only placement-history screen. Reads the asset's placements from Room via a
 * Flow (offline-first), reconciles room/project display names against the local rows, and emits
 * them ordered newest-first (desc by `date_in`, matching backend/iOS ordering). No writes — the
 * table is populated by [com.example.rocketplan_android.data.repository.sync.EquipmentAssetPullService].
 */
class PlacementHistoryViewModel(
    application: Application,
    val assetLocalId: Long
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val localDataService = app.localDataService
    private val offlineSyncRepository = app.offlineSyncRepository

    private val _uiState = MutableStateFlow<PlacementHistoryUiState>(PlacementHistoryUiState.Loading)
    val uiState: StateFlow<PlacementHistoryUiState> = _uiState

    // RP-FR-030: display placement dates in UTC so a date-only correction (stored as UTC midnight)
    // round-trips without shifting a day across timezones (mirrors iOS RP-BUG-345).
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    init {
        observePlacements()
    }

    private fun observePlacements() {
        viewModelScope.launch {
            localDataService.observePlacementsForAsset(assetLocalId)
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.remoteLogger.log(
                        com.example.rocketplan_android.logging.LogLevel.WARN, "equip_ui",
                        "Placement history flow threw",
                        mapOf(
                            "assetId" to assetLocalId.toString(),
                            "error" to (e::class.java.simpleName + ": " + (e.message ?: ""))
                        )
                    )
                    _uiState.value = PlacementHistoryUiState.Empty
                }
                .collect { placements -> _uiState.value = toState(placements) }
        }
    }

    private suspend fun toState(placements: List<OfflineEquipmentPlacementEntity>): PlacementHistoryUiState =
        withContext(Dispatchers.IO) {
            val visible = placements
                .filter { !it.isDeleted }
                // Newest first; null date_in sinks to the bottom.
                .sortedByDescending { it.dateIn?.time ?: Long.MIN_VALUE }
            if (visible.isEmpty()) return@withContext PlacementHistoryUiState.Empty

            // Batch name resolution: resolve each distinct room/project once.
            val roomNames = visible.mapNotNull { it.roomId }.distinct()
                .associateWith { localDataService.getRoom(it)?.title }
            val projectNames = visible.mapNotNull { it.projectId }.distinct()
                .associateWith { localDataService.getProject(it)?.title }

            PlacementHistoryUiState.Ready(
                visible.map { placement ->
                    PlacementRowUi(
                        placementId = placement.placementId,
                        roomName = placement.roomId?.let { roomNames[it] },
                        projectName = placement.projectId?.let { projectNames[it] },
                        dateRange = formatDateRange(placement),
                        isOpen = placement.isOpen,
                        note = placement.note?.takeIf { it.isNotBlank() },
                        dateInUtcMillis = placement.dateIn?.time,
                        dateOutUtcMillis = placement.dateOut?.time
                    )
                }
            )
        }

    /** "2025-06-01 → 2025-06-10", or "2025-06-01 → Currently deployed" when the span is open. */
    private fun formatDateRange(placement: OfflineEquipmentPlacementEntity): String {
        val start = placement.dateIn?.let { dateFormat.format(it) } ?: ""
        val end = when {
            placement.isOpen -> app.getString(R.string.serialized_history_currently_deployed)
            placement.dateOut != null -> dateFormat.format(placement.dateOut)
            else -> ""
        }
        return when {
            start.isNotEmpty() && end.isNotEmpty() -> "$start → $end"
            start.isNotEmpty() -> start
            else -> end
        }
    }

    /**
     * RP-FR-030 — correct a closed placement's dates. [dateInUtcMillis]/[dateOutUtcMillis] are the
     * UTC-midnight selections from the pickers; null leaves that date unchanged. Emits a user
     * message on failure. The list re-renders automatically via the placements Flow.
     */
    fun correctPlacement(placementId: Long, dateInUtcMillis: Long?, dateOutUtcMillis: Long?) {
        viewModelScope.launch {
            val result = runCatching {
                offlineSyncRepository.correctPlacementOffline(
                    placementId,
                    dateInUtcMillis?.let { Date(it) },
                    dateOutUtcMillis?.let { Date(it) }
                )
            }.getOrNull()
            if (result == null) {
                _messages.emit(app.getString(R.string.serialized_history_correct_failed))
            }
        }
    }

    /** RP-FR-031 — delete a closed placement. Emits a user message on failure. */
    fun deletePlacement(placementId: Long) {
        viewModelScope.launch {
            val result = runCatching {
                offlineSyncRepository.deletePlacementOffline(placementId)
            }.getOrNull()
            if (result == null) {
                _messages.emit(app.getString(R.string.serialized_history_delete_failed))
            }
        }
    }

    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    companion object {
        fun provideFactory(application: Application, assetLocalId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PlacementHistoryViewModel::class.java)) {
                        "Unknown ViewModel class"
                    }
                    return PlacementHistoryViewModel(application, assetLocalId) as T
                }
            }
    }
}
