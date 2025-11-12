package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.logging.LogLevel
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import kotlin.collections.buildSet

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDetailViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val remoteLogger = rocketPlanApp.remoteLogger
    private val dateFormatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MM/dd/yyyy", Locale.US)
    }

    private val _uiState = MutableStateFlow<RoomDetailUiState>(RoomDetailUiState.Loading)
    val uiState: StateFlow<RoomDetailUiState> = _uiState

    private val _selectedTab = MutableStateFlow(RoomDetailTab.PHOTOS)
    val selectedTab: StateFlow<RoomDetailTab> = _selectedTab

    private var lastRefreshAt = 0L
    private var isRefreshing = false
    private var lastSyncedServerRoomId: Long? = null
    private val _resolvedRoom = MutableStateFlow<OfflineRoomEntity?>(null)

    init {
        Log.d(TAG, "📦 init(projectId=$projectId, roomId=$roomId)")
        viewModelScope.launch {
            localDataService.observeRooms(projectId)
                .map { rooms ->
                    rooms.firstOrNull { it.roomId == roomId || it.serverId == roomId }
                        ?.also { room ->
                            Log.d(
                                TAG,
                                "🏠 Resolved room: title='${room.title}', localId=${room.roomId}, serverId=${room.serverId}, navArg=$roomId"
                            )
                        }
                }
                .distinctUntilChanged()
                .collect { resolved ->
                    if (resolved == null) {
                        Log.d(TAG, "⏳ Room resolution pending for navArg=$roomId (projectId=$projectId)")
                    }
                    _resolvedRoom.value = resolved
                }
        }

        viewModelScope.launch {
            _resolvedRoom.collectLatest { room ->
                if (room == null) {
                    Log.d(TAG, "⚠️ Room $roomId not yet available; emitting Loading")
                    _uiState.value = RoomDetailUiState.Loading
                    return@collectLatest
                }

                val localRoomId = room.roomId
                // Photos and albums are persisted with server room ID when available
                val photoLookupRoomId = room.serverId ?: room.roomId
                combine(
                    localDataService.observeNotes(projectId),
                    localDataService.observeAlbumsForRoom(photoLookupRoomId),
                    localDataService.observePhotoCountForRoom(photoLookupRoomId)
                ) { notes, albums, photoCount ->
                    val noteRoomIds = buildSet {
                        add(localRoomId)
                        room.serverId?.let { add(it) }
                    }
                    val roomNotes = notes.filter { note ->
                        val noteRoomId = note.roomId
                        noteRoomId != null && noteRoomId in noteRoomIds
                    }
                    Log.d(
                        TAG,
                        "✅ Room ready: '${room.title}', localRoomId=$localRoomId, serverId=${room.serverId}, photoLookupId=$photoLookupRoomId, photoCount=$photoCount, albumCount=${albums.size}, noteCount=${roomNotes.size}"
                    )
                    RoomDetailUiState.Ready(
                        header = room.toHeader(roomNotes),
                        albums = albums.toAlbumItems(),
                        photoCount = photoCount
                    )
                }.collect { state ->
                    _uiState.value = state
                }

                val serverId = room.serverId
                if (serverId != null && serverId != lastSyncedServerRoomId) {
                    lastSyncedServerRoomId = serverId
                    Log.d(TAG, "⚡️ Server room id resolved ($serverId); forcing photo refresh")
                    ensureRoomPhotosFresh(force = true)
                }
            }
        }
    }

    fun selectTab(tab: RoomDetailTab) {
        if (_selectedTab.value != tab) {
            _selectedTab.value = tab
        }
    }


    fun ensureRoomPhotosFresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAt < ROOM_REFRESH_INTERVAL_MS) {
            return
        }
        if (isRefreshing) {
            return
        }

        val remoteRoomId = _resolvedRoom.value?.serverId
        if (remoteRoomId == null) {
            Log.d(TAG, "⏭️ Skipping remote photo refresh; room $roomId has no serverId yet")
            return
        }
        isRefreshing = true
        viewModelScope.launch {
            try {
                Log.d(
                    TAG,
                    "🔄 ensureRoomPhotosFresh(force=$force) -> refreshRoomPhotos(projectId=$projectId, remoteRoomId=$remoteRoomId)"
                )
                offlineSyncRepository.refreshRoomPhotos(projectId, remoteRoomId)
            } catch (t: Throwable) {
                Log.e(TAG, "❌ Failed to refresh photos for remoteRoomId=$remoteRoomId", t)
                remoteLogger.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Failed to refresh room photos",
                    metadata = mapOf(
                        "projectId" to projectId.toString(),
                        "roomId" to remoteRoomId.toString()
                    )
                )
            } finally {
                lastRefreshAt = SystemClock.elapsedRealtime()
                isRefreshing = false
                Log.d(TAG, "✅ ensureRoomPhotosFresh done; lastRefreshAt=$lastRefreshAt")
            }
        }
    }

    val photoPagingData: Flow<PagingData<RoomPhotoItem>> =
        _resolvedRoom
            .map { room -> Pair(room?.roomId, room?.serverId ?: room?.roomId) }
            .distinctUntilChanged()
            .flatMapLatest { pair ->
                val localId = pair.first
                val lookupId = pair.second
                if (localId == null || lookupId == null) {
                    flowOf(PagingData.empty())
                } else {
                    // Photos are persisted with server room ID (persistPhotos uses defaultRoomId = serverId)
                    Log.d(TAG, "📸 Setting up photo paging for room: localId=$localId, lookupId=$lookupId")
                    localDataService
                        .pagedPhotosForRoom(lookupId)
                        .map { pagingData ->
                            val formatter = requireNotNull(dateFormatter.get())
                            pagingData
                                .filter { it.hasRenderableAsset() }
                                .map { photo -> photo.toPhotoItem(formatter) }
                        }
                }
            }
            .cachedIn(viewModelScope)

    private fun OfflineRoomEntity.toHeader(notes: List<OfflineNoteEntity>): RoomDetailHeader {
        val noteCount = notes.size
        val summary = when (noteCount) {
            0 -> "No Notes"
            1 -> "1 Note"
            else -> "$noteCount Notes"
        }
        return RoomDetailHeader(
            title = title,
            noteSummary = summary
        )
    }

    private fun OfflinePhotoEntity.toPhotoItem(formatter: SimpleDateFormat): RoomPhotoItem =
        RoomPhotoItem(
            id = photoId,
            imageUrl = preferredImageSource(),
            thumbnailUrl = preferredThumbnailSource(),
            capturedOn = capturedAt?.let { formatter.format(it) }
        )

    private fun List<OfflineAlbumEntity>.toAlbumItems(): List<RoomAlbumItem> {
        return this.map { album ->
            RoomAlbumItem(
                id = album.albumId,
                name = album.name,
                photoCount = album.photoCount,
                thumbnailUrl = album.thumbnailUrl
            )
        }
    }

    fun onLocalPhotoCaptured(photoFile: File, mimeType: String, albumId: Long? = null) {
        val room = _resolvedRoom.value
        if (room == null) {
            remoteLogger.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Ignoring captured photo because room is not resolved yet."
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val timestamp = Date()
            val lookupRoomId = room.serverId ?: room.roomId
            val entity = OfflinePhotoEntity(
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = lookupRoomId,
                albumId = albumId,
                fileName = photoFile.name,
                localPath = photoFile.absolutePath,
                mimeType = mimeType,
                fileSize = photoFile.length(),
                capturedAt = timestamp,
                createdAt = timestamp,
                updatedAt = timestamp,
                uploadStatus = "local_pending",
                syncStatus = SyncStatus.PENDING,
                isDirty = true,
                cacheStatus = PhotoCacheStatus.READY,
                cachedOriginalPath = photoFile.absolutePath,
                cachedThumbnailPath = null,
                lastAccessedAt = timestamp
            )
            localDataService.savePhotos(listOf(entity))
            remoteLogger.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Captured photo saved locally",
                metadata = mapOf(
                    "projectId" to projectId.toString(),
                    "roomId" to lookupRoomId.toString(),
                    "fileName" to photoFile.name
                )
            )
        }
    }

    companion object {
        private const val TAG = "RoomDetailVM"
        private const val ROOM_REFRESH_INTERVAL_MS = 10_000L

        fun provideFactory(
            application: Application,
            projectId: Long,
            roomId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RoomDetailViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return RoomDetailViewModel(application, projectId, roomId) as T
            }
        }
    }
}

