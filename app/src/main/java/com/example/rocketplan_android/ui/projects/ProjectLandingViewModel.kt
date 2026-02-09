package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.model.ProjectStatus
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class AliasUpdated(val alias: String) : ProjectLandingEvent()
    data class AliasUpdateFailed(val error: String) : ProjectLandingEvent()
}

data class ProjectLandingSummary(
    val projectTitle: String,
    val projectCode: String,
    val aliasText: String?,
    val aliasIsActionable: Boolean,
    val aliasIsUpdating: Boolean,
    val status: ProjectStatus?,
    val statusLabel: String?,
    val noteCount: Int,
    val hasLevels: Boolean,
    val hasRooms: Boolean,
    val hasProperty: Boolean,
    val isSyncing: Boolean = false
)

data class ProjectLandingScreenState(
    val ui: ProjectLandingUiState = ProjectLandingUiState.Loading,
    val isSyncBlocking: Boolean = false,
    val essentialsSyncFailed: Boolean = false
)

class ProjectLandingViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val syncNetworkMonitor = rocketPlanApp.syncNetworkMonitor
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository

    private val _screenState = MutableStateFlow(ProjectLandingScreenState())
    val screenState: StateFlow<ProjectLandingScreenState> = _screenState

    private val _events = Channel<ProjectLandingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val aliasUpdateInProgress = MutableStateFlow(false)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val syncTimeout = MutableStateFlow(false)

    companion object {
        internal const val SYNC_TIMEOUT_MS = 15_000L

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

    init {
        // Prioritize this project in the sync queue
        // This ensures the project is synced immediately and jumps to front of queue
        syncQueueManager.prioritizeProject(projectId)

        viewModelScope.launch {
            // One-shot check: did we already have essentials cached at entry?
            val hadEssentialsAtEntry = localDataService.getLocations(projectId).isNotEmpty()

            // Only start timeout for uncached projects
            if (!hadEssentialsAtEntry) {
                launch { delay(SYNC_TIMEOUT_MS); syncTimeout.value = true }
            }

            // Sticky latches for the blocking state machine
            var sawSyncing = false
            var blockingResolved = hadEssentialsAtEntry // cached → already resolved

            combine(
                localDataService.observeProjects(),
                localDataService.observeNotes(projectId),
                localDataService.observeLocations(projectId),
                localDataService.observeRooms(projectId),
                aliasUpdateInProgress
            ) { projects, notes, locations, rooms, aliasUpdating ->
                UiInputs(projects, notes, locations, rooms, aliasUpdating)
            }.combine(
                combine(
                    syncQueueManager.projectSyncingProjects,
                    syncQueueManager.projectEssentialsFailed,
                    syncNetworkMonitor.isOnline,
                    syncTimeout
                ) { syncing, essentialsFailed, online, timeout ->
                    SyncInputs(syncing, essentialsFailed, online, timeout)
                }
            ) { inputs, syncInputs ->
                val (projects, notes, locations, rooms, aliasUpdating) = inputs
                val project = projects.firstOrNull { it.projectId == projectId }
                val isSyncing = syncInputs.syncingProjects.contains(projectId)
                val isOnline = syncInputs.isOnline
                val timedOut = syncInputs.timedOut
                val essentialsFailed = syncInputs.essentialsFailed.contains(projectId)

                // Track if we ever saw syncing start
                if (isSyncing) sawSyncing = true

                // Build the UI state
                val uiState = if (project == null) {
                    ProjectLandingUiState.Loading
                } else {
                    ProjectLandingUiState.Ready(
                        summary = project.toSummary(
                            noteCount = notes.size,
                            hasLevels = locations.isNotEmpty(),
                            hasRooms = rooms.isNotEmpty(),
                            aliasIsUpdating = aliasUpdating,
                            isSyncing = isSyncing
                        )
                    )
                }

                // Compute blocking: only block uncached, unresolved projects
                if (!blockingResolved) {
                    val escape = locations.isNotEmpty() || rooms.isNotEmpty() ||
                        (sawSyncing && !isSyncing) ||
                        (project != null && project.serverId == null) ||
                        !isOnline ||
                        timedOut
                    if (escape) blockingResolved = true
                }

                val shouldBlock = !blockingResolved
                ProjectLandingScreenState(
                    ui = uiState,
                    isSyncBlocking = shouldBlock,
                    essentialsSyncFailed = essentialsFailed && !isSyncing
                )
            }.collect { state ->
                _screenState.value = state
            }
        }
    }

    private fun OfflineProjectEntity.toSummary(
        noteCount: Int,
        hasLevels: Boolean,
        hasRooms: Boolean,
        aliasIsUpdating: Boolean,
        isSyncing: Boolean
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
        val aliasActionable = !aliasIsUpdating

        val rawStatus = status.trim()
        val projectStatus = ProjectStatus.fromApiValue(rawStatus)
        val statusLabel = when {
            projectStatus != null -> getApplication<Application>().getString(projectStatus.labelRes)
            rawStatus.isNotEmpty() && !rawStatus.equals("unknown", ignoreCase = true) -> rawStatus
                .replace("_", " ")
                .replace("-", " ")
                .replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                }
            else -> null
        }

        return ProjectLandingSummary(
            projectTitle = titleText,
            projectCode = codeText,
            aliasText = aliasText,
            aliasIsActionable = aliasActionable,
            aliasIsUpdating = aliasIsUpdating,
            status = projectStatus,
            statusLabel = statusLabel,
            noteCount = noteCount,
            hasLevels = hasLevels,
            hasRooms = hasRooms,
            hasProperty = propertyId != null,
            isSyncing = isSyncing
        )
    }

    fun addAlias(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            viewModelScope.launch {
                val message = getApplication<Application>().getString(R.string.project_alias_required)
                _events.send(ProjectLandingEvent.AliasUpdateFailed(message))
            }
            return
        }
        if (aliasUpdateInProgress.value) {
            return
        }

        viewModelScope.launch {
            aliasUpdateInProgress.value = true
            try {
                val result = offlineSyncRepository.updateProjectAlias(projectId, trimmed)
                result.fold(
                    onSuccess = { updated ->
                        val resolvedAlias = updated.alias?.takeIf { it.isNotBlank() } ?: trimmed
                        _events.send(ProjectLandingEvent.AliasUpdated(resolvedAlias))
                    },
                    onFailure = { error ->
                        val fallback = getApplication<Application>().getString(R.string.project_alias_update_failed)
                        _events.send(ProjectLandingEvent.AliasUpdateFailed(error.message ?: fallback))
                    }
                )
            } finally {
                aliasUpdateInProgress.value = false
            }
        }
    }

    fun updateProjectStatus(projectStatus: ProjectStatus) {
        viewModelScope.launch {
            offlineSyncRepository.updateProjectStatus(projectId, projectStatus)
        }
    }

    fun refreshProject() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                Log.d("ProjectLandingVM", "🔄 Pull-to-refresh project $projectId")
                offlineSyncRepository.syncProjectGraph(projectId, skipPhotos = false, source = "ProjectLandingFragment")
                Log.d("ProjectLandingVM", "✅ Refresh complete for project $projectId")
            } catch (t: Throwable) {
                Log.e("ProjectLandingVM", "❌ Failed to refresh project $projectId", t)
            } finally {
                _isRefreshing.value = false
            }
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

}

private data class UiInputs(
    val projects: List<OfflineProjectEntity>,
    val notes: List<OfflineNoteEntity>,
    val locations: List<OfflineLocationEntity>,
    val rooms: List<OfflineRoomEntity>,
    val aliasUpdating: Boolean
)

private data class SyncInputs(
    val syncingProjects: Set<Long>,
    val essentialsFailed: Set<Long>,
    val isOnline: Boolean,
    val timedOut: Boolean
)
