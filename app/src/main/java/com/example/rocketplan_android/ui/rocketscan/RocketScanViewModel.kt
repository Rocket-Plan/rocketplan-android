package com.example.rocketplan_android.ui.rocketscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RocketScanViewModel(application: Application) : AndroidViewModel(application) {

    private val localDataService = (application as RocketPlanApplication).localDataService

    private val _uiState = MutableStateFlow<RocketScanUiState>(RocketScanUiState.Loading)
    val uiState: StateFlow<RocketScanUiState> = _uiState

    private var currentProjectId: Long? = null
    private var photoJob: Job? = null

    fun loadProject(projectId: Long) {
        if (projectId <= 0L) {
            _uiState.value = RocketScanUiState.Error("Invalid project reference.")
            return
        }

        if (currentProjectId == projectId) {
            return
        }

        currentProjectId = projectId
        photoJob?.cancel()
        _uiState.value = RocketScanUiState.Loading

        photoJob = viewModelScope.launch {
            localDataService.observePhotosForProject(projectId).collect { photos ->
                if (photos.isEmpty()) {
                    _uiState.value = RocketScanUiState.Empty
                } else {
                    _uiState.value = RocketScanUiState.Content(photos.map { it.toUiModel() })
                }
            }
        }
    }

    fun retry() {
        currentProjectId?.let { loadProject(it) }
    }

    private fun OfflinePhotoEntity.toUiModel(): RocketScanPhotoUiModel {
        val displaySource = when {
            !cachedThumbnailPath.isNullOrBlank() -> cachedThumbnailPath
            !cachedOriginalPath.isNullOrBlank() -> cachedOriginalPath
            localPath.isNotBlank() -> localPath
            !thumbnailUrl.isNullOrBlank() -> thumbnailUrl
            !remoteUrl.isNullOrBlank() -> remoteUrl
            else -> null
        }

        return RocketScanPhotoUiModel(
            id = photoId,
            fileName = fileName,
            displaySource = displaySource
        )
    }
}

sealed class RocketScanUiState {
    object Loading : RocketScanUiState()
    object Empty : RocketScanUiState()
    data class Content(val photos: List<RocketScanPhotoUiModel>) : RocketScanUiState()
    data class Error(val message: String) : RocketScanUiState()
}

data class RocketScanPhotoUiModel(
    val id: Long,
    val fileName: String,
    val displaySource: String?
)
