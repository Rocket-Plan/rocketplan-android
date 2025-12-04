package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val remoteLogger = rocketPlanApp.remoteLogger
    private val authRepository = rocketPlanApp.authRepository

    private val _uiState = MutableStateFlow<ProjectsUiState>(ProjectsUiState.Loading)
    val uiState: StateFlow<ProjectsUiState> = _uiState

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    init {
        Log.d(TAG, "📱 ProjectsViewModel initialized")

        viewModelScope.launch {
            Log.d(TAG, "🔄 Starting initial sync...")
            syncQueueManager.ensureInitialSync()
        }

        viewModelScope.launch {
            syncQueueManager.errors.collect { message ->
                Log.e(TAG, "❌ Sync error: $message")
                _isRefreshing.value = false
                val currentState = _uiState.value
                if (currentState !is ProjectsUiState.Success || (currentState.myProjects.isEmpty() && currentState.projectsByStatus.values.all { it.isEmpty() })) {
                    _uiState.value = ProjectsUiState.Error(message)
                }
            }
        }

        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                authRepository.observeCompanyId(),
                syncQueueManager.assignedProjects
            ) { projects, companyId, assignedIds ->
                val filteredProjects = companyId?.let { id ->
                    projects.filter { it.companyId == id }
                } ?: projects
                Triple(filteredProjects, companyId, assignedIds)
            }.collect { (projects, companyId, assignedIds) ->
                Log.d(TAG, "📊 Received ${projects.size} projects from database for company ${companyId ?: "unknown"} (assigned=${assignedIds.size})")

                val mappedProjects = projects.map { it.toListItem() }
                val myProjects = mappedProjects.filter { assignedIds.contains(it.projectId) }
                val projectsByStatus = ProjectStatus.orderedStatuses.associateWith { status ->
                    mappedProjects.filter { it.matchesStatus(status) }
                }
                val statusCounts = projectsByStatus.entries.joinToString { "${it.key.apiValue}=${it.value.size}" }

                Log.d(TAG, "✅ Projects - My Projects: ${myProjects.size}, byStatus: [$statusCounts]")

                remoteLogger.log(
                    level = LogLevel.DEBUG,
                    tag = TAG,
                    message = "Projects updated: my=${myProjects.size}, byStatus=[$statusCounts]",
                    metadata = mapOf("source" to "room_update")
                )

                _uiState.value = ProjectsUiState.Success(myProjects, projectsByStatus)
                _isRefreshing.value = false
            }
        }
    }

    fun refreshProjects() {
        Log.d(TAG, "🔄 Manual refresh triggered")
        _uiState.value = ProjectsUiState.Loading
        _isRefreshing.value = true
        remoteLogger.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Manual refresh requested by user"
        )
        syncQueueManager.refreshProjects()
    }

    fun prioritizeProject(projectId: Long) {
        Log.d(TAG, "⚡ Prioritizing project: $projectId")
        syncQueueManager.prioritizeProject(projectId)
    }

    companion object {
        private const val TAG = "ProjectsViewModel"
    }
}

data class ProjectListItem(
    val projectId: Long,
    val title: String,
    val projectCode: String,
    val alias: String? = null,
    val status: String,
    val propertyId: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

sealed class ProjectsUiState {
    object Loading : ProjectsUiState()
    data class Success(
        val myProjects: List<ProjectListItem>,
        val projectsByStatus: Map<ProjectStatus, List<ProjectListItem>>
    ) : ProjectsUiState()
    data class Error(val message: String) : ProjectsUiState()
}
