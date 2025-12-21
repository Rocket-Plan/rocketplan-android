package com.example.rocketplan_android.ui.projects

import android.content.res.Resources
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.util.DateUtils
import com.google.common.truth.Truth.assertThat
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
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
                cacheStatus = PhotoCacheStatus.NONE,
                cachedOriginalPath = null,
                cachedThumbnailPath = null,
                lastAccessedAt = null
            )
        )
    )

    private val photoSyncingProjectsFlow = MutableStateFlow(emptySet<Long>())
    private val projectSyncingProjectsFlow = MutableStateFlow(emptySet<Long>())
    private val syncQueueManager = mockk<SyncQueueManager>()

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

    private val albumsFlow = MutableStateFlow<List<OfflineAlbumEntity>>(emptyList())

    @Test
    fun `emits ready state with grouped rooms`() = runTest {
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeProjects() } returns projectsFlow
        every { localDataService.observeRooms(projectId) } returns roomsFlow
        every { localDataService.observePhotosForProject(projectId) } returns photosFlow
        every { localDataService.observeNotes(projectId) } returns notesFlow
        every { localDataService.observeAlbumsForProject(projectId) } returns albumsFlow
        every { localDataService.observeDamages(projectId) } returns flowOf(emptyList<OfflineDamageEntity>())
        every { localDataService.observeWorkScopes(projectId) } returns flowOf(emptyList<OfflineWorkScopeEntity>())

        val application = mockk<RocketPlanApplication>()
        val resources = mockk<Resources>(relaxed = true)
        every { resources.getIdentifier(any(), any(), any()) } returns 0
        every { application.localDataService } returns localDataService
        val offlineSyncRepository = mockk<OfflineSyncRepository>()
        coJustRun { offlineSyncRepository.syncProjectGraph(projectId) }
        every { application.offlineSyncRepository } returns offlineSyncRepository
        every { application.syncQueueManager } returns syncQueueManager
        every { application.resources } returns resources
        every { application.packageName } returns "com.example.rocketplan_android"
        every { syncQueueManager.photoSyncingProjects } returns photoSyncingProjectsFlow
        every { syncQueueManager.projectSyncingProjects } returns projectSyncingProjectsFlow
        every { syncQueueManager.prioritizeProject(projectId) } returns Unit

        val viewModel = ProjectDetailViewModel(application, projectId)

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is ProjectDetailUiState.Ready) return@repeat
        }

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(ProjectDetailUiState.Ready::class.java)
        val ready = state as ProjectDetailUiState.Ready
        assertThat(ready.header.projectTitle).isEqualTo("201 West 1st Street")
        assertThat(ready.header.noteSummary).isEqualTo("1 Note")
        assertThat(ready.levelSections).hasSize(1)
        val section = ready.levelSections.first()
        assertThat(section.levelName).isEqualTo("Main Level")
        assertThat(section.rooms).hasSize(1)
        val room = section.rooms.first()
        assertThat(room.photoCount).isEqualTo(1)
        assertThat(room.thumbnailUrl).contains("photo_thumb")

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `updates background syncing flag when queue state changes`() = runTest {
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeProjects() } returns projectsFlow
        every { localDataService.observeRooms(projectId) } returns roomsFlow
        every { localDataService.observePhotosForProject(projectId) } returns photosFlow
        every { localDataService.observeNotes(projectId) } returns notesFlow
        every { localDataService.observeAlbumsForProject(projectId) } returns albumsFlow
        every { localDataService.observeDamages(projectId) } returns flowOf(emptyList<OfflineDamageEntity>())
        every { localDataService.observeWorkScopes(projectId) } returns flowOf(emptyList<OfflineWorkScopeEntity>())

        val application = mockk<RocketPlanApplication>()
        val resources = mockk<Resources>(relaxed = true)
        every { resources.getIdentifier(any(), any(), any()) } returns 0
        every { application.localDataService } returns localDataService
        val offlineSyncRepository = mockk<OfflineSyncRepository>()
        coJustRun { offlineSyncRepository.syncProjectGraph(projectId) }
        every { application.offlineSyncRepository } returns offlineSyncRepository
        every { application.syncQueueManager } returns syncQueueManager
        every { application.resources } returns resources
        every { application.packageName } returns "com.example.rocketplan_android"
        every { syncQueueManager.photoSyncingProjects } returns photoSyncingProjectsFlow
        every { syncQueueManager.projectSyncingProjects } returns projectSyncingProjectsFlow
        every { syncQueueManager.prioritizeProject(projectId) } returns Unit

        projectSyncingProjectsFlow.value = setOf(projectId)

        val viewModel = ProjectDetailViewModel(application, projectId)

        repeat(5) {
            advanceUntilIdle()
            if (viewModel.uiState.value is ProjectDetailUiState.Ready) return@repeat
        }

        val ready = viewModel.uiState.value as ProjectDetailUiState.Ready
        assertThat(ready.isBackgroundSyncing).isTrue()

        projectSyncingProjectsFlow.value = emptySet()
        advanceUntilIdle()

        val updated = viewModel.uiState.value as ProjectDetailUiState.Ready
        assertThat(updated.isBackgroundSyncing).isFalse()

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `filterRoomScopedAlbums keeps only room albums`() {
        val projectAlbum = OfflineAlbumEntity(
            albumId = 26740L,
            projectId = projectId,
            roomId = null,
            name = "Betterments",
            albumableType = "App\\Models\\Project",
            albumableId = projectId,
            photoCount = 12,
            thumbnailUrl = "https://example.com/betterments_thumb.jpg",
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            createdAt = now(),
            updatedAt = now(),
            lastSyncedAt = now()
        )

        val roomAlbum = OfflineAlbumEntity(
            albumId = 300L,
            projectId = projectId,
            roomId = 100L,
            name = "Kitchen",
            albumableType = "App\\Models\\Room",
            albumableId = 100L,
            photoCount = 4,
            thumbnailUrl = "https://example.com/kitchen_thumb.jpg",
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            createdAt = now(),
            updatedAt = now(),
            lastSyncedAt = now()
        )

        val filtered = listOf(projectAlbum, roomAlbum).filterRoomScopedAlbums()
        assertThat(filtered).containsExactly(roomAlbum)
    }

    private fun now(): Date = Date()
}

private fun List<OfflineAlbumEntity>.filterRoomScopedAlbums(): List<OfflineAlbumEntity> {
    return filter { album ->
        val isRoomScopedById = album.roomId != null
        val isRoomScopedByType = album.albumableType?.contains("Room") == true
        isRoomScopedById || isRoomScopedByType
    }
}
