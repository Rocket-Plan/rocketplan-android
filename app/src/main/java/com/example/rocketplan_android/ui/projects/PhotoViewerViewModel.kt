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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class PhotoViewerViewModel(
    application: Application,
    private val photoId: Long
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val localDataService = app.localDataService
    private val remoteLogger = app.remoteLogger

    private val _uiState = MutableStateFlow<PhotoViewerUiState>(PhotoViewerUiState.Loading)
    val uiState: StateFlow<PhotoViewerUiState> = _uiState

    init {
        Log.d(TAG, "📸 init(photoId=$photoId)")
        viewModelScope.launch {
            localDataService.observePhoto(photoId).collectLatest { entity ->
                if (entity == null) {
                    _uiState.value = PhotoViewerUiState.Error("Photo not found")
                    return@collectLatest
                }
                _uiState.value = PhotoViewerUiState.Ready(entity.toContent())
            }
        }
    }

    private fun OfflinePhotoEntity.toContent(): PhotoViewerContent {
        val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
        val localOriginal = cachedOriginalPath?.takeIf { it.isNotBlank() && File(it).exists() }
        val localThumb = cachedThumbnailPath?.takeIf { it.isNotBlank() && File(it).exists() }
        val displayLabel = capturedAt?.let { formatter.format(it) }
        val content = PhotoViewerContent(
            photoId = photoId,
            title = fileName.takeIf { it.isNotBlank() } ?: "Photo $photoId",
            capturedLabel = displayLabel,
            localOriginalPath = localOriginal,
            localThumbnailPath = localThumb,
            remoteUrl = remoteUrl,
            thumbnailUrl = thumbnailUrl
        )
        Log.d(TAG, "📦 content resolved: $content")
        return content
    }

    fun reportError(message: String, throwable: Throwable? = null) {
        remoteLogger.log(
            level = LogLevel.ERROR,
            tag = TAG,
            message = message,
            metadata = mapOf("photoId" to photoId.toString())
        )
        throwable?.let { Log.e(TAG, message, it) }
    }

    companion object {
        private const val TAG = "PhotoViewerVM"

        fun provideFactory(application: Application, photoId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PhotoViewerViewModel::class.java)) {
                        "Unknown ViewModel class"
                    }
                    return PhotoViewerViewModel(application, photoId) as T
                }
            }
    }
}

sealed class PhotoViewerUiState {
    object Loading : PhotoViewerUiState()
    data class Ready(val content: PhotoViewerContent) : PhotoViewerUiState()
    data class Error(val message: String) : PhotoViewerUiState()
}

data class PhotoViewerContent(
    val photoId: Long,
    val title: String,
    val capturedLabel: String?,
    val localOriginalPath: String?,
    val localThumbnailPath: String?,
    val remoteUrl: String?,
    val thumbnailUrl: String?
)
