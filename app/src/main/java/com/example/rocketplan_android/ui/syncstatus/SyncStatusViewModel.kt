package com.example.rocketplan_android.ui.syncstatus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SyncStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService

    private val _uiState = MutableStateFlow<SyncStatusUiState>(SyncStatusUiState.Loading)
    val uiState: StateFlow<SyncStatusUiState> = _uiState

    init {
        viewModelScope.launch {
            localDataService.observeProjects().collect { projects ->
                if (projects.isEmpty()) {
                    _uiState.value = SyncStatusUiState.Empty
                } else {
                    // Load stats for all projects in a single coroutine
                    loadAllProjectStats(projects)
                }
            }
        }
    }

    private suspend fun loadAllProjectStats(projects: List<OfflineProjectEntity>) {
        // Combine observables for ALL projects into single flows
        val allRoomsFlows = projects.map { localDataService.observeRooms(it.projectId) }
        val allPhotosFlows = projects.map { localDataService.observePhotosForProject(it.projectId) }
        val allAlbumsFlows = projects.map { localDataService.observeAlbumsForProject(it.projectId) }

        combine(
            combine(allRoomsFlows) { it.toList() },
            combine(allPhotosFlows) { it.toList() },
            combine(allAlbumsFlows) { it.toList() }
        ) { roomsByProject, photosByProject, albumsByProject ->
            projects.mapIndexed { index, project ->
                buildProjectStats(
                    project,
                    roomsByProject[index],
                    photosByProject[index],
                    albumsByProject[index]
                )
            }
        }.collect { projectStats ->
            _uiState.value = SyncStatusUiState.Content(projectStats.sortedBy { it.projectTitle })
        }
    }

    private fun buildProjectStats(
        project: OfflineProjectEntity,
        rooms: List<OfflineRoomEntity>,
        photos: List<OfflinePhotoEntity>,
        albums: List<OfflineAlbumEntity>
    ): ProjectSyncStatus {
        return ProjectSyncStatus(
            projectId = project.projectId,
            projectTitle = project.title,
            roomCount = rooms.size,
            photoCount = photos.size,
            albumCount = albums.size
        )
    }
}

sealed class SyncStatusUiState {
    object Loading : SyncStatusUiState()
    object Empty : SyncStatusUiState()
    data class Content(val projects: List<ProjectSyncStatus>) : SyncStatusUiState()
}

data class ProjectSyncStatus(
    val projectId: Long,
    val projectTitle: String,
    val roomCount: Int,
    val photoCount: Int,
    val albumCount: Int
)
