package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.net.Uri
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
import com.example.rocketplan_android.data.local.entity.OfflineRoomPhotoSnapshotEntity
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import kotlin.collections.buildSet

import com.example.rocketplan_android.data.local.entity.hasRenderableAsset
import com.example.rocketplan_android.data.local.entity.preferredImageSource
import com.example.rocketplan_android.data.local.entity.preferredThumbnailSource

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
    private val imageProcessorRepository = rocketPlanApp.imageProcessorRepository
    private val imageProcessorQueueManager = rocketPlanApp.imageProcessorQueueManager
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
    private var currentPhotoLookupRoomId: Long? = null
    private var lastSnapshotRoomId: Long? = null
    private val _resolvedRoom = MutableStateFlow<OfflineRoomEntity?>(null)
    private val snapshotRefreshMutex = Mutex()
    private val _isSnapshotRefreshing = MutableStateFlow(false)
    val isSnapshotRefreshing: StateFlow<Boolean> = _isSnapshotRefreshing.asStateFlow()

    private val _batchCaptureState = MutableStateFlow(BatchCaptureState())
    val batchCaptureState: StateFlow<BatchCaptureState> = _batchCaptureState.asStateFlow()

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
                        if (_resolvedRoom.value != null) {
                            _resolvedRoom.value = null
                        }
                        return@collect
                    }

                    if (shouldUpdateResolvedRoom(_resolvedRoom.value, resolved)) {
                        _resolvedRoom.value = resolved
                    } else {
                        Log.v(
                            TAG,
                            "🚫 Skipping redundant room emission; ids/title unchanged (roomId=${resolved.roomId}, serverId=${resolved.serverId})"
                        )
                    }
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
                currentPhotoLookupRoomId = photoLookupRoomId
                if (lastSnapshotRoomId != photoLookupRoomId) {
                    Log.d(TAG, "🗂 Refreshing photo snapshot for roomId=$photoLookupRoomId")
                    refreshSnapshot(photoLookupRoomId)
                } else {
                    Log.d(TAG, "🗂 Snapshot already fresh for roomId=$photoLookupRoomId; skipping refresh")
                }
                if (room.serverId != null && room.serverId != room.roomId) {
                    Log.d(TAG, "🧹 Clearing legacy snapshot for localRoomId=${room.roomId}")
                    localDataService.clearRoomPhotoSnapshot(room.roomId)
                }
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
                Log.d(TAG, "🗂 Sync complete; refreshing snapshot for roomId=$remoteRoomId")
                refreshSnapshot(remoteRoomId)
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
                    Log.d(TAG, "📸 Setting up snapshot paging for room: localId=$localId, snapshotRoomId=$lookupId")
                    localDataService
                        .pagedPhotoSnapshotsForRoom(lookupId)
                        .map { pagingData ->
                            val formatter = requireNotNull(dateFormatter.get())
                            pagingData.map { snapshot -> snapshot.toPhotoItem(formatter) }
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

    private fun OfflineRoomPhotoSnapshotEntity.toPhotoItem(formatter: SimpleDateFormat): RoomPhotoItem =
        RoomPhotoItem(
            id = photoId,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            capturedOn = capturedOn?.let { formatter.format(it) }
        )

    private fun shouldUpdateResolvedRoom(
        current: OfflineRoomEntity?,
        next: OfflineRoomEntity
    ): Boolean {
        if (current == null) return true
        return current.roomId != next.roomId ||
            current.serverId != next.serverId ||
            current.title != next.title
    }

    private suspend fun refreshSnapshot(roomId: Long) {
        snapshotRefreshMutex.withLock {
            _isSnapshotRefreshing.value = true
            try {
                localDataService.refreshRoomPhotoSnapshot(roomId)
                lastSnapshotRoomId = roomId
            } finally {
                _isSnapshotRefreshing.value = false
            }
        }
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
            Log.w(TAG, "⚠️ Ignoring captured photo because room is not resolved yet")
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
            Log.d(TAG, "📸 Photo captured: file=${photoFile.name}, size=${photoFile.length()} bytes, roomId=$lookupRoomId, projectId=$projectId")

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
            Log.d(TAG, "♻️ Refreshing snapshot after local capture for roomId=$lookupRoomId")
            refreshSnapshot(lookupRoomId)
            Log.d(TAG, "✅ Photo saved to local database: uuid=${entity.uuid}, isDirty=true, syncStatus=PENDING")

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

    // Batch capture functions
    fun startBatchCapture() {
        _batchCaptureState.value = BatchCaptureState(
            isActive = true,
            photos = emptyList(),
            groupUuid = UUID.randomUUID().toString(),
            maxPhotos = 50
        )
        Log.d(TAG, "📸 Batch capture started with groupUuid=${_batchCaptureState.value.groupUuid}")
    }

    fun addPhotoToBatch(photoFile: File): Boolean {
        val currentState = _batchCaptureState.value
        if (!currentState.isActive) {
            Log.w(TAG, "⚠️ Attempted to add photo to inactive batch")
            return false
        }
        if (currentState.photos.size >= currentState.maxPhotos) {
            Log.w(TAG, "⚠️ Batch is full (${currentState.maxPhotos} photos)")
            return false
        }

        _batchCaptureState.update { state ->
            state.copy(photos = state.photos + photoFile)
        }
        Log.d(TAG, "📸 Photo added to batch: ${currentState.photos.size + 1}/${currentState.maxPhotos}")
        return true
    }

    fun clearBatch() {
        // DON'T delete temp files here - they're needed for upload
        // Files will be deleted by ImageProcessorQueueManager after successful upload
        _batchCaptureState.value = BatchCaptureState()
        Log.d(TAG, "📸 Batch cleared (temp files preserved for upload)")
    }

    fun processBatch() {
        val batch = _batchCaptureState.value
        if (!batch.isActive || batch.photos.isEmpty()) {
            Log.w(TAG, "⚠️ Cannot process empty or inactive batch")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "📸 Processing batch: ${batch.photos.size} photos, groupUuid=${batch.groupUuid}")

                val room = _resolvedRoom.value
                if (room == null) {
                    Log.e(TAG, "❌ Cannot process batch: room not resolved")
                    remoteLogger.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "Batch processing failed: room not resolved",
                        metadata = mapOf(
                            "project_id_local" to projectId.toString(),
                            "room_id_nav_arg" to roomId.toString(),
                            "batch_size" to batch.photos.size.toString()
                        )
                    )
                    return@launch
                }

                // Save photos to database
                val photoEntities = batch.photos.mapIndexed { index, file ->
                    OfflinePhotoEntity(
                        uuid = UUID.randomUUID().toString(),
                        uploadStatus = "local_pending",
                        syncStatus = SyncStatus.PENDING,
                        isDirty = true,
                        roomId = room.roomId,
                        projectId = projectId,
                        localPath = file.absolutePath,
                        fileName = file.name,
                        mimeType = "image/jpeg",
                        capturedAt = Date(),
                        cacheStatus = PhotoCacheStatus.NONE
                    )
                }

                localDataService.savePhotos(photoEntities)
                Log.d(TAG, "✅ Saved ${photoEntities.size} photos to database")

                // Create FileToUpload list from batch photos
                val filesToUpload = batch.photos.map { file ->
                    com.example.rocketplan_android.data.model.FileToUpload(
                        uri = Uri.fromFile(file),
                        filename = file.name,
                        deleteOnCompletion = true // Delete temp files after upload
                    )
                }

                // Create assembly
                val result = imageProcessorRepository.createAssembly(
                    roomId = room.roomId,
                    projectId = projectId,
                    filesToUpload = filesToUpload,
                    templateId = "", // Empty string (unused, legacy from Transloadit)
                    groupUuid = batch.groupUuid,
                    albums = emptyMap(),
                    irPhotos = emptyList(),
                    order = emptyList(),
                    notes = emptyMap(),
                    entityType = "room",
                    entityId = room.serverId
                )

                result.onSuccess { assemblyId ->
                    Log.d(TAG, "✅ Assembly created: $assemblyId")

                    remoteLogger.log(
                        level = LogLevel.INFO,
                        tag = TAG,
                        message = "Batch assembly created successfully",
                        metadata = mapOf(
                            "assembly_id" to assemblyId,
                            "project_id_local" to projectId.toString(),
                            "room_id_local" to room.roomId.toString(),
                            "batch_size" to batch.photos.size.toString(),
                            "group_uuid" to batch.groupUuid
                        )
                    )

                    // Trigger queue processing
                    imageProcessorQueueManager.processNextQueuedAssembly()

                    // Clear batch after successful assembly creation
                    clearBatch()
                }.onFailure { error ->
                    Log.e(TAG, "❌ Failed to create assembly", error)

                    remoteLogger.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "Batch assembly creation failed: ${error.message}",
                        metadata = mapOf(
                            "project_id_local" to projectId.toString(),
                            "room_id_local" to room.roomId.toString(),
                            "batch_size" to batch.photos.size.toString(),
                            "error" to (error.message ?: "unknown")
                        )
                    )

                    // Don't clear batch on failure so user can retry
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Batch processing failed", e)
                remoteLogger.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Batch processing failed: ${e.message}",
                    metadata = mapOf(
                        "project_id_local" to projectId.toString(),
                        "room_id_nav_arg" to roomId.toString(),
                        "batch_size" to batch.photos.size.toString(),
                        "error" to (e.message ?: "unknown")
                    )
                )
            }
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

data class RoomAlbumItem(
    val id: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUrl: String?
)

data class BatchCaptureState(
    val isActive: Boolean = false,
    val photos: List<File> = emptyList(),
    val groupUuid: String = UUID.randomUUID().toString(),
    val maxPhotos: Int = 50
)

enum class RoomDetailTab {
    PHOTOS, DAMAGES
}
