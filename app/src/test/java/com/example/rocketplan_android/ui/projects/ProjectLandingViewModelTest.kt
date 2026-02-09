package com.example.rocketplan_android.ui.projects

import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.network.SyncNetworkMonitor
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectLandingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val projectId = 42L

    private fun makeProject(
        serverId: Long? = projectId
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
        propertyId = 10L,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = now(),
        updatedAt = now(),
        lastSyncedAt = now()
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

    private fun buildVm(
        projectsFlow: MutableStateFlow<List<OfflineProjectEntity>> = MutableStateFlow(listOf(makeProject())),
        notesFlow: MutableStateFlow<List<OfflineNoteEntity>> = MutableStateFlow(emptyList()),
        locationsFlow: MutableStateFlow<List<OfflineLocationEntity>> = MutableStateFlow(emptyList()),
        roomsFlow: MutableStateFlow<List<OfflineRoomEntity>> = MutableStateFlow(emptyList()),
        projectSyncingFlow: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet()),
        isOnlineFlow: MutableStateFlow<Boolean> = MutableStateFlow(true),
        cachedLocations: List<OfflineLocationEntity> = emptyList()
    ): ProjectLandingViewModel {
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeProjects() } returns projectsFlow
        every { localDataService.observeNotes(projectId) } returns notesFlow
        every { localDataService.observeLocations(projectId) } returns locationsFlow
        every { localDataService.observeRooms(projectId) } returns roomsFlow
        coEvery { localDataService.getLocations(projectId) } returns cachedLocations

        val application = mockk<RocketPlanApplication>(relaxed = true)
        every { application.localDataService } returns localDataService
        val offlineSyncRepository = mockk<OfflineSyncRepository>(relaxed = true)
        every { application.offlineSyncRepository } returns offlineSyncRepository
        val syncQueueManager = mockk<SyncQueueManager>()
        every { syncQueueManager.projectSyncingProjects } returns projectSyncingFlow
        every { syncQueueManager.projectEssentialsFailed } returns MutableStateFlow(emptySet())
        every { syncQueueManager.prioritizeProject(projectId) } returns Unit
        every { application.syncQueueManager } returns syncQueueManager
        val syncNetworkMonitor = mockk<SyncNetworkMonitor>()
        every { syncNetworkMonitor.isOnline } returns isOnlineFlow
        every { application.syncNetworkMonitor } returns syncNetworkMonitor

        return ProjectLandingViewModel(application, projectId)
    }

    /** Wait for the VM to process its first real emission. */
    private suspend fun ProjectLandingViewModel.awaitReady() {
        screenState.first { it.ui is ProjectLandingUiState.Ready }
    }

    // -- Sync blocking tests --

    @Test
    fun `fresh project with syncing emits blocking true`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val viewModel = buildVm(projectSyncingFlow = projectSyncingFlow)

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when essentials arrive`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val locationsFlow = MutableStateFlow<List<OfflineLocationEntity>>(emptyList())
        val viewModel = buildVm(
            locationsFlow = locationsFlow,
            projectSyncingFlow = projectSyncingFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        locationsFlow.value = listOf(makeLocation())
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when sawSyncing then sync ends with no data`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val viewModel = buildVm(projectSyncingFlow = projectSyncingFlow)

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        projectSyncingFlow.value = emptySet()
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks on timeout`() = runTest {
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val viewModel = buildVm(projectSyncingFlow = projectSyncingFlow)

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        advanceTimeBy(ProjectLandingViewModel.SYNC_TIMEOUT_MS + 1)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when offline`() = runTest {
        val isOnlineFlow = MutableStateFlow(true)
        val projectSyncingFlow = MutableStateFlow(setOf(projectId))
        val viewModel = buildVm(
            projectSyncingFlow = projectSyncingFlow,
            isOnlineFlow = isOnlineFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        isOnlineFlow.value = false
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `fresh project unblocks when no serverId`() = runTest {
        val projectsFlow = MutableStateFlow(listOf(makeProject(serverId = null)))
        val viewModel = buildVm(projectsFlow = projectsFlow)

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
        val viewModel = buildVm(
            projectSyncingFlow = projectSyncingFlow,
            isOnlineFlow = isOnlineFlow
        )

        viewModel.awaitReady()
        assertThat(viewModel.screenState.value.isSyncBlocking).isTrue()

        // Go offline -> escape
        isOnlineFlow.value = false
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()

        // Come back online and syncing resumes -> stays unblocked (sticky)
        isOnlineFlow.value = true
        projectSyncingFlow.value = setOf(projectId)
        advanceUntilIdle()
        assertThat(viewModel.screenState.value.isSyncBlocking).isFalse()
        viewModel.viewModelScope.cancel()
    }

    private fun now(): Date = Date()
}
