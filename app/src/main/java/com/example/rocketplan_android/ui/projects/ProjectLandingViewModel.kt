package com.example.rocketplan_android.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed class ProjectLandingUiState {
    object Loading : ProjectLandingUiState()
    data class Ready(val summary: ProjectLandingSummary) : ProjectLandingUiState()
}

data class ProjectLandingSummary(
    val projectTitle: String,
    val projectCode: String,
    val aliasText: String?,
    val aliasIsActionable: Boolean,
    val statusLabel: String?,
    val noteCount: Int
)

class ProjectLandingViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService

    private val _uiState: MutableStateFlow<ProjectLandingUiState> =
        MutableStateFlow(ProjectLandingUiState.Loading)
    val uiState: StateFlow<ProjectLandingUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeNotes(projectId)
            ) { projects, notes ->
                val project = projects.firstOrNull { it.projectId == projectId }
                if (project == null) {
                    ProjectLandingUiState.Loading
                } else {
                    ProjectLandingUiState.Ready(
                        summary = project.toSummary(notes.size)
                    )
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    private fun OfflineProjectEntity.toSummary(noteCount: Int): ProjectLandingSummary {
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

        val statusLabel = status.takeIf { it.isNotBlank() }?.uppercase()

        return ProjectLandingSummary(
            projectTitle = titleText,
            projectCode = codeText,
            aliasText = aliasText,
            aliasIsActionable = aliasActionable,
            statusLabel = statusLabel,
            noteCount = noteCount
        )
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
