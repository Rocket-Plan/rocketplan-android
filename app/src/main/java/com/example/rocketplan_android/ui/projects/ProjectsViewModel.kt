package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val remoteLogger = rocketPlanApp.remoteLogger

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
                if (currentState !is ProjectsUiState.Success || (currentState.myProjects.isEmpty() && currentState.wipProjects.isEmpty())) {
                    _uiState.value = ProjectsUiState.Error(message)
                }
            }
        }

        viewModelScope.launch {
            localDataService.observeProjects().collect { projects ->
                Log.d(TAG, "📊 Received ${projects.size} projects from database")

                val mappedProjects = projects.map { it.toListItem() }
                val wipProjects = mappedProjects.filter {
                    it.status.equals("wip", ignoreCase = true) ||
                        it.status.equals("draft", ignoreCase = true)
                }

                Log.d(TAG, "✅ Projects - My Projects: ${mappedProjects.size}, WIP: ${wipProjects.size}")

                remoteLogger.log(
                    level = LogLevel.DEBUG,
                    tag = TAG,
                    message = "Projects updated: my=${mappedProjects.size}, wip=${wipProjects.size}",
                    metadata = mapOf("source" to "room_update")
                )

                _uiState.value = ProjectsUiState.Success(mappedProjects, wipProjects)
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

    private fun OfflineProjectEntity.toListItem(): ProjectListItem {
        val displayTitle = listOfNotNull(
            addressLine1?.takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() }
        ).firstOrNull() ?: "Project ${serverId ?: projectId}"
        val displayCode = uid?.takeIf { it.isNotBlank() }
            ?: projectNumber?.takeIf { it.isNotBlank() }
            ?: "RP-${serverId ?: projectId}"
        val displayAlias = alias?.takeIf { it.isNotBlank() }
        return ProjectListItem(
            projectId = projectId,
            title = displayTitle,
            projectCode = displayCode,
            alias = displayAlias,
            status = status
        )
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
    val status: String
)

sealed class ProjectsUiState {
    object Loading : ProjectsUiState()
    data class Success(val myProjects: List<ProjectListItem>, val wipProjects: List<ProjectListItem>) : ProjectsUiState()
    data class Error(val message: String) : ProjectsUiState()
}
