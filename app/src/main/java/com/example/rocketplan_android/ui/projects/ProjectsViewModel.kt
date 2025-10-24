package com.example.rocketplan_android.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncRepository = rocketPlanApp.offlineSyncRepository
    private val authRepository = AuthRepository(SecureStorage.getInstance(application))
    private val remoteLogger = rocketPlanApp.remoteLogger

    private val _uiState = MutableStateFlow<ProjectsUiState>(ProjectsUiState.Loading)
    val uiState: StateFlow<ProjectsUiState> = _uiState

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    init {
        refreshProjects()
    }

    private suspend fun loadProjectsInternal() {
        val allProjects = localDataService.getAllProjects()

        val myProjects = allProjects.filter {
            it.status.equals("active", ignoreCase = true) ||
                it.status.equals("in_progress", ignoreCase = true)
        }.map { it.toListItem() }

        val wipProjects = allProjects.filter {
            it.status.equals("wip", ignoreCase = true) ||
                it.status.equals("draft", ignoreCase = true)
        }.map { it.toListItem() }

        _uiState.value = ProjectsUiState.Success(myProjects, wipProjects)
    }

    fun loadProjects() {
        viewModelScope.launch {
            try {
                _uiState.value = ProjectsUiState.Loading
                loadProjectsInternal()
            } catch (e: Exception) {
                remoteLogger.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Failed to load projects: ${e.message}",
                    metadata = mapOf("phase" to "loadProjects")
                )
                _uiState.value = ProjectsUiState.Error(e.message ?: "Failed to load projects")
            }
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            try {
                _isRefreshing.value = true
                _uiState.value = ProjectsUiState.Loading

                // Ensure we have user context
                authRepository.ensureUserContext()
                val userId = authRepository.getStoredUserId()
                val companyId = authRepository.getStoredCompanyId()

                remoteLogger.log(
                    level = LogLevel.DEBUG,
                    tag = TAG,
                    message = "Refreshing projects - userId: $userId, companyId: $companyId",
                    metadata = mapOf("phase" to "refreshProjects")
                )

                if (userId == null && companyId == null) {
                    remoteLogger.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "No user or company ID available for sync",
                        metadata = mapOf("phase" to "refreshProjects")
                    )
                    throw IllegalStateException("Unable to determine user or company for project sync. Please log in again.")
                }

                // Sync projects from server
                withContext(Dispatchers.IO) {
                    var syncErrors = mutableListOf<String>()

                    userId?.let {
                        runCatching {
                            syncRepository.syncUserProjects(it)
                        }.onFailure { error ->
                            syncErrors.add("User projects sync failed: ${error.message}")
                            remoteLogger.log(
                                level = LogLevel.ERROR,
                                tag = TAG,
                                message = "Failed to sync user projects: ${error.message}",
                                metadata = mapOf("userId" to it.toString())
                            )
                        }
                    }

                    companyId?.let {
                        runCatching {
                            syncRepository.syncCompanyProjects(it)
                        }.onFailure { error ->
                            syncErrors.add("Company projects sync failed: ${error.message}")
                            remoteLogger.log(
                                level = LogLevel.ERROR,
                                tag = TAG,
                                message = "Failed to sync company projects: ${error.message}",
                                metadata = mapOf("companyId" to it.toString())
                            )
                        }
                    }

                    // Sync project details for each project
                    val projects = localDataService.getAllProjects()
                    remoteLogger.log(
                        level = LogLevel.DEBUG,
                        tag = TAG,
                        message = "Found ${projects.size} projects in local database",
                        metadata = mapOf("phase" to "refreshProjects")
                    )

                    projects.forEach { project ->
                        runCatching {
                            syncRepository.syncProjectGraph(project.projectId)
                        }.onFailure { error ->
                            remoteLogger.log(
                                level = LogLevel.ERROR,
                                tag = TAG,
                                message = "Failed to sync project graph: ${error.message}",
                                metadata = mapOf("projectId" to project.projectId.toString())
                            )
                        }
                    }

                    // Log sync errors if any occurred
                    if (syncErrors.isNotEmpty()) {
                        remoteLogger.log(
                            level = LogLevel.WARN,
                            tag = TAG,
                            message = "Sync completed with errors: ${syncErrors.joinToString("; ")}",
                            metadata = mapOf("errorCount" to syncErrors.size.toString())
                        )
                    }
                }

                // Load projects from local database
                loadProjectsInternal()
            } catch (e: Exception) {
                remoteLogger.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Failed to refresh projects: ${e.message}",
                    metadata = mapOf("phase" to "refreshProjects", "exception" to e.toString())
                )
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

    companion object {
        private const val TAG = "ProjectsViewModel"
    }
}
