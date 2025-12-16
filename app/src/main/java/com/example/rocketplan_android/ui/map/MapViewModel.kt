package com.example.rocketplan_android.ui.map

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.model.ProjectWithProperty
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.ui.projects.ProjectListItem
import com.example.rocketplan_android.ui.projects.matchesStatus
import com.example.rocketplan_android.ui.projects.toListItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val authRepository = rocketPlanApp.authRepository
    private val remoteLogger = rocketPlanApp.remoteLogger

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Loading)
    val uiState: StateFlow<MapUiState> = _uiState

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    init {
        viewModelScope.launch { syncQueueManager.ensureInitialSync() }

        viewModelScope.launch {
            combine(
                localDataService.observeProjectsWithProperty(),
                authRepository.observeCompanyId(),
                syncQueueManager.assignedProjects
            ) { projects, companyId, assigned ->
                Triple(projects, companyId, assigned)
            }.collect { (projectsWithProperty, companyId, assignedIds) ->
                val filteredProjects = companyId?.let { id ->
                    projectsWithProperty.filter { it.project.companyId == id }
                } ?: projectsWithProperty

                val mappedProjects = filteredProjects.map { it.toListItemWithCoords() }
                val myProjects = mappedProjects.filter { assignedIds.contains(it.projectId) }
                val wipProjects = mappedProjects.filter { it.matchesStatus(ProjectStatus.WIP) }
                val markers = wipProjects.mapNotNull { it.toMarker() }
                val missingCoords = mappedProjects.count { it.latitude == null || it.longitude == null }
                val missingSample = mappedProjects
                    .filter { it.latitude == null || it.longitude == null }
                    .take(3)
                    .joinToString { "[${it.projectId}] ${it.title}" }

                _uiState.value = MapUiState.Ready(
                    myProjects = myProjects,
                    wipProjects = wipProjects,
                    markers = markers
                )
                _isRefreshing.value = false

                Log.d(
                    TAG,
                    "Map data: db=${projectsWithProperty.size}, filtered=${filteredProjects.size}, " +
                        "assigned=${assignedIds.size}, my=${myProjects.size}, wip=${wipProjects.size}, " +
                        "markers=${markers.size}, missingCoords=$missingCoords sample=$missingSample"
                )
                remoteLogger.log(
                    level = LogLevel.DEBUG,
                    tag = TAG,
                    message = "Map updated: my=${myProjects.size}, wip=${wipProjects.size}, markers=${markers.size}"
                )
            }
        }

        viewModelScope.launch {
            syncQueueManager.errors.collect { message ->
                _isRefreshing.value = false
                if (_uiState.value is MapUiState.Loading) {
                    _uiState.value = MapUiState.Error(message)
                }
            }
        }
    }

    fun refreshProjects() {
        _isRefreshing.value = true
        syncQueueManager.refreshProjects()
    }

    private fun ProjectWithProperty.toListItemWithCoords(): ProjectListItem {
        val base = project.toListItem()
        return base.copy(
            latitude = property?.latitude,
            longitude = property?.longitude
        )
    }

    private fun ProjectListItem.toMarker(): MapMarker? {
        val lat = latitude ?: return null
        val lng = longitude ?: return null
        return MapMarker(
            projectId = projectId,
            title = title,
            latitude = lat,
            longitude = lng,
            projectCode = projectCode
        )
    }

    companion object {
        private const val TAG = "MapViewModel"
    }
}

sealed class MapUiState {
    object Loading : MapUiState()
    data class Ready(
        val myProjects: List<ProjectListItem>,
        val wipProjects: List<ProjectListItem>,
        val markers: List<MapMarker>
    ) : MapUiState()

    data class Error(val message: String) : MapUiState()
}

data class MapMarker(
    val projectId: Long,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val projectCode: String
)
