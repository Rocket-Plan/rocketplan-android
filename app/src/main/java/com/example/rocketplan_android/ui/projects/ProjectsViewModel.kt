package com.example.rocketplan_android.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProjectListItem(
    val projectId: Long,
    val title: String,
    val projectNumber: String,
    val subtitle: String? = null,
    val status: String
)

sealed class ProjectsUiState {
    object Loading : ProjectsUiState()
    data class Success(val myProjects: List<ProjectListItem>, val wipProjects: List<ProjectListItem>) : ProjectsUiState()
    data class Error(val message: String) : ProjectsUiState()
}

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val localDataService = (application as RocketPlanApplication).localDataService
    private val syncRepository = (application as RocketPlanApplication).offlineSyncRepository

    private val _uiState = MutableStateFlow<ProjectsUiState>(ProjectsUiState.Loading)
    val uiState: StateFlow<ProjectsUiState> = _uiState

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    init {
        loadProjects()
    }

    fun loadProjects() {
        viewModelScope.launch {
            try {
                _uiState.value = ProjectsUiState.Loading

                // Get all projects from local database
                val allProjects = localDataService.getAllProjects()

                // Split projects by status - WIP vs completed/archived
                val myProjects = allProjects.filter {
                    it.status.equals("active", ignoreCase = true) ||
                    it.status.equals("in_progress", ignoreCase = true)
                }.map { it.toListItem() }

                val wipProjects = allProjects.filter {
                    it.status.equals("wip", ignoreCase = true) ||
                    it.status.equals("draft", ignoreCase = true)
                }.map { it.toListItem() }

                _uiState.value = ProjectsUiState.Success(myProjects, wipProjects)
            } catch (e: Exception) {
                _uiState.value = ProjectsUiState.Error(e.message ?: "Failed to load projects")
            }
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true

                // TODO: Sync projects from server
                // This requires user/company ID which we'll get from auth context
                // syncRepository.syncUserProjects(userId)
                // syncRepository.syncCompanyProjects(companyId)

                // For now, just reload from local database
                loadProjects()
            } catch (e: Exception) {
                _uiState.value = ProjectsUiState.Error(e.message ?: "Failed to refresh projects")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun OfflineProjectEntity.toListItem(): ProjectListItem {
        return ProjectListItem(
            projectId = projectId,
            title = title,
            projectNumber = projectNumber ?: "RP-${serverId ?: projectId}",
            subtitle = propertyType,
            status = status
        )
    }
}
