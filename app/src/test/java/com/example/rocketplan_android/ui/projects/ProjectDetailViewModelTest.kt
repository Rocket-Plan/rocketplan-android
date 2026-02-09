package com.example.rocketplan_android.ui.projects

import android.content.res.Resources
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.network.SyncNetworkMonitor
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.util.DateUtils
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
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

    /** Debounce window in the VM is 200ms; we advance past it. */
    private val DEBOUNCE_MS = 250L

    private fun makeProject(
        serverId: Long? = projectId,
        propertyId: Long? = 10L
    ) = OfflineProjectEntity(
        projectId = projectId,
        serverId = serverId,
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
        propertyId = propertyId,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = now(),
        updatedAt = now(),
        lastSyncedAt = now()
    )

    private fun makeRoom() = OfflineRoomEntity(
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

    private fun makePhoto() = OfflinePhotoEntity(
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

    private fun makeLocation() = OfflineLocationEntity(
        locationId = 200L,
        serverId = 200L,
        uuid = "loc-200",
        projectId = projectId,
        title = "Main Level",
        type = "level",
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = now(),
        updatedAt = now(),
        lastSyncedAt = now()
    )

    private fun makeNote() = OfflineNoteEntity(
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

    private fun buildVm(
        projectsFlow: MutableStateFlow<List<OfflineProjectEntity>> = MutableStateFlow(listOf(makeProject())),
        roomsFlow: MutableStateFlow<List<OfflineRoomEntity>> = MutableStateFlow(listOf(makeRoom())),
        photosFlow: MutableStateFlow<List<OfflinePhotoEntity>> = MutableStateFlow(listOf(makePhoto())),
        notesFlow: MutableStateFlow<List<OfflineNoteEntity>> = MutableStateFlow(listOf(makeNote())),
        albumsFlow: MutableStateFlow<List<OfflineAlbumEntity>> = MutableStateFlow(emptyList()),
        locationsFlow: MutableStateFlow<List<OfflineLocationEntity>> = MutableStateFlow(emptyList()),
        projectSyncingFlow: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet()),
        photoSyncingFlow: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet()),
        isOnlineFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
        cachedLocations: List<OfflineLocationEntity> = emptyList()
    ): ProjectDetailViewModel {
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeProjects() } returns projectsFlow
        every { localDataService.observeRooms(projectId) } returns roomsFlow
        every { localDataService.observePhotosForProject(projectId) } returns photosFlow
        every { localDataService.observeNotes(projectId) } returns notesFlow
        every { localDataService.observeAlbumsForProject(projectId) } returns albumsFlow
        every { localDataService.observeLocations(projectId) } returns locationsFlow
        every { localDataService.observeDamages(projectId) } returns flowOf(emptyList<OfflineDamageEntity>())
        every { localDataService.observeWorkScopes(projectId) } returns flowOf(emptyList<OfflineWorkScopeEntity>())
        coEvery { localDataService.getLocations(projectId) } returns cachedLocations

        val application = mockk<RocketPlanApplication>(relaxed = true)
        val resources = mockk<Resources>(relaxed = true)
        every { resources.getIdentifier(any(), any(), any()) } returns 0
        every { application.localDataService } returns localDataService
        val offlineSyncRepository = mockk<OfflineSyncRepository>(relaxed = true)
        every { application.offlineSyncRepository } returns offlineSyncRepository
        val syncQueueManager = mockk<SyncQueueManager>()
        every { syncQueueManager.photoSyncingProjects } returns photoSyncingFlow
        every { syncQueueManager.projectSyncingProjects } returns projectSyncingFlow
        every { syncQueueManager.projectEssentialsFailed } returns MutableStateFlow(emptySet())
        every { syncQueueManager.prioritizeProject(projectId) } returns Unit
        every { application.syncQueueManager } returns syncQueueManager
        val syncNetworkMonitor = mockk<SyncNetworkMonitor>()
        every { syncNetworkMonitor.isOnline } returns isOnlineFlow
        every { application.syncNetworkMonitor } returns syncNetworkMonitor
        every { application.resources } returns resources
        every { application.packageName } returns "com.example.rocketplan_android"
        val imageProcessorDao = mockk<ImageProcessorDao>()
        every { imageProcessorDao.observeProcessingProgressByProject(projectId) } returns flowOf(emptyList())
        every { application.imageProcessorDao } returns imageProcessorDao
        val remoteLogger = mockk<RemoteLogger>(relaxed = true)
        every { application.remoteLogger } returns remoteLogger

        return ProjectDetailViewModel(application, projectId)
    }

    /** Wait for the VM to process its first real emission (past debounce). */
    private suspend fun ProjectDetailViewModel.awaitReady() {
        screenState.first { it.ui is ProjectDetailUiState.Ready }
    }

    @Test
    fun `emits ready state with grouped rooms`() = runTest {
        val locationsFlow = MutableStateFlow(listOf(makeLocation()))
        val viewModel = buildVm(
            locationsFlow = locationsFlow,
            cachedLocations = listOf(makeLocation())
        )

        viewModel.awaitReady()
        val ready = viewModel.screenState.value.ui as ProjectDetailUiState.Ready
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
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val locationsFlow = MutableStateFlow(listOf(makeLocation()))
        val viewModel = buildVm(
            locationsFlow = locationsFlow,
            projectSyncingFlow = projectSyncingFlow,
            cachedLocations = listOf(makeLocation())
        )

        viewModel.awaitReady()
        val ready = viewModel.screenState.value.ui as ProjectDetailUiState.Ready
        assertThat(ready.isBackgroundSyncing).isTrue()

        projectSyncingFlow.value = emptySet()
        advanceTimeBy(DEBOUNCE_MS)
        advanceUntilIdle()

        val updated = viewModel.screenState.value.ui as ProjectDetailUiState.Ready
        assertThat(updated.isBackgroundSyncing).isFalse()

        viewModel.viewModelScope.cancel()
    }

    // -- Sync blocking tests --

    @Test
    fun `fresh project with syncing emits blocking true`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val roomsFlow = MutableStateFlow<List<OfflineRoomEntity>>(emptyList())
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val photosFlow = MutableStateFlow<List<OfflinePhotoEntity>>(emptyList())
        val viewModel = buildVm(
            roomsFlow = roomsFlow,
            locationsFlow = locationsFlow,
            photosFlow = photosFlow,
            projectSyncingFlow = projectSyncingFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when essentials arrive`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val roomsFlow = MutableStateFlow<List<OfflineRoomEntity>>(emptyList())
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val photosFlow = MutableStateFlow<List<OfflinePhotoEntity>>(emptyList())
        val viewModel = buildVm(
            roomsFlow = roomsFlow,
            locationsFlow = locationsFlow,
            photosFlow = photosFlow,
            projectSyncingFlow = projectSyncingFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        locationsFlow.value = listOf(makeLocation())
        advanceTimeBy(DEBOUNCE_MS)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when sawSyncing then sync ends with no data`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val roomsFlow = MutableStateFlow<List<OfflineRoomEntity>>(emptyList())
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val photosFlow = MutableStateFlow<List<OfflinePhotoEntity>>(emptyList())
        val viewModel = buildVm(
            roomsFlow = roomsFlow,
            locationsFlow = locationsFlow,
            photosFlow = photosFlow,
            projectSyncingFlow = projectSyncingFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        projectSyncingFlow.value = emptySet()
        advanceTimeBy(DEBOUNCE_MS)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks on timeout`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val roomsFlow = MutableStateFlow<List<OfflineRoomEntity>>(emptyList())
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val photosFlow = MutableStateFlow<List<OfflinePhotoEntity>>(emptyList())
        val viewModel = buildVm(
            roomsFlow = roomsFlow,
            locationsFlow = locationsFlow,
            photosFlow = photosFlow,
            projectSyncingFlow = projectSyncingFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        advanceTimeBy(ProjectDetailViewModel.SYNC_TIMEOUT_MS + DEBOUNCE_MS)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when offline`() = runTest {
        val isOnlineFlow = MutableStateFlow(true)
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val roomsFlow = MutableStateFlow<List<OfflineRoomEntity>>(emptyList())
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val photosFlow = MutableStateFlow<List<OfflinePhotoEntity>>(emptyList())
        val viewModel = buildVm(
            roomsFlow = roomsFlow,
            locationsFlow = locationsFlow,
            photosFlow = photosFlow,
            projectSyncingFlow = projectSyncingFlow,
            isOnlineFlow = isOnlineFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        isOnlineFlow.value = false
        advanceTimeBy(DEBOUNCE_MS)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when no serverId`() = runTest {
        val projectsFlow = MutableStateFlow(listOf(makeProject(serverId = null)))
        val roomsFlow = MutableStateFlow<List<OfflineRoomEntity>>(emptyList())
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val photosFlow = MutableStateFlow<List<OfflinePhotoEntity>>(emptyList())
        val viewModel = buildVm(
            projectsFlow = projectsFlow,
            roomsFlow = roomsFlow,
            locationsFlow = locationsFlow,
            photosFlow = photosFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cached project with syncing never blocks`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val locationsFlow = MutableStateFlow(listOf(makeLocation()))
        val viewModel = buildVm(
            locationsFlow = locationsFlow,
            projectSyncingFlow = projectSyncingFlow,
            cachedLocations = listOf(makeLocation())
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `cached project while offline never blocks`() = runTest {
        val isOnlineFlow = MutableStateFlow(false)
        val locationsFlow = MutableStateFlow(listOf(makeLocation()))
        val viewModel = buildVm(
            locationsFlow = locationsFlow,
            isOnlineFlow = isOnlineFlow,
            cachedLocations = listOf(makeLocation())
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `offline escape then online and sync resumes does not re-block`() = runTest {
        val isOnlineFlow = MutableStateFlow(true)
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val roomsFlow = MutableStateFlow<List<OfflineRoomEntity>>(emptyList())
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val photosFlow = MutableStateFlow<List<OfflinePhotoEntity>>(emptyList())
        val viewModel = buildVm(
            roomsFlow = roomsFlow,
            locationsFlow = locationsFlow,
            photosFlow = photosFlow,
            projectSyncingFlow = projectSyncingFlow,
            isOnlineFlow = isOnlineFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        // Go offline -> escape
        isOnlineFlow.value = false
        advanceTimeBy(DEBOUNCE_MS)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()

        // Come back online and syncing resumes -> stays unblocked (sticky)
        isOnlineFlow.value = true
        projectSyncingFlow.value = setOf(projectId)
        advanceTimeBy(DEBOUNCE_MS)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    private fun now(): Date = Date()
}
