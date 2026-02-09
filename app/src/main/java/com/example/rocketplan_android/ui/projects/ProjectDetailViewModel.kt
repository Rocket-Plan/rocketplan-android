package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.model.CategoryAlbums
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
import java.io.File
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.rocketplan_android.data.repository.SyncSegment
import com.example.rocketplan_android.logging.LogLevel

data class ProjectDetailScreenState(
    val ui: ProjectDetailUiState = ProjectDetailUiState.Loading,
    val isSyncBlocking: Boolean = false,
    val essentialsSyncFailed: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ProjectDetailViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val syncNetworkMonitor = rocketPlanApp.syncNetworkMonitor
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val imageProcessorDao = rocketPlanApp.imageProcessorDao
    private val remoteLogger = rocketPlanApp.remoteLogger

    private val _screenState = MutableStateFlow(ProjectDetailScreenState())
    val screenState: StateFlow<ProjectDetailScreenState> = _screenState

    private val _selectedTab = MutableStateFlow(ProjectDetailTab.PHOTOS)
    val selectedTab: StateFlow<ProjectDetailTab> = _selectedTab
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val syncTimeout = MutableStateFlow(false)

    @Volatile
    private var lastIsBackgroundSyncing: Boolean? = null

    companion object {
        internal const val SYNC_TIMEOUT_MS = 15_000L

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

    init {
        // Prioritize this project in the sync queue
        // This ensures project essentials are synced via the queue (can be cancelled)
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
            var blockingResolved = hadEssentialsAtEntry

            combine(
                localDataService.observeProjects(),
                localDataService.observeRooms(projectId),
                localDataService.observePhotosForProject(projectId),
                localDataService.observeNotes(projectId),
                localDataService.observeAlbumsForProject(projectId)
            ) { projects, rooms, photos, notes, albums ->
                DetailData(projects, rooms, photos, notes, albums)
            }.combine(
                combine(
                    syncQueueManager.photoSyncingProjects,
                    syncQueueManager.projectSyncingProjects,
                    localDataService.observeDamages(projectId),
                    localDataService.observeWorkScopes(projectId),
                    imageProcessorDao.observeProcessingProgressByProject(projectId)
                ) { photoSyncingProjects, projectSyncingProjects, damages, workScopes, progressList ->
                    val progressMap = progressList.associateBy { it.roomId }
                    if (progressList.isNotEmpty()) {
                        Log.d("ProjectDetailVM", "📊 Processing progress: ${progressList.map { "${it.roomId}: ${it.completedPhotos}/${it.totalPhotos}" }}")
                    }
                    SyncExtras(photoSyncingProjects, projectSyncingProjects, damages, workScopes, progressMap)
                }
            ) { data, extra -> data to extra }
            .combine(
                combine(
                    localDataService.observeLocations(projectId),
                    syncQueueManager.projectEssentialsFailed,
                    syncNetworkMonitor.isOnline,
                    syncTimeout
                ) { locations, essentialsFailed, online, timeout ->
                    BlockingInputs(locations, essentialsFailed, online, timeout)
                }
            ) { pair, blockingInputs -> Triple(pair.first, pair.second, blockingInputs) }
            .mapLatest { (data, extra, blockingInputs) ->
                val (projects, rooms, photos, notes, albums) = data
                val (photoSyncingProjects, projectSyncingProjects, damages, workScopes, processingProgressMap) = extra
                val locations = blockingInputs.locations
                val essentialsFailed = blockingInputs.essentialsFailed.contains(projectId)
                val isOnline = blockingInputs.isOnline
                val timedOut = blockingInputs.timedOut

                Log.d("ProjectDetailVM", "📊 Data update for project $projectId: ${rooms.size} rooms, ${albums.size} albums, ${photos.size} photos")
                val project = projects.firstOrNull { it.projectId == projectId }
                val isProjectSyncing = projectSyncingProjects.contains(projectId)

                // Track if we ever saw syncing start
                if (isProjectSyncing) sawSyncing = true

                val uiState = if (project == null) {
                    Log.d("ProjectDetailVM", "⚠️ Project $projectId not found in database")
                    ProjectDetailUiState.Loading
                } else {
                    Log.d("ProjectDetailVM", "✅ Project found: ${project.title}")
                    if (lastIsBackgroundSyncing != isProjectSyncing) {
                        Log.d("ProjectDetailVM", "🔄 isBackgroundSyncing changed: $lastIsBackgroundSyncing → $isProjectSyncing for project $projectId")
                        lastIsBackgroundSyncing = isProjectSyncing
                    }
                    val isProjectPhotoSyncing = photoSyncingProjects.contains(projectId)
                    val sections = rooms.toSections(
                        photos = photos,
                        damages = damages,
                        workScopes = workScopes,
                        isProjectPhotoSyncing = isProjectPhotoSyncing,
                        processingProgressMap = processingProgressMap
                    )
                    val damageTotal = sections.sumOf { section -> section.rooms.sumOf { it.damageCount } }
                    val header = project.toHeader(notes.size, damageTotal)
                    val roomCreationStatus = project.resolveRoomCreationStatus(localDataService, isProjectSyncing)
                    if (roomCreationStatus == RoomCreationStatus.SYNCING) {
                        remoteLogger.log(
                            level = LogLevel.INFO,
                            tag = "ProjectDetailVM",
                            message = "Room creation blocked - sync in progress",
                            metadata = mapOf(
                                "project_id" to projectId.toString(),
                                "has_property_id" to (project.propertyId != null).toString()
                            )
                        )
                    }
                    ProjectDetailUiState.Ready(
                        header = header,
                        levelSections = sections,
                        albums = albums.toAlbumSections(photos),
                        roomCreationStatus = roomCreationStatus,
                        isBackgroundSyncing = isProjectSyncing
                    )
                }

                // Compute blocking state
                if (!blockingResolved) {
                    val escape = locations.isNotEmpty() || rooms.isNotEmpty() ||
                        (sawSyncing && !isProjectSyncing) ||
                        (project != null && project.serverId == null) ||
                        !isOnline ||
                        timedOut
                    if (escape) blockingResolved = true
                }

                ProjectDetailScreenState(
                    ui = uiState,
                    isSyncBlocking = !blockingResolved,
                    essentialsSyncFailed = essentialsFailed && !isProjectSyncing
                )
            }
            // Smooth out bursts while batched photos land
            .debounce(200)
            // Only emit when visible content meaningfully changes
            .distinctUntilChanged { old, new ->
                if (old.isSyncBlocking != new.isSyncBlocking) return@distinctUntilChanged false
                if (old.essentialsSyncFailed != new.essentialsSyncFailed) return@distinctUntilChanged false
                val oldUi = old.ui
                val newUi = new.ui
                when {
                    oldUi is ProjectDetailUiState.Ready && newUi is ProjectDetailUiState.Ready -> {
                        val oldPhotoTotal = oldUi.levelSections.sumOf { it.rooms.sumOf { r -> r.photoCount } }
                        val newPhotoTotal = newUi.levelSections.sumOf { it.rooms.sumOf { r -> r.photoCount } }
                        val oldLoadingCount = oldUi.levelSections.sumOf { it.rooms.count { r -> r.isLoadingPhotos } }
                        val newLoadingCount = newUi.levelSections.sumOf { it.rooms.count { r -> r.isLoadingPhotos } }
                        val oldDamageTotal = oldUi.levelSections.sumOf { it.rooms.sumOf { r -> r.damageCount } }
                        val newDamageTotal = newUi.levelSections.sumOf { it.rooms.sumOf { r -> r.damageCount } }

                        oldUi.levelSections.size == newUi.levelSections.size &&
                            oldUi.levelSections == newUi.levelSections &&
                            oldUi.albums.size == newUi.albums.size &&
                            oldPhotoTotal == newPhotoTotal &&
                            oldLoadingCount == newLoadingCount &&
                            oldDamageTotal == newDamageTotal &&
                            oldUi.roomCreationStatus == newUi.roomCreationStatus &&
                            oldUi.isBackgroundSyncing == newUi.isBackgroundSyncing
                    }
                    oldUi is ProjectDetailUiState.Loading && newUi is ProjectDetailUiState.Loading -> true
                    else -> false
                }
            }
            .collect { state ->
                _screenState.value = state
            }
        }
    }

    fun selectTab(tab: ProjectDetailTab) {
        if (_selectedTab.value != tab) {
            _selectedTab.value = tab
        }
    }

    fun refreshRoomsAndThumbnails() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                Log.d("ProjectDetailVM", "🔄 Pull-to-refresh project $projectId (rooms + targeted photo sync)")
                val results = offlineSyncRepository.syncProjectGraph(projectId, skipPhotos = true, source = "ProjectDetailFragment")
                val essentials = results.firstOrNull { it.segment == SyncSegment.PROJECT_ESSENTIALS }
                if (essentials == null || !essentials.success) {
                    Log.w("ProjectDetailVM", "⚠️ Project refresh incomplete for project $projectId; essentials=$essentials")
                } else {
                    Log.d("ProjectDetailVM", "✅ Project essentials synced for project $projectId; checking photo mismatches")
                    // Sync only rooms with photo count mismatches (much faster than full sync)
                    val syncedRooms = offlineSyncRepository.syncRoomsWithMismatchedPhotoCounts(projectId)
                    if (syncedRooms > 0) {
                        Log.d("ProjectDetailVM", "📷 Synced photos for $syncedRooms rooms with count mismatches")
                    }
                }
            } catch (t: Throwable) {
                Log.e("ProjectDetailVM", "❌ Failed to refresh project $projectId via pull-to-refresh", t)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    suspend fun deleteProject(): Boolean =
        runCatching {
            offlineSyncRepository.deleteProject(projectId)
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                Log.e("ProjectDetailVM", "Failed to delete project $projectId", error)
                false
            }
        )

    private fun OfflineProjectEntity.toHeader(noteCount: Int, damageCountTotal: Int): ProjectDetailHeader {
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
            noteCount = noteCount,
            damageCountTotal = damageCountTotal
        )
    }

    private fun List<OfflineRoomEntity>.toSections(
        photos: List<OfflinePhotoEntity>,
        damages: List<OfflineDamageEntity>,
        workScopes: List<OfflineWorkScopeEntity>,
        isProjectPhotoSyncing: Boolean,
        processingProgressMap: Map<Long, ImageProcessorDao.RoomProcessingProgress> = emptyMap()
    ): List<RoomLevelSection> {
        val visibleRooms = filterNot { room ->
            room.roomId == 0L
        }
        if (visibleRooms.size != size) {
            Log.w(
                "ProjectDetailVM",
                "🧹 Filtering ${size - visibleRooms.size} phantom rooms (roomId=0)"
            )
        }
        if (visibleRooms.isEmpty()) {
            Log.d("ProjectDetailVM", "⚠️ No rooms found for project")
            return emptyList()
        }
        Log.d(
            "ProjectDetailVM",
            "🏠 Loading ${visibleRooms.size} rooms: ${visibleRooms.map { "[${it.serverId ?: it.roomId}] ${it.title}" }}"
        )
        val photosByRoom = photos.groupBy { it.roomId }
        val damagesByRoom = damages.groupBy { it.roomId }
        val scopesByRoom = workScopes.groupBy { it.roomId }
        val scopeTotalsByRoom = scopesByRoom.mapValues { (_, scopes) ->
            scopes.sumOf { scope ->
                val lineTotal = scope.lineTotal ?: 0.0
                val quantity = scope.quantity ?: 0.0
                val rate = scope.rate ?: 0.0
                val computedTotal = if (lineTotal > 0.0) lineTotal else quantity * rate
                if (!computedTotal.isNaN() && !computedTotal.isInfinite() && computedTotal > 0.0) computedTotal else 0.0
            }
        }
        return visibleRooms
            .groupBy { room ->
                room.level?.takeIf { it.isNotBlank() } ?: "Unassigned"
            }
            .map { (level, groupedRooms) ->
                val roomCards = groupedRooms
                    .sortedBy { it.title }
                    .map { room ->
                        val roomKey = room.serverId ?: room.roomId
                        val roomPhotos = photosByRoom[roomKey].orEmpty()
                        val resolvedPhotoCount = maxOf(roomPhotos.size, room.photoCount ?: 0)
                        val resolvedThumbnail = room.thumbnailUrl
                            ?: roomPhotos.firstNotNullOfOrNull { photo ->
                                photo.preferredThumbnailSourceForRoomCard()
                            }
                        val hasAnyPhotos = resolvedPhotoCount > 0 || roomPhotos.isNotEmpty()
                        val isLoadingPhotos = hasAnyPhotos && (
                            room.serverId == null ||
                                (isProjectPhotoSyncing && resolvedPhotoCount > roomPhotos.size) ||
                                (isProjectPhotoSyncing && roomPhotos.isEmpty())
                            )
                        val relatedRoomIds = buildSet {
                            add(room.roomId)
                            room.serverId?.let { add(it) }
                        }
                        val damageCount = damages.count { damage ->
                            val rid = damage.roomId ?: return@count false
                            relatedRoomIds.contains(rid)
                        } + workScopes.count { scope ->
                            val rid = scope.roomId ?: return@count false
                            relatedRoomIds.contains(rid)
                        }
                        val scopeTotal = relatedRoomIds.sumOf { id ->
                            scopeTotalsByRoom[id] ?: 0.0
                        }
                        val iconRes = RoomTypeCatalog.resolveIconRes(
                            context = getApplication(),
                            typeId = room.roomTypeId,
                            iconName = room.roomType ?: room.title
                        )
                        // Look up processing progress using both local and server IDs
                        val progress = relatedRoomIds.mapNotNull { id ->
                            processingProgressMap[id]
                        }.let { progressList ->
                            if (progressList.isEmpty()) null
                            else {
                                val totalPhotos = progressList.fold(0) { acc, p -> acc + p.totalPhotos }
                                val completedPhotos = progressList.fold(0) { acc, p -> acc + p.completedPhotos }
                                ProcessingProgress(completed = completedPhotos, total = totalPhotos)
                            }
                        }
                        val pendingCount = progress?.total ?: 0
                        RoomCard(
                            roomId = roomKey,
                            title = room.title,
                            level = level,
                            photoCount = resolvedPhotoCount,
                            pendingPhotoCount = pendingCount,
                            processingProgress = progress,
                            damageCount = damageCount,
                            scopeTotal = scopeTotal,
                            thumbnailUrl = resolvedThumbnail,
                            isLoadingPhotos = isLoadingPhotos,
                            iconRes = iconRes
                        )
                    }
                RoomLevelSection(levelName = level, rooms = roomCards)
            }
            .sortedBy { it.levelName }
    }

    private fun List<OfflineAlbumEntity>.toAlbumSections(
        photos: List<OfflinePhotoEntity>
    ): List<AlbumSection> {
        val photosByAlbumId = photos.filter { it.albumId != null }.groupBy { it.albumId }
        val populatedAlbums = this.mapNotNull { album ->
            // Filter out project-scoped albums (roomId == null) or category albums by name
            if (album.roomId == null || CategoryAlbums.isCategory(album.name)) {
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

}

sealed class ProjectDetailUiState {
    object Loading : ProjectDetailUiState()
    data class Ready(
        val header: ProjectDetailHeader,
        val levelSections: List<RoomLevelSection>,
        val albums: List<AlbumSection> = emptyList(),
        val roomCreationStatus: RoomCreationStatus = RoomCreationStatus.UNKNOWN,
        val isBackgroundSyncing: Boolean = false
    ) : ProjectDetailUiState()
}

data class ProjectDetailHeader(
    val projectTitle: String,
    val projectCode: String,
    val noteSummary: String,
    val noteCount: Int,
    val damageCountTotal: Int
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
    val pendingPhotoCount: Int = 0,
    val processingProgress: ProcessingProgress? = null,
    val damageCount: Int,
    val scopeTotal: Double,
    val thumbnailUrl: String?,
    val isLoadingPhotos: Boolean,
    val iconRes: Int
)

data class ProcessingProgress(
    val completed: Int,
    val total: Int
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
    UNSYNCED_PROPERTY,
    SYNCING
}

private suspend fun OfflineProjectEntity.resolveRoomCreationStatus(
    localDataService: LocalDataService,
    isSyncing: Boolean
): RoomCreationStatus {
    val propertyLocalId = propertyId
    if (propertyLocalId == null) {
        // If sync is in progress, property may not have loaded yet - show loading state
        if (isSyncing) {
            android.util.Log.d("RoomCreation", "⏳ SYNCING: project $projectId has null propertyId but sync is in progress")
            return RoomCreationStatus.SYNCING
        }
        android.util.Log.d("RoomCreation", "❌ MISSING_PROPERTY: project $projectId has null propertyId")
        return RoomCreationStatus.MISSING_PROPERTY
    }
    // Try to find property by propertyId first, then by serverId as fallback
    val property = localDataService.getProperty(propertyLocalId)
        ?: localDataService.getPropertyByServerId(propertyLocalId)
    if (property == null) {
        // If sync is in progress, property may not have loaded yet - show loading state
        if (isSyncing) {
            android.util.Log.d("RoomCreation", "⏳ SYNCING: project $projectId has propertyId=$propertyLocalId but property not found, sync in progress")
            return RoomCreationStatus.SYNCING
        }
        android.util.Log.d("RoomCreation", "❌ MISSING_PROPERTY: project $projectId has propertyId=$propertyLocalId but getProperty returned null")
        return RoomCreationStatus.MISSING_PROPERTY
    }
    android.util.Log.d("RoomCreation", "✅ AVAILABLE: project $projectId has property ${property.propertyId} (serverId=${property.serverId})")
    // Allow room creation as long as property exists locally - rooms will sync when online
    return RoomCreationStatus.AVAILABLE
}

private fun String?.existingFilePath(): String? {
    if (this.isNullOrBlank()) return null
    val file = File(this)
    return file.takeIf { it.exists() }?.absolutePath
}

private data class DetailData(
    val projects: List<OfflineProjectEntity>,
    val rooms: List<OfflineRoomEntity>,
    val photos: List<OfflinePhotoEntity>,
    val notes: List<OfflineNoteEntity>,
    val albums: List<OfflineAlbumEntity>
)

private data class BlockingInputs(
    val locations: List<OfflineLocationEntity>,
    val essentialsFailed: Set<Long>,
    val isOnline: Boolean,
    val timedOut: Boolean
)

private data class SyncExtras(
    val photoSyncingProjects: Set<Long>,
    val projectSyncingProjects: Set<Long>,
    val damages: List<OfflineDamageEntity>,
    val workScopes: List<OfflineWorkScopeEntity>,
    val processingProgress: Map<Long, ImageProcessorDao.RoomProcessingProgress> = emptyMap()
)
