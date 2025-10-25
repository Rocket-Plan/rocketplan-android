package com.example.rocketplan_android.ui.projects

import app.cash.turbine.test
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.SyncStatus
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.util.DateUtils
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDetailViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val projectId = 42L
    private val roomId = 7L

    private val roomsFlow = MutableStateFlow(
        listOf(
            OfflineRoomEntity(
                roomId = roomId,
                serverId = roomId,
                uuid = "room-$roomId",
                projectId = projectId,
                locationId = null,
                title = "Basement",
                roomType = null,
                level = "Main Level",
                squareFootage = null,
                isAccessible = true,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false,
                createdAt = now(),
                updatedAt = now(),
                lastSyncedAt = now()
            )
        )
    )

    private val photosFlow = MutableStateFlow(
        listOf(
            OfflinePhotoEntity(
                photoId = 1L,
                serverId = 1L,
                uuid = "photo-1",
                projectId = projectId,
                roomId = roomId,
                logId = null,
                moistureLogId = null,
                albumId = null,
                fileName = "photo.jpg",
                localPath = "",
                remoteUrl = "https://example.com/photo.jpg",
                thumbnailUrl = "https://example.com/photo_thumb.jpg",
                uploadStatus = "completed",
                assemblyId = null,
                tusUploadId = null,
                fileSize = 1024,
                width = 1200,
                height = 900,
                mimeType = "image/jpeg",
                capturedAt = DateUtils.parseApiDate("2025-05-01T00:00:00Z"),
                createdAt = now(),
                updatedAt = now(),
                lastSyncedAt = now(),
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false,
                cacheStatus = com.example.rocketplan_android.data.local.PhotoCacheStatus.NONE,
                cachedOriginalPath = null,
                cachedThumbnailPath = null,
                lastAccessedAt = null
            )
        )
    )

    private val notesFlow = MutableStateFlow(
        listOf(
            OfflineNoteEntity(
                noteId = 10L,
                serverId = 10L,
                uuid = "note-10",
                projectId = projectId,
                roomId = roomId,
                userId = null,
                content = "remember the leak",
                createdAt = now(),
                updatedAt = now(),
                lastSyncedAt = now(),
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
    )

    @Test
    fun `room detail emits header and photo grid`() = runTest {
        val localDataService = mockk<LocalDataService>()
        every { localDataService.observeRooms(projectId) } returns roomsFlow
        every { localDataService.observePhotosForRoom(roomId) } returns photosFlow
        every { localDataService.observeNotes(projectId) } returns notesFlow

        val application = mockk<RocketPlanApplication>()
        every { application.localDataService } returns localDataService

        val viewModel = RoomDetailViewModel(application, projectId, roomId)

        viewModel.uiState.test {
            assertThat(awaitItem()).isInstanceOf(RoomDetailUiState.Loading::class.java)
            val ready = awaitItem() as RoomDetailUiState.Ready
            assertThat(ready.header.title).isEqualTo("Basement")
            assertThat(ready.header.noteSummary).isEqualTo("1 Note")
            assertThat(ready.photos).hasSize(1)
            assertThat(ready.photos.first().thumbnailUrl).contains("photo_thumb")
            cancelAndConsumeRemainingEvents()
        }
    }

    private fun now(): Date = Date()
}
