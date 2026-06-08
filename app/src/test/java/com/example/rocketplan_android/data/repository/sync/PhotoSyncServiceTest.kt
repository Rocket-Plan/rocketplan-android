package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.data.repository.SyncSegment
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RP-BUG-044 regression — `syncAllRoomPhotos` must retry per-room failures (bounded) and then report
 * the terminal state truthfully. The bug was that a per-room fetch failure was swallowed: the bulk
 * segment returned `Success` with the room left at photoCount>0 / local=0 and no retry.
 *
 * `syncRoomPhotos` is a real method on the SUT, so we use `spyk` to stub it per-room while running the
 * real `syncAllRoomPhotos`. Retries use `retryDelayMs = 0` to stay fast/deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoSyncServiceTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = 5233L

    private val api = mockk<OfflineSyncApi>(relaxed = true)
    private val local = mockk<LocalDataService>(relaxed = true)
    private val checkpoint = mockk<SyncCheckpointStore>(relaxed = true)
    private val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
    private val remoteLogger = mockk<RemoteLogger>(relaxed = true)

    private fun newService(): PhotoSyncService = spyk(
        PhotoSyncService(
            api = api,
            localDataService = local,
            syncCheckpointStore = checkpoint,
            photoCacheScheduler = scheduler,
            remoteLogger = remoteLogger,
            ioDispatcher = dispatcher,
        )
    )

    private fun room(serverId: Long?): OfflineRoomEntity =
        mockk(relaxed = true) { every { this@mockk.serverId } returns serverId }

    private fun stubRooms(vararg serverIds: Long?) {
        every { local.observeRooms(projectId) } returns flowOf(serverIds.map { room(it) })
    }

    private fun ok(items: Int) = SyncResult.success(SyncSegment.ROOM_PHOTOS, items, 0)
    private fun fail() = SyncResult.failure(SyncSegment.ROOM_PHOTOS, RuntimeException("boom"), 0)

    @Test
    fun `all rooms succeed - Success with summed photos and no partial-failure log`() = runTest(dispatcher) {
        val service = newService()
        stubRooms(1L, 2L)
        coEvery { service.syncRoomPhotos(projectId, 1L, any(), any(), any()) } returns ok(3)
        coEvery { service.syncRoomPhotos(projectId, 2L, any(), any(), any()) } returns ok(5)

        val result = service.syncAllRoomPhotos(projectId, maxRoomRetries = 2, retryDelayMs = 0)

        assertThat(result.success).isTrue()
        assertThat(result.itemsSynced).isEqualTo(8)
        verify(exactly = 0) {
            remoteLogger.log(any(), any(), match { it.contains("partial failure", ignoreCase = true) }, any())
        }
    }

    @Test
    fun `room fails first then succeeds on retry - Success`() = runTest(dispatcher) {
        val service = newService()
        stubRooms(1L, 2L)
        coEvery { service.syncRoomPhotos(projectId, 1L, any(), any(), any()) } returns ok(3)
        // Flaky room: fails the first attempt, succeeds the second.
        coEvery { service.syncRoomPhotos(projectId, 2L, any(), any(), any()) } returnsMany listOf(fail(), ok(4))

        val result = service.syncAllRoomPhotos(projectId, maxRoomRetries = 2, retryDelayMs = 0)

        assertThat(result.success).isTrue()
        assertThat(result.itemsSynced).isEqualTo(7)
        // Stable room fetched once; flaky room fetched twice (initial + one retry).
        coVerify(exactly = 1) { service.syncRoomPhotos(projectId, 1L, any(), any(), any()) }
        coVerify(exactly = 2) { service.syncRoomPhotos(projectId, 2L, any(), any(), any()) }
    }

    @Test
    fun `room fails every attempt - Failure, partial progress kept, one-shot log`() = runTest(dispatcher) {
        val service = newService()
        stubRooms(1L, 2L)
        coEvery { service.syncRoomPhotos(projectId, 1L, any(), any(), any()) } returns ok(3)
        coEvery { service.syncRoomPhotos(projectId, 2L, any(), any(), any()) } returns fail()

        val result = service.syncAllRoomPhotos(projectId, maxRoomRetries = 2, retryDelayMs = 0)

        // The regression guard: today this wrongly returns Success.
        assertThat(result.success).isFalse()
        assertThat(result).isInstanceOf(SyncResult.Failure::class.java)
        assertThat(result.segment).isEqualTo(SyncSegment.ALL_ROOM_PHOTOS)
        // Partial progress from the room that did sync is preserved.
        assertThat(result.itemsSynced).isEqualTo(3)
        // Initial + maxRoomRetries attempts on the failing room.
        coVerify(exactly = 3) { service.syncRoomPhotos(projectId, 2L, any(), any(), any()) }
        // Exactly one terminal partial-failure remote log, naming the failed room.
        coVerify(exactly = 1) {
            remoteLogger.log(
                level = LogLevel.WARN,
                tag = any(),
                message = any(),
                metadata = match { it?.get("failedRoomIds") == "2" && it["failedRooms"] == "1" },
            )
        }
    }

    @Test
    fun `no rooms - Success zero, no retry, no log`() = runTest(dispatcher) {
        val service = newService()
        stubRooms() // empty

        val result = service.syncAllRoomPhotos(projectId, maxRoomRetries = 2, retryDelayMs = 0)

        assertThat(result.success).isTrue()
        assertThat(result.itemsSynced).isEqualTo(0)
        coVerify(exactly = 0) { service.syncRoomPhotos(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { remoteLogger.log(any(), any(), any(), any()) }
    }

    @Test
    fun `room with no photos (success empty) is not a failure - no retry`() = runTest(dispatcher) {
        val service = newService()
        stubRooms(1L)
        // 404 / empty room is surfaced by syncRoomPhotos as success with 0 items, not a failure.
        coEvery { service.syncRoomPhotos(projectId, 1L, any(), any(), any()) } returns ok(0)

        val result = service.syncAllRoomPhotos(projectId, maxRoomRetries = 2, retryDelayMs = 0)

        assertThat(result.success).isTrue()
        assertThat(result.itemsSynced).isEqualTo(0)
        coVerify(exactly = 1) { service.syncRoomPhotos(projectId, 1L, any(), any(), any()) }
    }
}
