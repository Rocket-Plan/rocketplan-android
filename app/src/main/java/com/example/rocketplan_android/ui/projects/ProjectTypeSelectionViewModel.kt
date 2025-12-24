package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.repository.IncompleteReason
import com.example.rocketplan_android.data.repository.SyncResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ProjectTypeSelectionViewState(
    val isLoading: Boolean = true,
    val projectName: String = "",
    val isSelectionInProgress: Boolean = false,
    val errorMessage: String? = null
)

sealed interface ProjectTypeSelectionNavigation {
    data class NavigateToProjectDetail(val projectId: Long) : ProjectTypeSelectionNavigation
}

class ProjectTypeSelectionViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val syncQueueManager = rocketPlanApp.syncQueueManager

    private val missingProjectMessage = application.getString(R.string.project_type_missing_project)
    private val genericErrorMessage = application.getString(R.string.project_type_error_generic)
    private val syncInProgressMessage = application.getString(R.string.project_type_sync_in_progress)

    private val _uiState = MutableStateFlow(ProjectTypeSelectionViewState())
    val uiState: StateFlow<ProjectTypeSelectionViewState> = _uiState

    private val _navigationEvents = MutableSharedFlow<ProjectTypeSelectionNavigation>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<ProjectTypeSelectionNavigation> = _navigationEvents

    private var latestProject: OfflineProjectEntity? = null
    private var hasAttemptedPropertyRecovery: Boolean = false
    private var hasNavigatedToDetail: Boolean = false
    private var propertyCreationIdempotencyKey: String? = null

    init {
        viewModelScope.launch {
            localDataService.observeProjects().collectLatest { projects ->
                val previousProject = latestProject
                val project = projects.firstOrNull { it.projectId == projectId }
                latestProject = project

                if (project == null) {
                    Log.d(TAG, "[ProjectType] Project $projectId missing locally; showing error state")
                    _uiState.value = ProjectTypeSelectionViewState(
                        isLoading = false,
                        projectName = "",
                        isSelectionInProgress = false,
                        errorMessage = missingProjectMessage
                    )
                } else {
                    val syncInFlight = syncQueueManager.isProjectSyncInFlight(projectId)
                    Log.d(
                        TAG,
                        "[ProjectType] Rendering selection page for projectId=$projectId propertyId=${project.propertyId} serverId=${project.serverId} syncInFlight=$syncInFlight"
                    )
                    _uiState.value = ProjectTypeSelectionViewState(
                        isLoading = false,
                        projectName = project.displayName(),
                        isSelectionInProgress = false,
                        errorMessage = null
                    )
                    maybeRecoverMissingProperty(previousProject, project)
                }
            }
        }
    }

    fun selectPropertyType(propertyType: PropertyType) {
        val project = latestProject ?: run {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isSelectionInProgress = false,
                    errorMessage = missingProjectMessage
                )
            }
            return
        }

        if (_uiState.value.isSelectionInProgress) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSelectionInProgress = true, errorMessage = null) }

            if (project.propertyId == null && syncQueueManager.isProjectSyncInFlight(projectId)) {
                _uiState.update {
                    it.copy(
                        isSelectionInProgress = false,
                        errorMessage = syncInProgressMessage
                    )
                }
                return@launch
            }

            val request = PropertyMutationRequest(propertyTypeId = propertyType.propertyTypeId)
            val result = if (project.propertyId == null) {
                val idempotencyKey = ensurePropertyCreationIdempotencyKey()
                offlineSyncRepository.createProjectProperty(
                    projectId = projectId,
                    request = request.copy(idempotencyKey = idempotencyKey),
                    propertyTypeValue = propertyType.apiValue,
                    idempotencyKey = idempotencyKey
                )
            } else {
                offlineSyncRepository.updateProjectProperty(
                    projectId = projectId,
                    propertyId = project.propertyId!!,
                    request = request,
                    propertyTypeValue = propertyType.apiValue
                )
            }

            result.fold(
                onSuccess = {
                    val locationName = project.displayName()
                    val seedResult = offlineSyncRepository.createDefaultLocationAndRoom(
                        projectId = projectId,
                        propertyTypeValue = propertyType.apiValue,
                        locationName = locationName,
                        seedDefaultRoom = false
                    )
                    if (seedResult.isFailure) {
                        val message = seedResult.exceptionOrNull()?.message ?: genericErrorMessage
                        _uiState.update {
                            it.copy(
                                isSelectionInProgress = false,
                                errorMessage = message
                            )
                        }
                        return@launch
                    }

                    propertyCreationIdempotencyKey = null
                    _uiState.update { it.copy(isSelectionInProgress = false) }
                    syncQueueManager.prioritizeProject(projectId)
                    hasNavigatedToDetail = true
                    _navigationEvents.emit(
                        ProjectTypeSelectionNavigation.NavigateToProjectDetail(projectId)
                    )
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSelectionInProgress = false,
                            errorMessage = error.message ?: genericErrorMessage
                        )
                    }
                }
            )
        }
    }

    private fun ensurePropertyCreationIdempotencyKey(): String {
        val current = propertyCreationIdempotencyKey
        if (current != null) return current
        val generated = UUID.randomUUID().toString()
        propertyCreationIdempotencyKey = generated
        return generated
    }

    private fun maybeRecoverMissingProperty(
        previousProject: OfflineProjectEntity?,
        project: OfflineProjectEntity
    ) {
        val propertyJustAppeared =
            project.propertyId != null && previousProject != null && previousProject.propertyId == null

        // If property just became available, navigate directly to detail to avoid looping
        if (propertyJustAppeared && !hasNavigatedToDetail) {
            hasNavigatedToDetail = true
            viewModelScope.launch {
                _navigationEvents.emit(
                    ProjectTypeSelectionNavigation.NavigateToProjectDetail(projectId)
                )
            }
            return
        }

        // If property is missing but the project exists on server, try to pull it down once
        if (project.propertyId == null && project.serverId != null && !hasAttemptedPropertyRecovery) {
            hasAttemptedPropertyRecovery = true
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                when (val result = offlineSyncRepository.syncProjectEssentials(project.serverId)) {
                    is SyncResult.Success -> {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    is SyncResult.Incomplete -> {
                        val message = when (result.reason) {
                            IncompleteReason.MISSING_PROPERTY -> "Property not set up yet. Please choose a property type to continue."
                        }
                        Log.w(
                            TAG,
                            "[ProjectType] Essentials sync incomplete (${result.reason}) for project $projectId; property still missing"
                        )
                        _uiState.update { it.copy(isLoading = false, errorMessage = message) }
                    }
                    is SyncResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = result.error?.message ?: genericErrorMessage
                            )
                        }
                    }
                }
            }
        }
    }

    private fun OfflineProjectEntity.displayName(): String {
        return listOfNotNull(
            addressLine1?.takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() },
            alias?.takeIf { it.isNotBlank() }
        ).firstOrNull() ?: "Project ${this.projectId}"
    }

    companion object {
        private const val TAG = "ProjectTypeSelection"

        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ProjectTypeSelectionViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return ProjectTypeSelectionViewModel(application, projectId) as T
            }
        }
    }
}
