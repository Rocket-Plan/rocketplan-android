package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
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
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository

    private val _uiState = MutableStateFlow<ProjectDetailUiState>(ProjectDetailUiState.Loading)
    val uiState: StateFlow<ProjectDetailUiState> = _uiState

    private val _selectedTab = MutableStateFlow(ProjectDetailTab.PHOTOS)
    val selectedTab: StateFlow<ProjectDetailTab> = _selectedTab

    init {
        // Trigger sync once per app session for this project (avoid repeated refresh on recreation)
        if (syncOnce(projectId)) {
            viewModelScope.launch {
                runCatching { offlineSyncRepository.syncProjectGraph(projectId) }
            }
        }

        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeRooms(projectId),
                localDataService.observePhotosForProject(projectId),
                localDataService.observeNotes(projectId),
                localDataService.observeAlbumsForProject(projectId)
            ) { projects, rooms, photos, notes, albums ->
                Log.d("ProjectDetailVM", "📊 Data update for project $projectId: ${rooms.size} rooms, ${albums.size} albums, ${photos.size} photos")
                val project = projects.firstOrNull { it.projectId == projectId }
                if (project == null) {
                    Log.d("ProjectDetailVM", "⚠️ Project $projectId not found in database")
                    ProjectDetailUiState.Loading
                } else {
                    Log.d("ProjectDetailVM", "✅ Project found: ${project.title}")
                    val header = project.toHeader(notes.size)
                    val sections = rooms.toSections(photos)
                    ProjectDetailUiState.Ready(
                        header = header,
                        levelSections = sections,
                        albums = albums.toAlbumSections(photos)
                    )
                }
            }
            // Smooth out bursts while batched photos land
            .debounce(200)
            // Only emit when visible content meaningfully changes
            .distinctUntilChanged { old, new ->
                when {
                    old is ProjectDetailUiState.Ready && new is ProjectDetailUiState.Ready ->
                        old.levelSections.size == new.levelSections.size &&
                            old.albums.size == new.albums.size &&
                            // Compare total photo counts across rooms to avoid noisy rebinds
                            old.levelSections.sumOf { it.rooms.sumOf { r -> r.photoCount } } ==
                                new.levelSections.sumOf { it.rooms.sumOf { r -> r.photoCount } }
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
        photos: List<OfflinePhotoEntity>
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
                        RoomCard(
                            roomId = roomKey,
                            title = room.title,
                            level = level,
                            photoCount = roomPhotos.size,
                            thumbnailUrl = roomPhotos.firstNotNullOfOrNull { photo ->
                                photo.thumbnailUrl.takeIf { !it.isNullOrBlank() }
                                    ?: photo.remoteUrl.takeIf { !it.isNullOrBlank() }
                            }
                        )
                    }
                RoomLevelSection(levelName = level, rooms = roomCards)
            }
            .sortedBy { it.levelName }
    }

    private fun List<OfflineAlbumEntity>.toAlbumSections(
        photos: List<OfflinePhotoEntity>
    ): List<AlbumSection> {
        Log.d(
            "ProjectDetailVM",
            "📚 Loading ${this.size} albums: ${this.map { "[${it.albumId}] ${it.name} (${it.albumableType ?: "null"})" }}"
        )

        // Only show room-scoped albums at the project level
        val roomScoped = this.filter { album ->
            album.roomId != null || album.albumableType?.endsWith("Room", ignoreCase = true) == true
        }

        return roomScoped.map { album ->
            AlbumSection(
                albumId = album.albumId,
                name = album.name,
                photoCount = album.photoCount,
                thumbnailUrl = album.thumbnailUrl
            )
        }.sortedBy { it.name }
    }

    companion object {
        // Track which projects have already been synced this app session
        private val syncedProjects: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf())

        private fun syncOnce(projectId: Long): Boolean {
            return if (syncedProjects.add(projectId)) true else false
        }

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
        val albums: List<AlbumSection> = emptyList()
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

data class RoomCard(
    val roomId: Long,
    val title: String,
    val level: String,
    val photoCount: Int,
    val thumbnailUrl: String?
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
