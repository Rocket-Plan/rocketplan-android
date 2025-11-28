package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val remoteLogger = app.remoteLogger

    private val _photos = MutableStateFlow<List<PhotoPageItem>>(emptyList())
    val photos: StateFlow<List<PhotoPageItem>> = _photos.asStateFlow()

    private val _currentPhotoInfo = MutableStateFlow<CurrentPhotoInfo?>(null)
    val currentPhotoInfo: StateFlow<CurrentPhotoInfo?> = _currentPhotoInfo.asStateFlow()

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
                updateCurrentPhotoInfo(0)
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
        _currentPhotoInfo.value = CurrentPhotoInfo(
            title = photo.fileName,
            subtitle = photo.capturedLabel,
            currentIndex = position,
            totalCount = photoList.size
        )
    }

    private fun OfflinePhotoEntity.toPageItem(): PhotoPageItem {
        val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
        val displayLabel = capturedAt?.let { formatter.format(it) }

        return PhotoPageItem(
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
