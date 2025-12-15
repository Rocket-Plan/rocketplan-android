package com.example.rocketplan_android.ui.syncstatus

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class SyncStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager

    private val _uiState = MutableStateFlow<SyncStatusUiState>(SyncStatusUiState.Loading)
    val uiState: StateFlow<SyncStatusUiState> = _uiState
    private val recentEvents = MutableStateFlow<List<SyncActivityItem>>(emptyList())
    private val activeSince = mutableMapOf<Long, Long>()

    init {
        viewModelScope.launch {
            syncQueueManager.errors.collect { message ->
                appendEvent(
                    title = "Sync error",
                    message = message,
                    type = SyncActivityType.ERROR
                )
            }
        }

        viewModelScope.launch {
            syncQueueManager.isActive
                .collect { isActive ->
                    appendEvent(
                        title = "Sync queue",
                        message = if (isActive) "Sync started" else "Sync idle",
                        type = SyncActivityType.INFO
                    )
                }
        }

        viewModelScope.launch {
            combine(
                projectStatsFlow(),
                activeSyncsFlow(),
                queueItemsFlow(),
                recentEvents
            ) { projects, activeSyncs, queueItems, events ->
                if (projects.isEmpty() && activeSyncs.isEmpty() && queueItems.isEmpty() && events.isEmpty()) {
                    SyncStatusUiState.Empty
                } else {
                    SyncStatusUiState.Content(
                        projects = projects.sortedBy { it.projectTitle },
                        activeSyncs = activeSyncs,
                        queueItems = queueItems,
                        recentEvents = events
                    )
                }
            }.collect { _uiState.value = it }
        }
    }

    private fun projectStatsFlow(): Flow<List<ProjectSyncStatus>> =
        localDataService.observeProjects().flatMapLatest { projects ->
            if (projects.isEmpty()) {
                return@flatMapLatest flowOf(emptyList())
            }

            val allRoomsFlows = projects.map { localDataService.observeRooms(it.projectId) }
            val allPhotosFlows = projects.map { localDataService.observePhotosForProject(it.projectId) }
            val allAlbumsFlows = projects.map { localDataService.observeAlbumsForProject(it.projectId) }

            combine(
                combine(allRoomsFlows) { it.toList() },
                combine(allPhotosFlows) { it.toList() },
                combine(allAlbumsFlows) { it.toList() }
            ) { roomsByProject, photosByProject, albumsByProject ->
                projects.mapIndexed { index, project ->
                    buildProjectStats(
                        project,
                        roomsByProject[index],
                        photosByProject[index],
                        albumsByProject[index]
                    )
                }
            }
        }

    private fun activeSyncsFlow(): Flow<List<SyncActivityItem>> =
        combine(
            syncQueueManager.projectSyncingProjects,
            syncQueueManager.photoSyncingProjects,
            localDataService.observeProjects()
        ) { projectSyncing, photoSyncing, projects ->
            val now = System.currentTimeMillis()
            val activeIds = (projectSyncing + photoSyncing).toSet()
            activeSince.keys.retainAll(activeIds)
            activeIds.forEach { id -> activeSince.putIfAbsent(id, now) }

            activeIds.map { projectId ->
                val project = projects.firstOrNull { it.projectId == projectId }
                val title = project?.title ?: "Project $projectId"
                val message = when {
                    photoSyncing.contains(projectId) -> "Syncing photos"
                    else -> "Syncing project data"
                }
                SyncActivityItem(
                    id = "active-$projectId",
                    title = title,
                    status = message,
                    timestamp = activeSince[projectId] ?: now,
                    type = SyncActivityType.ACTIVE
                )
            }.sortedByDescending { it.timestamp }
        }

    private fun queueItemsFlow(): Flow<List<SyncQueueItem>> =
        combine(
            localDataService.observeSyncOperations(SyncStatus.PENDING),
            localDataService.observeSyncOperations(SyncStatus.FAILED)
        ) { pending, failed ->
            (pending + failed)
                .map { it.toQueueItem() }
                .sortedByDescending { it.displayTimestamp }
        }

    private fun appendEvent(
        title: String,
        message: String,
        type: SyncActivityType
    ) {
        val entry = SyncActivityItem(
            id = "event-${System.currentTimeMillis()}-${message.hashCode()}",
            title = title,
            status = message,
            timestamp = System.currentTimeMillis(),
            type = type
        )
        recentEvents.value = (listOf(entry) + recentEvents.value).take(MAX_EVENTS)
    }

    private fun buildProjectStats(
        project: OfflineProjectEntity,
        rooms: List<OfflineRoomEntity>,
        photos: List<OfflinePhotoEntity>,
        albums: List<OfflineAlbumEntity>
    ): ProjectSyncStatus {
        return ProjectSyncStatus(
            projectId = project.projectId,
            projectTitle = project.title,
            roomCount = rooms.size,
            photoCount = photos.size,
            albumCount = albums.size
        )
    }

    companion object {
        private const val MAX_EVENTS = 30
    }
}

sealed class SyncStatusUiState {
    object Loading : SyncStatusUiState()
    object Empty : SyncStatusUiState()
    data class Content(
        val projects: List<ProjectSyncStatus>,
        val activeSyncs: List<SyncActivityItem>,
        val queueItems: List<SyncQueueItem>,
        val recentEvents: List<SyncActivityItem>
    ) : SyncStatusUiState()
}

data class ProjectSyncStatus(
    val projectId: Long,
    val projectTitle: String,
    val roomCount: Int,
    val photoCount: Int,
    val albumCount: Int
)

data class SyncActivityItem(
    val id: String,
    val title: String,
    val status: String,
    val timestamp: Long,
    val type: SyncActivityType
)

enum class SyncActivityType {
    ACTIVE,
    INFO,
    ERROR
}

data class SyncQueueItem(
    val id: String,
    val entityType: String,
    val operationType: SyncOperationType,
    val status: SyncStatus,
    val createdAt: Date,
    val scheduledAt: Date?,
    val lastAttemptAt: Date?,
    val errorMessage: String?
) {
    val displayTimestamp: Long
        get() = (lastAttemptAt ?: scheduledAt ?: createdAt).time
}

private fun com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity.toQueueItem(): SyncQueueItem =
    SyncQueueItem(
        id = operationId,
        entityType = entityType.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
        operationType = operationType,
        status = status,
        createdAt = createdAt,
        scheduledAt = scheduledAt,
        lastAttemptAt = lastAttemptAt,
        errorMessage = errorMessage
    )
