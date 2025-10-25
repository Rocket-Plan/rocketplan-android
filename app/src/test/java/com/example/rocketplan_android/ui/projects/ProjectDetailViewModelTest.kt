package com.example.rocketplan_android.ui.projects

import app.cash.turbine.test
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.SyncStatus
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.util.DateUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val projectId = 42L

    private val projectsFlow = MutableStateFlow(
        listOf(
            OfflineProjectEntity(
                projectId = projectId,
                serverId = projectId,
                uuid = "uuid-$projectId",
                title = "201 West 1st Street",
                projectNumber = "RP-25-1001",
                uid = null,
                alias = null,
                addressLine1 = "201 West 1st Street",
                addressLine2 = null,
                status = "wip",
                propertyType = null,
                companyId = 1L,
                propertyId = 10L,
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

    private val roomsFlow = MutableStateFlow(
        listOf(
            OfflineRoomEntity(
                roomId = 100L,
                serverId = 100L,
                uuid = "room-100",
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
                photoId = 500L,
                serverId = 500L,
                uuid = "photo-500",
                projectId = projectId,
                roomId = 100L,
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
                width = 1920,
                height = 1080,
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
                noteId = 1L,
                serverId = 1L,
                uuid = "note-1",
                projectId = projectId,
                roomId = 100L,
                userId = null,
                content = "Great room",
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
    fun `emits ready state with grouped rooms`() = runTest {
        val localDataService = mockk<LocalDataService>()
        every { localDataService.observeProjects() } returns projectsFlow
        every { localDataService.observeRooms(projectId) } returns roomsFlow
        every { localDataService.observePhotosForProject(projectId) } returns photosFlow
        every { localDataService.observeNotes(projectId) } returns notesFlow

        val application = mockk<RocketPlanApplication>()
        every { application.localDataService } returns localDataService

        val viewModel = ProjectDetailViewModel(application, projectId)

        viewModel.uiState.test {
            assertThat(awaitItem()).isInstanceOf(ProjectDetailUiState.Loading::class.java)
            val ready = awaitItem() as ProjectDetailUiState.Ready
            assertThat(ready.header.projectTitle).isEqualTo("201 West 1st Street")
            assertThat(ready.header.noteSummary).isEqualTo("1 Note")
            assertThat(ready.levelSections).hasSize(1)
            val section = ready.levelSections.first()
            assertThat(section.levelName).isEqualTo("Main Level")
            assertThat(section.rooms).hasSize(1)
            val room = section.rooms.first()
            assertThat(room.photoCount).isEqualTo(1)
            assertThat(room.thumbnailUrl).contains("photo_thumb")
            cancelAndConsumeRemainingEvents()
        }
    }

    private fun now(): Date = Date()
}
