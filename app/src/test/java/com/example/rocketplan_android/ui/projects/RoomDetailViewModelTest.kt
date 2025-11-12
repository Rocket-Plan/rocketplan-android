package com.example.rocketplan_android.ui.projects

import android.os.SystemClock
import android.util.Log
import androidx.paging.PagingData
import app.cash.turbine.test
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.w(any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
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
            assertThat(awaitItem()).isEqualTo(RoomDetailUiState.Loading)
            val ready = awaitItem() as RoomDetailUiState.Ready
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
        mockkStatic(SystemClock::class)
        val offlineSyncRepository = mockk<OfflineSyncRepository>()
        coJustRun { offlineSyncRepository.refreshRoomPhotos(any(), any()) }

        val viewModel = createViewModel(offlineSyncRepository = offlineSyncRepository)

        every { SystemClock.elapsedRealtime() } returnsMany listOf(0L, 0L, 5_000L)

        try {
            viewModel.ensureRoomPhotosFresh(force = true)
            advanceUntilIdle()

            viewModel.ensureRoomPhotosFresh()
            advanceUntilIdle()

            coVerify(exactly = 1) {
                offlineSyncRepository.refreshRoomPhotos(projectId, serverRoomId)
            }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    @Test
    fun `onLocalPhotoCaptured saves pending photo with resolved room id`() = runTest {
        val localDataService = mockk<LocalDataService>()
        val roomsFlow = MutableStateFlow(listOf(defaultRoom()))

        val viewModel = createViewModel(
            rooms = roomsFlow,
            localDataService = localDataService
        )

        val tempFile = File.createTempFile("room-detail", ".jpg").apply {
            writeText("pixels")
            deleteOnExit()
        }

        viewModel.onLocalPhotoCaptured(tempFile, mimeType = "image/jpeg", albumId = 55L)
        advanceUntilIdle()

        coVerify {
            localDataService.savePhotos(withArg { photos ->
                assertThat(photos).hasSize(1)
                val entity = photos.first()
                assertThat(entity.projectId).isEqualTo(projectId)
                assertThat(entity.roomId).isEqualTo(serverRoomId)
                assertThat(entity.albumId).isEqualTo(55L)
                assertThat(entity.syncStatus).isEqualTo(SyncStatus.PENDING)
                assertThat(entity.isDirty).isTrue()
                assertThat(entity.fileName).isEqualTo(tempFile.name)
                assertThat(entity.cachedOriginalPath).isEqualTo(tempFile.absolutePath)
                assertThat(entity.cacheStatus).isEqualTo(PhotoCacheStatus.READY)
            })
        }
    }

    private fun createViewModel(
        rooms: MutableStateFlow<List<OfflineRoomEntity>> = MutableStateFlow(listOf(defaultRoom())),
        notes: MutableStateFlow<List<OfflineNoteEntity>> = MutableStateFlow(emptyList<OfflineNoteEntity>()),
        albums: MutableStateFlow<List<OfflineAlbumEntity>> = MutableStateFlow(emptyList<OfflineAlbumEntity>()),
        photoCount: MutableStateFlow<Int> = MutableStateFlow(0),
        localDataService: LocalDataService = mockk(),
        offlineSyncRepository: OfflineSyncRepository = mockk(),
        remoteLogger: RemoteLogger = mockk(relaxed = true)
    ): RoomDetailViewModel {
        every { localDataService.observeRooms(projectId) } returns rooms
        every { localDataService.observeNotes(projectId) } returns notes
        every { localDataService.observeAlbumsForRoom(any()) } returns albums
        every { localDataService.observePhotoCountForRoom(any()) } returns photoCount
        every { localDataService.pagedPhotosForRoom(any()) } returns flowOf(PagingData.empty<OfflinePhotoEntity>())
        coJustRun { localDataService.savePhotos(any()) }
        coJustRun { offlineSyncRepository.refreshRoomPhotos(any(), any()) }

        val application = mockk<RocketPlanApplication>()
        every { application.localDataService } returns localDataService
        every { application.offlineSyncRepository } returns offlineSyncRepository
        every { application.remoteLogger } returns remoteLogger

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
