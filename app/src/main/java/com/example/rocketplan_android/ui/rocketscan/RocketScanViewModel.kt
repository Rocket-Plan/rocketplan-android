package com.example.rocketplan_android.ui.rocketscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
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
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository

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

        requestProjectSync(projectId)

        roomsJob = viewModelScope.launch {
            combine(
                localDataService.observeRooms(projectId),
                localDataService.observePhotosForProject(projectId)
            ) { rooms, photos ->
                buildRoomItems(rooms, photos)
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
        currentProjectId?.let { loadProject(it) }
    }

    private fun requestProjectSync(projectId: Long) {
        if (syncedProjects.add(projectId)) {
            viewModelScope.launch {
                runCatching { offlineSyncRepository.syncProjectGraph(projectId) }
            }
        }
    }

    private fun buildRoomItems(
        rooms: List<OfflineRoomEntity>,
        photos: List<OfflinePhotoEntity>
    ): List<RoomListItem> {
        if (rooms.isEmpty()) {
            return emptyList()
        }

        val photosByRoomId = photos.groupBy { it.roomId }
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
                    // Use stable local roomId to query photos, serverId for navigation
                    val roomPhotos = photosByRoomId[room.roomId].orEmpty()
                    val navigationId = room.serverId ?: room.roomId
                    RoomListItem.Room(
                        RoomCard(
                            roomId = navigationId,
                            title = room.title,
                            level = level,
                            photoCount = roomPhotos.size,
                            thumbnailUrl = roomPhotos.firstNotNullOfOrNull { photo ->
                                photo.thumbnailUrl?.takeIf { it.isNotBlank() }
                                    ?: photo.remoteUrl?.takeIf { it.isNotBlank() }
                            }
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

private val syncedProjects = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
