package com.example.rocketplan_android.ui.projects

import android.os.SystemClock
import androidx.paging.PagingData
import app.cash.turbine.test
import com.example.rocketplan_android.RocketPlanApplication
import android.content.res.Resources
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomPhotoSnapshotEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.ImageProcessorRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.data.repository.SyncSegment
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.ui.projects.RoomDetailEvent
import com.google.common.truth.Truth.assertThat
import io.mockk.coJustRun
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.runs
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDetailViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val projectId = 42L
    private val roomId = 7L
    private val serverRoomId = 707L

    @Before
    fun setUp() {
        // No-op: android.util.Log is stubbed in unit tests.
    }

    @After
    fun tearDown() {
    }

    @Test
    fun `ui state emits ready content once room resolves`() = runTest {
        val roomsFlow = MutableStateFlow(listOf(defaultRoom()))
        val notesFlow = MutableStateFlow(listOf(defaultNote()))
        val albumsFlow = MutableStateFlow(listOf(defaultAlbum(name = "Progress", photoCount = 2)))
        val photoCountFlow = MutableStateFlow(3)

        val viewModel = createViewModel(
            rooms = roomsFlow,
            notes = notesFlow,
            albums = albumsFlow,
            photoCount = photoCountFlow
        )

        viewModel.uiState.test {
            val first = awaitItem()
            val ready = if (first is RoomDetailUiState.Ready) {
                first
            } else {
                awaitItem() as RoomDetailUiState.Ready
            }
            assertThat(ready.header.title).isEqualTo("Basement")
            assertThat(ready.header.noteSummary).isEqualTo("1 Note")
            assertThat(ready.albums).hasSize(1)
            assertThat(ready.albums.first().name).isEqualTo("Progress")
            assertThat(ready.photoCount).isEqualTo(3)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `selectedTab only emits when value actually changes`() = runTest {
        val viewModel = createViewModel()

        viewModel.selectedTab.test {
            assertThat(awaitItem()).isEqualTo(RoomDetailTab.PHOTOS)

            viewModel.selectTab(RoomDetailTab.PHOTOS)
            expectNoEvents()

            viewModel.selectTab(RoomDetailTab.DAMAGES)
            assertThat(awaitItem()).isEqualTo(RoomDetailTab.DAMAGES)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `ensureRoomPhotosFresh refreshes once and throttles subsequent calls`() = runTest {
        val offlineSyncRepository = mockk<OfflineSyncRepository>()
        coEvery { offlineSyncRepository.syncRoomPhotos(any(), any(), any(), any(), any()) } returns
            SyncResult.success(SyncSegment.ROOM_PHOTOS, 0, 0)

        val viewModel = createViewModel(offlineSyncRepository = offlineSyncRepository)
        // Wait for any init-triggered syncs to complete before clearing mock counts
        advanceUntilIdle()
        clearMocks(offlineSyncRepository, answers = false)

        SystemClock.elapsedRealtimeValue = 0L
        viewModel.ensureRoomPhotosFresh(force = true)
        advanceUntilIdle()

        SystemClock.elapsedRealtimeValue = 0L
        viewModel.ensureRoomPhotosFresh()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            offlineSyncRepository.syncRoomPhotos(projectId, serverRoomId, any(), any(), any())
        }
    }

    @Test
    fun `onLocalPhotoCaptured defers persistence until image processor completes`() = runTest {
        val localDataService = mockk<LocalDataService>()
        val remoteLogger = mockk<RemoteLogger>(relaxed = true)
        val roomsFlow = MutableStateFlow(listOf(defaultRoom()))

        val viewModel = createViewModel(
            rooms = roomsFlow,
            localDataService = localDataService,
            remoteLogger = remoteLogger
        )

        val tempFile = File.createTempFile("room-detail", ".jpg").apply {
            writeText("pixels")
            deleteOnExit()
        }

        viewModel.events.test {
            viewModel.onLocalPhotoCaptured(tempFile, mimeType = "image/jpeg", albumId = 55L)
            advanceUntilIdle()

            val error = awaitItem() as RoomDetailEvent.Error
            assertThat(error.message).contains("after processing finishes")
            cancelAndConsumeRemainingEvents()
        }

        coVerify(exactly = 0) { localDataService.savePhotos(any()) }
        verify {
            remoteLogger.log(
                level = com.example.rocketplan_android.logging.LogLevel.WARN,
                tag = "RoomDetailVM",
                message = match { it.contains("No local placeholder saved") },
                metadata = match { it["fileName"] == tempFile.name }
            )
        }
    }

    private fun createViewModel(
        rooms: MutableStateFlow<List<OfflineRoomEntity>> = MutableStateFlow(listOf(defaultRoom())),
        notes: MutableStateFlow<List<OfflineNoteEntity>> = MutableStateFlow(emptyList<OfflineNoteEntity>()),
        albums: MutableStateFlow<List<OfflineAlbumEntity>> = MutableStateFlow(emptyList<OfflineAlbumEntity>()),
        photoCount: MutableStateFlow<Int> = MutableStateFlow(0),
        localDataService: LocalDataService = mockk(),
        offlineSyncRepository: OfflineSyncRepository = mockk(),
        remoteLogger: RemoteLogger = mockk(relaxed = true),
        imageProcessorRepository: ImageProcessorRepository = mockk(),
        imageProcessorQueueManager: ImageProcessorQueueManager = mockk(),
        authRepository: AuthRepository = mockk(relaxed = true)
    ): RoomDetailViewModel {
        every { localDataService.observeRooms(projectId) } returns rooms
        every { localDataService.observeNotes(projectId) } returns notes
        every { localDataService.observeWorkScopes(projectId) } returns flowOf(emptyList<OfflineWorkScopeEntity>())
        every { localDataService.observeDamages(projectId) } returns flowOf(emptyList<OfflineDamageEntity>())
        every { localDataService.observeAlbumsForRoom(any()) } returns albums
        every { localDataService.observePhotoCountForRoom(any()) } returns photoCount
        every { localDataService.pagedPhotoSnapshotsForRoom(any()) } returns flowOf(PagingData.empty<OfflineRoomPhotoSnapshotEntity>())
        coJustRun { localDataService.clearRoomPhotoSnapshot(any()) }
        coJustRun { localDataService.refreshRoomPhotoSnapshot(any()) }
        coJustRun { localDataService.savePhotos(any()) }
        coEvery { offlineSyncRepository.syncRoomPhotos(any(), any(), any(), any(), any()) } returns
            SyncResult.success(SyncSegment.ROOM_PHOTOS, 0, 0)
        coEvery { offlineSyncRepository.syncRoomWorkScopes(any(), any()) } returns 0
        coEvery { offlineSyncRepository.syncRoomDamages(any(), any()) } returns 0
        every { imageProcessorRepository.observeAssembliesByRoom(any()) } returns flowOf(emptyList())
        every { imageProcessorRepository.observePhotosByAssemblyLocalId(any()) } returns flowOf(emptyList())
        every { imageProcessorQueueManager.reconcileProcessingAssemblies(any()) } just runs

        val application = mockk<RocketPlanApplication>()
        val resources = mockk<Resources>(relaxed = true)
        every { resources.getIdentifier(any(), any(), any()) } returns 0
        every { application.localDataService } returns localDataService
        every { application.offlineSyncRepository } returns offlineSyncRepository
        every { application.remoteLogger } returns remoteLogger
        every { application.imageProcessorRepository } returns imageProcessorRepository
        every { application.imageProcessorQueueManager } returns imageProcessorQueueManager
        every { application.authRepository } returns authRepository
        every { application.resources } returns resources
        every { application.packageName } returns "com.example.rocketplan_android"

        return RoomDetailViewModel(application, projectId, roomId)
    }

    private fun defaultRoom(
        localId: Long = roomId,
        serverId: Long? = serverRoomId,
        title: String = "Basement"
    ): OfflineRoomEntity =
        OfflineRoomEntity(
            roomId = localId,
            serverId = serverId,
            uuid = "room-$localId",
            projectId = projectId,
            title = title,
            level = "Main Level",
            isAccessible = true,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            isDirty = false,
            isDeleted = false,
            createdAt = now(),
            updatedAt = now(),
            lastSyncedAt = now()
        )

    private fun defaultNote(roomId: Long? = this.roomId): OfflineNoteEntity =
        OfflineNoteEntity(
            noteId = 1,
            serverId = 10L,
            uuid = "note-10",
            projectId = projectId,
            roomId = roomId,
            content = "remember the leak",
            createdAt = now(),
            updatedAt = now(),
            lastSyncedAt = now(),
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1
        )

    private fun defaultAlbum(
        albumId: Long = 25L,
        name: String = "Album",
        photoCount: Int = 0
    ): OfflineAlbumEntity =
        OfflineAlbumEntity(
            albumId = albumId,
            projectId = projectId,
            roomId = serverRoomId,
            name = name,
            photoCount = photoCount,
            createdAt = now(),
            updatedAt = now(),
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1
        )

    private fun now(): Date = Date()
}
