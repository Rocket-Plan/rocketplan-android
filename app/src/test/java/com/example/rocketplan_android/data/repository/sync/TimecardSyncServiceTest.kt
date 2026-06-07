package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineTimecardEntity
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.TimecardDto
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RP-BUG-039: timecard down-sync. syncTimecards must pull the project's timecards and persist them
 * with serverId reconciliation enabled (so an offline-created timecard that synced is not duplicated).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimecardSyncServiceTest {

    private val api = mockk<OfflineSyncApi>()
    private val localDataService = mockk<LocalDataService>(relaxed = true)

    private fun service() = TimecardSyncService(
        api = api,
        localDataService = localDataService,
        syncQueueEnqueuer = { mockk(relaxed = true) },
        logLocalDeletion = { _, _, _ -> },
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun timecardDto(id: Long) = TimecardDto(
        id = id,
        userId = 5L,
        projectId = 100L,
        timeIn = "2026-06-07T08:00:00.000000Z",
    )

    @Test
    fun `syncTimecards pulls project timecards and persists with serverId reconcile`() = runTest {
        coEvery { api.getTimecards(100L) } returns PaginatedResponse(data = listOf(timecardDto(900L)))
        val saved = slot<List<OfflineTimecardEntity>>()
        coEvery { localDataService.saveTimecards(capture(saved), reconcileByServerId = true) } returns Unit

        val result = service().syncTimecards(100L)

        assertThat(result.isSuccess).isTrue()
        // Reconcile MUST be enabled — a plain upsert would duplicate offline-created timecards (RP-BUG-038 class).
        coVerify { localDataService.saveTimecards(any(), reconcileByServerId = true) }
        coVerify(exactly = 0) { localDataService.saveTimecards(any(), reconcileByServerId = false) }
        assertThat(saved.captured).hasSize(1)
        assertThat(saved.captured.first().serverId).isEqualTo(900L)
    }

    @Test
    fun `syncTimecards returns failure on API error`() = runTest {
        coEvery { api.getTimecards(any()) } throws RuntimeException("boom")

        val result = service().syncTimecards(100L)

        assertThat(result.isFailure).isTrue()
    }
}
