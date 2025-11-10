package com.example.rocketplan_android.ui.rocketscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.model.RoomPhotoSummary
import com.example.rocketplan_android.ui.projects.RoomCard
import com.example.rocketplan_android.ui.projects.RoomListItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class RocketScanViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager

    private val _uiState = MutableStateFlow<RocketScanUiState>(RocketScanUiState.Loading)
    val uiState: StateFlow<RocketScanUiState> = _uiState

    private var currentProjectId: Long? = null
    private var roomsJob: Job? = null

    fun loadProject(projectId: Long) {
        if (projectId <= 0L) {
            _uiState.value = RocketScanUiState.Error("Invalid project reference.")
            return
        }

        if (currentProjectId == projectId) {
            return
        }

        currentProjectId = projectId
        roomsJob?.cancel()
        _uiState.value = RocketScanUiState.Loading

        // Prioritize this project in the sync queue
        // This ensures rooms are fetched first, jumping ahead of other projects
        syncQueueManager.prioritizeProject(projectId)

        roomsJob = viewModelScope.launch {
            combine(
                localDataService.observeRooms(projectId),
                localDataService.observeRoomPhotoSummaries(projectId)
            ) { rooms, summaries ->
                buildRoomItems(rooms, summaries)
            }.collect { items ->
                _uiState.value = if (items.isEmpty()) {
                    RocketScanUiState.Empty
                } else {
                    RocketScanUiState.Content(items)
                }
            }
        }
    }

    fun retry() {
        currentProjectId?.let { projectId ->
            // Re-prioritize the project for retry
            syncQueueManager.prioritizeProject(projectId)
        }
    }

    private fun buildRoomItems(
        rooms: List<OfflineRoomEntity>,
        photoSummaries: List<RoomPhotoSummary>
    ): List<RoomListItem> {
        if (rooms.isEmpty()) {
            return emptyList()
        }

        val summariesByRoomId = photoSummaries.associateBy { it.roomId }
        val sections = rooms
            .groupBy { room ->
                room.level?.takeIf { it.isNotBlank() } ?: "Unassigned"
            }
            .entries
            .sortedBy { it.key.lowercase() }

        val items = mutableListOf<RoomListItem>()
        for ((level, groupedRooms) in sections) {
            val roomItems = groupedRooms
                .sortedBy { it.title }
                .map { room ->
                    // Photos are persisted with server room ID, use that for lookup
                    val photoLookupId = room.serverId ?: room.roomId
                    val summary = summariesByRoomId[photoLookupId]
                    val navigationId = room.serverId ?: room.roomId
                    RoomListItem.Room(
                        RoomCard(
                            roomId = navigationId,
                            title = room.title,
                            level = level,
                            photoCount = summary?.photoCount ?: 0,
                            thumbnailUrl = summary?.latestThumbnailUrl?.takeIf { it.isNotBlank() }
                        )
                    )
                }

            if (roomItems.isNotEmpty()) {
                items += RoomListItem.Header(level)
                items += roomItems
            }
        }

        return items
    }
}

sealed class RocketScanUiState {
    object Loading : RocketScanUiState()
    object Empty : RocketScanUiState()
    data class Content(val items: List<RoomListItem>) : RocketScanUiState()
    data class Error(val message: String) : RocketScanUiState()
}
