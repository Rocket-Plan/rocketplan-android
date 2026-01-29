package com.example.rocketplan_android.ui.projects.batchcapture

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.model.CategoryAlbums
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.rocketplan_android.util.UuidUtils
import java.io.File

data class BatchCaptureUiState(
    val photos: List<BatchPhotoItem> = emptyList(),
    val maxPhotos: Int = 50,
    val isProcessing: Boolean = false,
    val roomTitle: String = "",
    val categories: List<PhotoCategoryOption> = emptyList(),
    val selectedCategoryId: Long? = null
) {
    val photoCount: Int get() = photos.size
    val canTakeMore: Boolean get() = photos.size < maxPhotos
    val hasPhotos: Boolean get() = photos.isNotEmpty()
}

data class PhotoCategoryOption(
    val albumId: Long,
    val name: String
)

data class BatchPhotoItem(
    val id: String,
    val file: File,
    val number: Int,
    val categoryAlbumId: Long?,
    val categoryName: String?,
    val isIr: Boolean = false,
    val visualFile: File? = null
)

sealed interface BatchCaptureEvent {
    data class PhotosCommitted(val count: Int, val assemblyId: String?) : BatchCaptureEvent
    data class Error(val message: String) : BatchCaptureEvent
    object BatchCleared : BatchCaptureEvent
    object LimitReached : BatchCaptureEvent
}

class BatchCaptureViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val remoteLogger = rocketPlanApp.remoteLogger
    private val imageProcessorRepository = rocketPlanApp.imageProcessorRepository
    private val imageProcessorQueueManager = rocketPlanApp.imageProcessorQueueManager

    private val _uiState = MutableStateFlow(BatchCaptureUiState())
    val uiState: StateFlow<BatchCaptureUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<BatchCaptureEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<BatchCaptureEvent> = _events.asSharedFlow()

    private var resolvedRoom: OfflineRoomEntity? = null
    private val groupUuid = UuidUtils.generateUuidV7()

    init {
        Log.d(TAG, "BatchCaptureViewModel init: projectId=$projectId, roomId=$roomId, groupUuid=$groupUuid")
        loadRoom()
        observeCategoryAlbums()
    }

    private fun loadRoom() {
        viewModelScope.launch {
            localDataService.observeRooms(projectId).collect { rooms ->
                resolvedRoom = rooms.firstOrNull { it.roomId == roomId || it.serverId == roomId }
                resolvedRoom?.let { room ->
                    _uiState.update { it.copy(roomTitle = room.title) }
                    Log.d(TAG, "Room resolved: ${room.title} (localId=${room.roomId}, serverId=${room.serverId})")
                }
            }
        }
    }

    private fun observeCategoryAlbums() {
        viewModelScope.launch {
            localDataService.observeAlbumsForProject(projectId).collect { albums ->
                val categoryAlbums = albums
                    .filter { album ->
                        album.roomId == null && CategoryAlbums.isCategory(album.name)
                    }
                    .sortedBy { album -> CategoryAlbums.orderIndex(album.name) }
                    .map { album ->
                        PhotoCategoryOption(
                            albumId = album.albumId,
                            name = album.name
                        )
                    }

                _uiState.update { state ->
                    val currentSelection = state.selectedCategoryId
                    val resolvedSelection = when {
                        currentSelection != null && categoryAlbums.any { it.albumId == currentSelection } -> currentSelection
                        categoryAlbums.isNotEmpty() -> categoryAlbums.first().albumId
                        else -> null
                    }
                    state.copy(
                        categories = categoryAlbums,
                        selectedCategoryId = resolvedSelection
                    )
                }
            }
        }
    }

    fun selectCategory(albumId: Long) {
        _uiState.update { it.copy(selectedCategoryId = albumId) }
    }

    fun addPhoto(photoFile: File, isIr: Boolean = false, visualFile: File? = null): Boolean {
        val currentState = _uiState.value
        if (currentState.photos.size >= currentState.maxPhotos) {
            Log.w(TAG, "Cannot add photo: limit reached (${currentState.maxPhotos})")
            _events.tryEmit(BatchCaptureEvent.LimitReached)
            return false
        }

        val newPhoto = BatchPhotoItem(
            id = UuidUtils.generateUuidV7(),
            file = photoFile,
            number = currentState.photos.size + 1,
            categoryAlbumId = currentState.selectedCategoryId,
            categoryName = currentState.categories.firstOrNull { option ->
                option.albumId == currentState.selectedCategoryId
            }?.name,
            isIr = isIr,
            visualFile = visualFile
        )

        _uiState.update { state ->
            state.copy(photos = state.photos + newPhoto)
        }

        Log.d(TAG, "Photo added: ${newPhoto.number}/${currentState.maxPhotos}, visualFile=${visualFile?.name}")
        return true
    }

    fun removePhoto(photoId: String) {
        _uiState.update { state ->
            val updatedPhotos = state.photos
                .filter { it.id != photoId }
                .also { filtered ->
                    // Find the removed photo and delete its files (thermal + visual if present)
                    state.photos.find { it.id == photoId }?.let { photo ->
                        photo.file.delete()
                        photo.visualFile?.delete()
                    }
                }
                .mapIndexed { index, photo ->
                    photo.copy(number = index + 1)
                }
            state.copy(photos = updatedPhotos)
        }
        Log.d(TAG, "Photo removed: $photoId, remaining: ${_uiState.value.photos.size}")
    }

    fun clearBatch() {
        // Delete all temp files (thermal + visual)
        _uiState.value.photos.forEach { photo ->
            photo.file.delete()
            photo.visualFile?.delete()
        }
        _uiState.update { it.copy(photos = emptyList()) }
        _events.tryEmit(BatchCaptureEvent.BatchCleared)
        Log.d(TAG, "Batch cleared")
    }

    fun commitPhotos() {
        val currentState = _uiState.value
        if (currentState.photos.isEmpty()) {
            Log.w(TAG, "Cannot commit: no photos")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Try cached room first, then fetch directly if not resolved
            val room = resolvedRoom ?: run {
                Log.w(TAG, "Room not cached, fetching directly for roomId=$roomId")
                localDataService.getRoom(roomId) ?: localDataService.getRoomByServerId(roomId)
            }
            if (room == null) {
                Log.e(TAG, "Cannot commit: room not found in database (roomId=$roomId)")
                _events.tryEmit(BatchCaptureEvent.Error("Room not available"))
                return@launch
            }
            try {
                Log.d(TAG, "Committing ${currentState.photos.size} photos for room ${room.roomId}")
                val lookupRoomId = room.serverId ?: room.roomId
                Log.d(TAG, "Skipping local placeholder insert; awaiting image processor results")
                remoteLogger.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Batch commit defers local placeholders; waiting for image processor photos",
                    metadata = mapOf(
                        "project_id" to projectId.toString(),
                        "room_id" to lookupRoomId.toString(),
                        "photo_count" to currentState.photos.size.toString(),
                        "group_uuid" to groupUuid
                    )
                )

                val albumAssignments = mutableMapOf<String, MutableList<String>>()
                currentState.photos.forEach { batchPhoto ->
                    val category = batchPhoto.categoryName ?: return@forEach
                    albumAssignments.getOrPut(category) { mutableListOf() }.add(batchPhoto.file.name)
                    // Also add visual file to album if present
                    batchPhoto.visualFile?.let { visualFile ->
                        albumAssignments.getOrPut(category) { mutableListOf() }.add(visualFile.name)
                    }
                }

                // Create FileToUpload list - include both thermal and visual files
                val filesToUpload = currentState.photos.flatMap { batchPhoto ->
                    buildList {
                        add(com.example.rocketplan_android.data.model.FileToUpload(
                            uri = Uri.fromFile(batchPhoto.file),
                            filename = batchPhoto.file.name,
                            deleteOnCompletion = true
                        ))
                        // Add visual file if present
                        batchPhoto.visualFile?.let { visualFile ->
                            add(com.example.rocketplan_android.data.model.FileToUpload(
                                uri = Uri.fromFile(visualFile),
                                filename = visualFile.name,
                                deleteOnCompletion = true
                            ))
                        }
                    }
                }

                // Collect IR photo data - use visual file name if present, otherwise thermal for both
                val irPhotoData = currentState.photos
                    .filter { it.isIr }
                    .map { photo ->
                        val uuid = UuidUtils.generateUuidV7()
                        val visualFileName = photo.visualFile?.name ?: photo.file.name
                        Log.d(TAG, "Adding IR photo: thermal=${photo.file.name}, visual=$visualFileName with uuid=$uuid")
                        mapOf(uuid to com.example.rocketplan_android.data.model.IRPhotoData(
                            thermalFileName = photo.file.name,
                            visualFileName = visualFileName
                        ))
                    }
                Log.d(TAG, "IR photo count: ${irPhotoData.size} of ${currentState.photos.size} total photos")
                currentState.photos.forEach { photo ->
                    Log.d(TAG, "Photo ${photo.file.name}: isIr=${photo.isIr}, visualFile=${photo.visualFile?.name}")
                }

                // Create assembly
                // Only pass entityType/entityId when room has synced (has serverId)
                // Otherwise photos upload to project level and get associated later
                val result = imageProcessorRepository.createAssembly(
                    roomId = room.roomId,
                    projectId = projectId,
                    filesToUpload = filesToUpload,
                    templateId = "",
                    groupUuid = groupUuid,
                    albums = albumAssignments.mapValues { it.value.toList() },
                    irPhotos = irPhotoData,
                    order = emptyList(),
                    notes = emptyMap(),
                    entityType = if (room.serverId != null) "room" else null,
                    entityId = room.serverId
                )

                result.onSuccess { assemblyId ->
                    Log.d(TAG, "Assembly created: $assemblyId")

                    remoteLogger.log(
                        level = LogLevel.INFO,
                        tag = TAG,
                        message = "Batch photos committed successfully",
                        metadata = mapOf(
                            "assembly_id" to assemblyId,
                            "project_id" to projectId.toString(),
                            "room_id" to room.roomId.toString(),
                            "photo_count" to currentState.photos.size.toString(),
                            "group_uuid" to groupUuid
                        )
                    )

                    // Trigger queue processing
                    imageProcessorQueueManager.processNextQueuedAssembly()

                    _uiState.update { it.copy(photos = emptyList()) }
                    _events.emit(BatchCaptureEvent.PhotosCommitted(currentState.photos.size, assemblyId))

                }.onFailure { error ->
                    Log.e(TAG, "Failed to create assembly", error)

                    remoteLogger.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "Batch commit failed: ${error.message}",
                        metadata = mapOf(
                            "project_id" to projectId.toString(),
                            "room_id" to room.roomId.toString(),
                            "photo_count" to currentState.photos.size.toString(),
                            "error" to (error.message ?: "unknown")
                        )
                    )

                    _events.emit(BatchCaptureEvent.Error(error.message ?: "Failed to process photos"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Commit failed", e)
                _events.emit(BatchCaptureEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Log camera errors to remote logging for diagnostics.
     */
    fun logCameraError(errorType: String, errorMessage: String?, exception: Throwable? = null) {
        remoteLogger.log(
            level = LogLevel.ERROR,
            tag = TAG,
            message = "Camera error: $errorType",
            metadata = buildMap {
                put("error_type", errorType)
                put("error_message", errorMessage ?: "unknown")
                put("project_id", projectId.toString())
                put("room_id", roomId.toString())
                exception?.let {
                    put("exception_class", it.javaClass.simpleName)
                    put("exception_message", it.message ?: "no message")
                }
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Don't delete files here - they may still be needed for upload
        // Files will be deleted by ImageProcessorQueueManager after successful upload
        Log.d(TAG, "ViewModel cleared, photos preserved for upload")
    }

    companion object {
        private const val TAG = "BatchCaptureVM"

        fun provideFactory(
            application: Application,
            projectId: Long,
            roomId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(BatchCaptureViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return BatchCaptureViewModel(application, projectId, roomId) as T
            }
        }
    }
}
