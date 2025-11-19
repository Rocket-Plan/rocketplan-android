package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ProjectDetailViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager

    private val _uiState = MutableStateFlow<ProjectDetailUiState>(ProjectDetailUiState.Loading)
    val uiState: StateFlow<ProjectDetailUiState> = _uiState

    private val _selectedTab = MutableStateFlow(ProjectDetailTab.PHOTOS)
    val selectedTab: StateFlow<ProjectDetailTab> = _selectedTab

    init {
        // Prioritize this project in the sync queue
        // This ensures project essentials are synced via the queue (can be cancelled)
        syncQueueManager.prioritizeProject(projectId)

        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeRooms(projectId),
                localDataService.observePhotosForProject(projectId),
                localDataService.observeNotes(projectId),
                localDataService.observeAlbumsForProject(projectId),
                syncQueueManager.photoSyncingProjects
            ) { flows ->
                val projects = flows[0] as List<*>
                val rooms = flows[1] as List<*>
                val photos = flows[2] as List<*>
                val notes = flows[3] as List<*>
                val albums = flows[4] as List<*>
                val photoSyncingProjects = flows[5] as Set<*>

                Log.d("ProjectDetailVM", "📊 Data update for project $projectId: ${rooms.size} rooms, ${albums.size} albums, ${photos.size} photos")
                val project = (projects as List<OfflineProjectEntity>).firstOrNull { it.projectId == projectId }
                if (project == null) {
                    Log.d("ProjectDetailVM", "⚠️ Project $projectId not found in database")
                    ProjectDetailUiState.Loading
                } else {
                    Log.d("ProjectDetailVM", "✅ Project found: ${project.title}")
                    val header = project.toHeader((notes as List<OfflineNoteEntity>).size)
                    val isProjectPhotoSyncing = (photoSyncingProjects as Set<Long>).contains(projectId)
                    val sections = (rooms as List<OfflineRoomEntity>).toSections(photos as List<OfflinePhotoEntity>, isProjectPhotoSyncing)
                    val roomCreationStatus = project.resolveRoomCreationStatus(localDataService)
                    ProjectDetailUiState.Ready(
                        header = header,
                        levelSections = sections,
                        albums = (albums as List<OfflineAlbumEntity>).toAlbumSections(photos as List<OfflinePhotoEntity>),
                        roomCreationStatus = roomCreationStatus
                    )
                }
            }
            // Smooth out bursts while batched photos land
            .debounce(200)
            // Only emit when visible content meaningfully changes
            .distinctUntilChanged { old, new ->
                when {
                    old is ProjectDetailUiState.Ready && new is ProjectDetailUiState.Ready -> {
                        val oldPhotoTotal = old.levelSections.sumOf { it.rooms.sumOf { r -> r.photoCount } }
                        val newPhotoTotal = new.levelSections.sumOf { it.rooms.sumOf { r -> r.photoCount } }
                        val oldLoadingCount = old.levelSections.sumOf { it.rooms.count { r -> r.isLoadingPhotos } }
                        val newLoadingCount = new.levelSections.sumOf { it.rooms.count { r -> r.isLoadingPhotos } }

                        old.levelSections.size == new.levelSections.size &&
                            old.albums.size == new.albums.size &&
                            oldPhotoTotal == newPhotoTotal &&
                            oldLoadingCount == newLoadingCount &&
                            old.roomCreationStatus == new.roomCreationStatus
                    }
                    old is ProjectDetailUiState.Loading && new is ProjectDetailUiState.Loading -> true
                    else -> false
                }
            }
            .collect { state ->
                _uiState.value = state
            }
        }
    }

    fun selectTab(tab: ProjectDetailTab) {
        if (_selectedTab.value != tab) {
            _selectedTab.value = tab
        }
    }

    suspend fun deleteProject(): Boolean {
        return try {
            localDataService.deleteProject(projectId)
            true
        } catch (t: Throwable) {
            Log.e("ProjectDetailVM", "Failed to delete project $projectId", t)
            false
        }
    }

    private fun OfflineProjectEntity.toHeader(noteCount: Int): ProjectDetailHeader {
        val titleCandidates = listOfNotNull(
            addressLine1?.takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() },
            alias?.takeIf { it.isNotBlank() }
        )
        val titleText = titleCandidates.firstOrNull() ?: "Project $projectId"
        val codeText = uid?.takeIf { it.isNotBlank() } ?: projectNumber ?: ""
        val noteSummary = when (noteCount) {
            0 -> "No Notes"
            1 -> "1 Note"
            else -> "$noteCount Notes"
        }
        return ProjectDetailHeader(
            projectTitle = titleText,
            projectCode = codeText,
            noteSummary = noteSummary,
            noteCount = noteCount
        )
    }

    private fun List<OfflineRoomEntity>.toSections(
        photos: List<OfflinePhotoEntity>,
        isProjectPhotoSyncing: Boolean
    ): List<RoomLevelSection> {
        if (isEmpty()) {
            Log.d("ProjectDetailVM", "⚠️ No rooms found for project")
            return emptyList()
        }
        Log.d("ProjectDetailVM", "🏠 Loading ${this.size} rooms: ${this.map { "[${it.serverId}] ${it.title}" }}")
        val photosByRoom = photos.groupBy { it.roomId }
        return this
            .groupBy { room ->
                room.level?.takeIf { it.isNotBlank() } ?: "Unassigned"
            }
            .map { (level, groupedRooms) ->
                val roomCards = groupedRooms
                    .sortedBy { it.title }
                    .map { room ->
                        val roomKey = room.serverId ?: room.roomId
                        val roomPhotos = photosByRoom[roomKey].orEmpty()
                        val isLoadingPhotos = (isProjectPhotoSyncing && roomPhotos.isEmpty()) || room.serverId == null
                        RoomCard(
                            roomId = roomKey,
                            title = room.title,
                            level = level,
                            photoCount = roomPhotos.size,
                            thumbnailUrl = roomPhotos.firstNotNullOfOrNull { photo ->
                                photo.preferredThumbnailSourceForRoomCard()
                            },
                            isLoadingPhotos = isLoadingPhotos
                        )
                    }
                RoomLevelSection(levelName = level, rooms = roomCards)
            }
            .sortedBy { it.levelName }
    }

    private fun List<OfflineAlbumEntity>.toAlbumSections(
        photos: List<OfflinePhotoEntity>
    ): List<AlbumSection> {
        // Category album names that should be filtered out (not true room albums)
        val categoryAlbumNames = setOf(
            "Damage Assessment",
            "Betterments",
            "Contents",
            "Daily Progress",
            "Pre-existing Damages"
        )

        val photosByAlbumId = photos.filter { it.albumId != null }.groupBy { it.albumId }
        val populatedAlbums = this.mapNotNull { album ->
            // Filter out project-scoped albums (roomId == null) or category albums by name
            if (album.roomId == null || categoryAlbumNames.contains(album.name)) {
                Log.d("ProjectDetailVM", "🚫 Filtering project-scoped album ${album.name} (${album.albumId})")
                return@mapNotNull null
            }

            val albumPhotos = photosByAlbumId[album.albumId]?.takeIf { it.isNotEmpty() }
            val hasServerCount = album.photoCount > 0
            if (albumPhotos == null && !hasServerCount) {
                Log.d("ProjectDetailVM", "🗂️ Skipping empty album ${album.name}")
                null
            } else {
                val resolvedCount = albumPhotos?.size ?: album.photoCount
                val resolvedThumb = album.thumbnailUrl
                    ?: albumPhotos?.firstNotNullOfOrNull { it.preferredThumbnailSourceForRoomCard() }
                AlbumSection(
                    albumId = album.albumId,
                    name = album.name,
                    photoCount = resolvedCount,
                    thumbnailUrl = resolvedThumb
                )
            }
        }

        Log.d(
            "ProjectDetailVM",
            "📚 Showing ${populatedAlbums.size} populated albums (from ${this.size} total)"
        )
        return populatedAlbums.sortedBy { it.name }
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ProjectDetailViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return ProjectDetailViewModel(application, projectId) as T
            }
        }
    }
}

