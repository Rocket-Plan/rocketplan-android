package com.example.rocketplan_android.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoomDetailViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService

    private val _uiState = MutableStateFlow<RoomDetailUiState>(RoomDetailUiState.Loading)
    val uiState: StateFlow<RoomDetailUiState> = _uiState

    private val _selectedTab = MutableStateFlow(RoomDetailTab.PHOTOS)
    val selectedTab: StateFlow<RoomDetailTab> = _selectedTab

    init {
        viewModelScope.launch {
            combine(
                localDataService.observeRooms(projectId),
                localDataService.observePhotosForRoom(roomId),
                localDataService.observeNotes(projectId),
                localDataService.observeAlbumsForRoom(roomId)
            ) { rooms, photos, notes, albums ->
                val room = rooms.firstOrNull { it.roomId == roomId }
                if (room == null) {
                    RoomDetailUiState.Loading
                } else {
                    val roomNotes = notes.filter { it.roomId == roomId }
                    RoomDetailUiState.Ready(
                        header = room.toHeader(roomNotes),
                        photos = photos.toPhotoItems(),
                        albums = albums.toAlbumItems()
                    )
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun selectTab(tab: RoomDetailTab) {
        if (_selectedTab.value != tab) {
            _selectedTab.value = tab
        }
    }

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

    private fun List<OfflinePhotoEntity>.toPhotoItems(): List<RoomPhotoItem> {
        val formatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)
        return this
            .sortedByDescending { it.capturedAt ?: it.updatedAt ?: Date(0) }
            .map { photo ->
                RoomPhotoItem(
                    id = photo.photoId,
                    imageUrl = photo.remoteUrl ?: "",
                    thumbnailUrl = photo.thumbnailUrl ?: photo.remoteUrl ?: "",
                    capturedOn = photo.capturedAt?.let { formatter.format(it) }
                )
            }
            .filter { it.imageUrl.isNotBlank() }
    }

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

    companion object {
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
        val photos: List<RoomPhotoItem>,
        val albums: List<RoomAlbumItem> = emptyList()
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

enum class RoomDetailTab {
    PHOTOS, DAMAGES
}
