package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class PhotoViewerViewModel(
    application: Application,
    private val photoIds: List<Long>
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val localDataService = app.localDataService
    private val offlineSyncRepository = app.offlineSyncRepository
    private val remoteLogger = app.remoteLogger

    private val _photos = MutableStateFlow<List<PhotoPageItem>>(emptyList())
    val photos: StateFlow<List<PhotoPageItem>> = _photos.asStateFlow()

    private val _currentPhotoInfo = MutableStateFlow<CurrentPhotoInfo?>(null)
    val currentPhotoInfo: StateFlow<CurrentPhotoInfo?> = _currentPhotoInfo.asStateFlow()

    private val _currentPhoto = MutableStateFlow<PhotoPageItem?>(null)
    val currentPhoto: StateFlow<PhotoPageItem?> = _currentPhoto.asStateFlow()

    private val _isSavingNote = MutableStateFlow(false)
    val isSavingNote: StateFlow<Boolean> = _isSavingNote.asStateFlow()

    private val _events = MutableSharedFlow<PhotoViewerEvent>()
    val events = _events.asSharedFlow()

    private var currentIndex = 0

    init {
        Log.d(TAG, "📸 init(photoIds=${photoIds.size} photos)")
        loadPhotos()
    }

    private fun loadPhotos() {
        viewModelScope.launch {
            val photoEntities = mutableListOf<OfflinePhotoEntity>()

            // Load each photo by ID
            for (photoId in photoIds) {
                try {
                    val entity = localDataService.getPhoto(photoId)
                    if (entity != null) {
                        photoEntities.add(entity)
                    } else {
                        Log.w(TAG, "Photo not found: $photoId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading photo $photoId", e)
                }
            }

            val items = photoEntities.map { it.toPageItem() }
            _photos.value = items
            Log.d(TAG, "📦 Loaded ${items.size} photos")

            // Update info for initial photo
            if (items.isNotEmpty()) {
                val targetIndex = currentIndex.coerceIn(0, items.lastIndex)
                updateCurrentPhotoInfo(targetIndex)
            } else {
                _currentPhoto.value = null
                _currentPhotoInfo.value = null
            }
        }
    }

    fun onPageSelected(position: Int) {
        currentIndex = position
        updateCurrentPhotoInfo(position)
    }

    private fun updateCurrentPhotoInfo(position: Int) {
        val photoList = _photos.value
        if (position < 0 || position >= photoList.size) return

        val photo = photoList[position]
        _currentPhoto.value = photo
        _currentPhotoInfo.value = CurrentPhotoInfo(
            title = photo.fileName,
            subtitle = photo.capturedLabel,
            currentIndex = position,
            totalCount = photoList.size
        )
    }

    fun addNoteForCurrentPhoto(content: String) {
        val photo = _currentPhoto.value ?: return
        viewModelScope.launch {
            _isSavingNote.value = true
            runCatching {
                offlineSyncRepository.createNote(
                    projectId = photo.projectId,
                    content = content,
                    roomId = photo.roomId,
                    categoryId = PHOTO_NOTE_CATEGORY_ID,
                    photoId = photo.photoId
                )
            }.onSuccess { note ->
                _events.emit(PhotoViewerEvent.NoteSaved(note))
            }.onFailure { error ->
                reportError("Failed to save photo note", error)
                _events.emit(PhotoViewerEvent.Error("Failed to save note"))
            }
            _isSavingNote.value = false
        }
    }

    private fun OfflinePhotoEntity.toPageItem(): PhotoPageItem {
        val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
        val displayLabel = capturedAt?.let { formatter.format(it) }

        return PhotoPageItem(
            projectId = projectId,
            roomId = roomId,
            photoId = photoId,
            fileName = fileName.takeIf { it.isNotBlank() } ?: "Photo $photoId",
            capturedLabel = displayLabel,
            localPath = localPath.takeIf { it.isNotBlank() && File(it).exists() },
            cachedOriginalPath = cachedOriginalPath?.takeIf { it.isNotBlank() && File(it).exists() },
            cachedThumbnailPath = cachedThumbnailPath?.takeIf { it.isNotBlank() && File(it).exists() },
            remoteUrl = remoteUrl,
            thumbnailUrl = thumbnailUrl
        )
    }

    fun reportError(message: String, throwable: Throwable? = null) {
        remoteLogger.log(
            level = LogLevel.ERROR,
            tag = TAG,
            message = message,
            metadata = mapOf("photoIds" to photoIds.joinToString(","))
        )
        throwable?.let { Log.e(TAG, message, it) }
    }

    companion object {
        private const val TAG = "PhotoViewerVM"
        private const val PHOTO_NOTE_CATEGORY_ID = 2L

        fun provideFactory(application: Application, photoIds: List<Long>): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PhotoViewerViewModel::class.java)) {
                        "Unknown ViewModel class"
                    }
                    return PhotoViewerViewModel(application, photoIds) as T
                }
            }
    }
}

data class PhotoPageItem(
    val projectId: Long,
    val roomId: Long?,
    val photoId: Long,
    val fileName: String,
    val capturedLabel: String?,
    val localPath: String?,
    val cachedOriginalPath: String?,
    val cachedThumbnailPath: String?,
    val remoteUrl: String?,
    val thumbnailUrl: String?
)

data class CurrentPhotoInfo(
    val title: String,
    val subtitle: String?,
    val currentIndex: Int,
    val totalCount: Int
)

sealed class PhotoViewerEvent {
    data class NoteSaved(val note: OfflineNoteEntity) : PhotoViewerEvent()
    data class Error(val message: String) : PhotoViewerEvent()
}
