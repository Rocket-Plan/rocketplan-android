package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * RP-BUG-035: syncRoomWorkScopes must merge locally-pending work-scope creates with the
 * API fetch so offline-authored scopes are not lost on refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkScopeSyncServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()

    private fun service() = WorkScopeSyncService(api, localDataService, dispatcher)

    private fun workScopeDto(id: Long) = WorkScopeDto(
        id = id,
        uuid = "ws-server-$id",
        projectId = 100L,
        roomId = 400L,
        name = "Server Scope $id",
        description = null,
        createdAt = "2026-01-30T12:00:00.000000Z",
        updatedAt = "2026-01-30T12:00:00.000000Z"
    )

    private fun pendingScope(serverId: Long? = null) = OfflineWorkScopeEntity(
        workScopeId = -1L,
        serverId = serverId,
        uuid = "ws-pending",
        projectId = 100L,
        roomId = 400L,
        name = "Pending Scope",
        syncStatus = SyncStatus.PENDING,
        isDirty = true
    )

    @Test
    fun `syncRoomWorkScopes preserves pending creates when fetch is empty`() = runTest {
        coEvery { api.getRoomWorkScope(400L) } returns PaginatedResponse(data = emptyList())
        coEvery { localDataService.getPendingWorkScopesForRoom(400L) } returns listOf(pendingScope())
        val saved = slot<List<OfflineWorkScopeEntity>>()
        coEvery { localDataService.saveWorkScopes(capture(saved)) } just runs

        service().syncRoomWorkScopes(projectId = 100L, roomId = 400L)

        assertThat(saved.captured.map { it.uuid }).contains("ws-pending")
    }

    @Test
    fun `syncRoomWorkScopes merges fetched and pending creates`() = runTest {
        coEvery { api.getRoomWorkScope(400L) } returns PaginatedResponse(data = listOf(workScopeDto(5L)))
        coEvery { localDataService.getPendingWorkScopesForRoom(400L) } returns listOf(pendingScope())
        val saved = slot<List<OfflineWorkScopeEntity>>()
        coEvery { localDataService.saveWorkScopes(capture(saved)) } just runs

        service().syncRoomWorkScopes(projectId = 100L, roomId = 400L)

        assertThat(saved.captured.mapNotNull { it.serverId }).contains(5L)
        assertThat(saved.captured.map { it.uuid }).contains("ws-pending")
        assertThat(saved.captured).hasSize(2)
    }

    @Test
    fun `syncRoomWorkScopes does not duplicate a pending scope already returned by the fetch`() = runTest {
        coEvery { api.getRoomWorkScope(400L) } returns PaginatedResponse(data = listOf(workScopeDto(5L)))
        // pending row already assigned serverId 5 (also present in the fetch) → must not be re-added
        coEvery { localDataService.getPendingWorkScopesForRoom(400L) } returns listOf(pendingScope(serverId = 5L))
        val saved = slot<List<OfflineWorkScopeEntity>>()
        coEvery { localDataService.saveWorkScopes(capture(saved)) } just runs

        service().syncRoomWorkScopes(projectId = 100L, roomId = 400L)

        assertThat(saved.captured).hasSize(1)
        assertThat(saved.captured.single().serverId).isEqualTo(5L)
    }
}