sealed class RoomDetailUiState {
    object Loading : RoomDetailUiState()
    data class Ready(
        val header: RoomDetailHeader,
        val albums: List<RoomAlbumItem> = emptyList(),
        val photoCount: Int = 0
    ) : RoomDetailUiState()
}

data class RoomDetailHeader(
    val title: String,
    val noteSummary: String
)

data class RoomPhotoItem(
    val id: Long,
    val imageUrl: String,
    val thumbnailUrl: String,
    val capturedOn: String?
)

private fun OfflinePhotoEntity.hasRenderableAsset(): Boolean =
    !remoteUrl.isNullOrBlank() ||
        !thumbnailUrl.isNullOrBlank() ||
        !cachedOriginalPath.isNullOrBlank() ||
        !cachedThumbnailPath.isNullOrBlank() ||
        localPath.isNotBlank()

private fun OfflinePhotoEntity.preferredImageSource(): String =
    when {
        !cachedOriginalPath.isNullOrBlank() -> cachedOriginalPath
        !remoteUrl.isNullOrBlank() -> remoteUrl
        !localPath.isNullOrBlank() -> localPath
        !cachedThumbnailPath.isNullOrBlank() -> cachedThumbnailPath
        !thumbnailUrl.isNullOrBlank() -> thumbnailUrl!!
        else -> ""
    }

private fun OfflinePhotoEntity.preferredThumbnailSource(): String =
    when {
        !cachedThumbnailPath.isNullOrBlank() -> cachedThumbnailPath
        !thumbnailUrl.isNullOrBlank() -> thumbnailUrl
        !cachedOriginalPath.isNullOrBlank() -> cachedOriginalPath
        !remoteUrl.isNullOrBlank() -> remoteUrl
        !localPath.isNullOrBlank() -> localPath
        else -> ""
    }

data class RoomAlbumItem(
    val id: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUrl: String?
)

enum class RoomDetailTab {
    PHOTOS, DAMAGES
}
