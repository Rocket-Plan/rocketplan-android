package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.model.ProjectStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed class ProjectLandingUiState {
    object Loading : ProjectLandingUiState()
    data class Ready(val summary: ProjectLandingSummary) : ProjectLandingUiState()
}

sealed class ProjectLandingEvent {
    object ProjectDeleted : ProjectLandingEvent()
    data class DeleteFailed(val error: String) : ProjectLandingEvent()
}

data class ProjectLandingSummary(
    val projectTitle: String,
    val projectCode: String,
    val aliasText: String?,
    val aliasIsActionable: Boolean,
    val status: ProjectStatus?,
    val statusLabel: String?,
    val noteCount: Int,
    val hasLevels: Boolean,
    val hasRooms: Boolean,
    val hasProperty: Boolean
)

class ProjectLandingViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository

    private val _uiState: MutableStateFlow<ProjectLandingUiState> =
        MutableStateFlow(ProjectLandingUiState.Loading)
    val uiState: StateFlow<ProjectLandingUiState> = _uiState

    private val _events = Channel<ProjectLandingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Prioritize this project in the sync queue
        // This ensures the project is synced immediately and jumps to front of queue
        syncQueueManager.prioritizeProject(projectId)

        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeNotes(projectId),
                localDataService.observeLocations(projectId),
                localDataService.observeRooms(projectId)
            ) { projects, notes, locations, rooms ->
                val project = projects.firstOrNull { it.projectId == projectId }
                if (project == null) {
                    ProjectLandingUiState.Loading
                } else {
                    ProjectLandingUiState.Ready(
                        summary = project.toSummary(
                            noteCount = notes.size,
                            hasLevels = locations.isNotEmpty(),
                            hasRooms = rooms.isNotEmpty()
                        )
                    )
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private fun OfflineProjectEntity.toSummary(
        noteCount: Int,
        hasLevels: Boolean,
        hasRooms: Boolean
    ): ProjectLandingSummary {
        val titleCandidates = listOfNotNull(
            addressLine1?.takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() },
            alias?.takeIf { it.isNotBlank() }
        )
        val titleText = titleCandidates.firstOrNull() ?: "Project $projectId"

        val codeText = uid?.takeIf { it.isNotBlank() }
            ?: projectNumber?.takeIf { it.isNotBlank() }
            ?: ""

        val aliasText = alias?.takeIf { it.isNotBlank() }
        val aliasActionable = aliasText == null

        val projectStatus = ProjectStatus.fromApiValue(status)
        val statusLabel = projectStatus?.let { projectStatus ->
            getApplication<Application>().getString(projectStatus.labelRes)
        }

        return ProjectLandingSummary(
            projectTitle = titleText,
            projectCode = codeText,
            aliasText = aliasText,
            aliasIsActionable = aliasActionable,
            status = projectStatus,
            statusLabel = statusLabel,
            noteCount = noteCount,
            hasLevels = hasLevels,
            hasRooms = hasRooms,
            hasProperty = propertyId != null
        )
    }

    fun updateProjectStatus(projectStatus: ProjectStatus) {
        viewModelScope.launch {
            localDataService.updateProjectStatus(projectId, projectStatus)
        }
    }

    fun deleteProject() {
        viewModelScope.launch {
            try {
                offlineSyncRepository.deleteProject(projectId)
                _events.send(ProjectLandingEvent.ProjectDeleted)
            } catch (e: Exception) {
                Log.e("ProjectLandingVM", "Failed to delete project", e)
                _events.send(ProjectLandingEvent.DeleteFailed(e.message ?: "Unknown error"))
            }
        }
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ProjectLandingViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return ProjectLandingViewModel(application, projectId) as T
            }
        }
    }
}