sealed class ProjectDetailUiState {
    object Loading : ProjectDetailUiState()
    data class Ready(
        val header: ProjectDetailHeader,
        val levelSections: List<RoomLevelSection>,
        val albums: List<AlbumSection> = emptyList(),
        val roomCreationStatus: RoomCreationStatus = RoomCreationStatus.UNKNOWN
    ) : ProjectDetailUiState()
}

data class ProjectDetailHeader(
    val projectTitle: String,
    val projectCode: String,
    val noteSummary: String,
    val noteCount: Int
)

data class RoomLevelSection(
    val levelName: String,
    val rooms: List<RoomCard>
)

private fun OfflinePhotoEntity.preferredThumbnailSourceForRoomCard(): String? {
    cachedThumbnailPath.existingFilePath()?.let { return it }
    thumbnailUrl?.takeIf { it.isNotBlank() }?.let { return it }
    cachedOriginalPath.existingFilePath()?.let { return it }
    remoteUrl?.takeIf { it.isNotBlank() }?.let { return it }
    return localPath.existingFilePath()
}

data class RoomCard(
    val roomId: Long,
    val title: String,
    val level: String,
    val photoCount: Int,
    val thumbnailUrl: String?,
    val isLoadingPhotos: Boolean
)

data class AlbumSection(
    val albumId: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUrl: String?
)

enum class ProjectDetailTab {
    PHOTOS, DAMAGES, SKETCH
}

enum class RoomCreationStatus {
    UNKNOWN,
    AVAILABLE,
    MISSING_PROPERTY,
    UNSYNCED_PROPERTY
}

private suspend fun OfflineProjectEntity.resolveRoomCreationStatus(
    localDataService: LocalDataService
): RoomCreationStatus {
    val propertyLocalId = propertyId ?: return RoomCreationStatus.MISSING_PROPERTY
    val property = localDataService.getProperty(propertyLocalId) ?: return RoomCreationStatus.MISSING_PROPERTY
    return if (property.serverId == null) {
        RoomCreationStatus.UNSYNCED_PROPERTY
    } else {
        RoomCreationStatus.AVAILABLE
    }
}

private fun String?.existingFilePath(): String? {
    if (this.isNullOrBlank()) return null
    val file = File(this)
    return file.takeIf { it.exists() }?.absolutePath
}
