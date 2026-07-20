package com.example.rocketplan_android.ui.rocketdry

import app.cash.turbine.test
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class PlacementHistoryViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val assetLocalId = 7L
    private val companyId = 99L

    @Test
    fun `rows are ordered desc by dateIn, open renders Currently deployed, names resolve`() = runTest {
        // dateIn: closed@day1, closed@day2, open@day3 (unsorted on the way in)
        val day1 = Date(1_000L)
        val day2 = Date(2_000L)
        val day3 = Date(3_000L)
        val placements = listOf(
            closedPlacement(id = 1, roomId = 3, projectId = 11, dateIn = day1, dateOut = Date(1_500L)),
            openPlacement(id = 3, roomId = 4, projectId = 12, dateIn = day3),
            closedPlacement(id = 2, roomId = 3, projectId = 11, dateIn = day2, dateOut = Date(2_500L))
        )
        val rooms = mapOf(
            3L to OfflineRoomEntity(roomId = 3, uuid = "room-3", projectId = 11, title = "Kitchen"),
            4L to OfflineRoomEntity(roomId = 4, uuid = "room-4", projectId = 12, title = "Basement")
        )
        val projects = mapOf(
            11L to project(11, "123 Main St"),
            12L to project(12, "456 Oak Ave")
        )
        val vm = createViewModel(MutableStateFlow(placements), rooms, projects)

        vm.uiState.test {
            val ready = awaitReady()
            assertThat(ready.rows.map { it.placementId }).containsExactly(3L, 2L, 1L).inOrder()

            val open = ready.rows.first()
            assertThat(open.isOpen).isTrue()
            assertThat(open.roomName).isEqualTo("Basement")
            assertThat(open.projectName).isEqualTo("456 Oak Ave")
            assertThat(open.dateRange).contains("Currently deployed")

            val newestClosed = ready.rows[1]
            assertThat(newestClosed.isOpen).isFalse()
            assertThat(newestClosed.roomName).isEqualTo("Kitchen")
            assertThat(newestClosed.dateRange).doesNotContain("Currently deployed")
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deleted placements are filtered out`() = runTest {
        val placements = listOf(
            openPlacement(id = 3, roomId = 4, projectId = 12, dateIn = Date(3_000L)),
            closedPlacement(id = 1, roomId = 3, projectId = 11, dateIn = Date(1_000L), dateOut = Date(1_500L))
                .copy(isDeleted = true)
        )
        val rooms = mapOf(4L to OfflineRoomEntity(roomId = 4, uuid = "room-4", projectId = 12, title = "Basement"))
        val vm = createViewModel(MutableStateFlow(placements), rooms, mapOf(12L to project(12, "456 Oak Ave")))

        vm.uiState.test {
            val ready = awaitReady()
            assertThat(ready.rows.map { it.placementId }).containsExactly(3L)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `empty history renders Empty state`() = runTest {
        val vm = createViewModel(MutableStateFlow(emptyList()), emptyMap(), emptyMap())

        vm.uiState.test {
            assertThat(awaitNonLoading()).isInstanceOf(PlacementHistoryUiState.Empty::class.java)
            cancelAndConsumeRemainingEvents()
        }
    }

    // --- helpers ---

    private suspend fun app.cash.turbine.ReceiveTurbine<PlacementHistoryUiState>.awaitReady(): PlacementHistoryUiState.Ready {
        var state = awaitItem()
        while (state !is PlacementHistoryUiState.Ready) state = awaitItem()
        return state
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<PlacementHistoryUiState>.awaitNonLoading(): PlacementHistoryUiState {
        var state = awaitItem()
        while (state is PlacementHistoryUiState.Loading) state = awaitItem()
        return state
    }

    private fun createViewModel(
        placements: MutableStateFlow<List<OfflineEquipmentPlacementEntity>>,
        rooms: Map<Long, OfflineRoomEntity>,
        projects: Map<Long, OfflineProjectEntity>
    ): PlacementHistoryViewModel {
        val localDataService = mockk<LocalDataService>(relaxed = true)
        val remoteLogger = mockk<RemoteLogger>(relaxed = true)

        every { localDataService.observePlacementsForAsset(assetLocalId) } returns placements
        coEvery { localDataService.getRoom(any()) } answers { rooms[firstArg()] }
        coEvery { localDataService.getProject(any()) } answers { projects[firstArg()] }

        val application = mockk<RocketPlanApplication>()
        every { application.localDataService } returns localDataService
        every { application.remoteLogger } returns remoteLogger
        every { application.getString(any()) } returns "Currently deployed"

        return PlacementHistoryViewModel(application, assetLocalId)
    }

    private fun openPlacement(id: Long, roomId: Long, projectId: Long, dateIn: Date): OfflineEquipmentPlacementEntity =
        OfflineEquipmentPlacementEntity(
            placementId = id,
            serverId = id,
            uuid = "placement-$id",
            assetId = assetLocalId,
            roomId = roomId,
            projectId = projectId,
            dateIn = dateIn,
            isOpen = true,
            createdAt = Date(),
            updatedAt = Date(),
            syncStatus = SyncStatus.SYNCED
        )

    private fun closedPlacement(
        id: Long,
        roomId: Long,
        projectId: Long,
        dateIn: Date,
        dateOut: Date
    ): OfflineEquipmentPlacementEntity =
        openPlacement(id, roomId, projectId, dateIn).copy(isOpen = false, dateOut = dateOut)

    private fun project(id: Long, title: String): OfflineProjectEntity =
        OfflineProjectEntity(
            projectId = id,
            serverId = id,
            uuid = "project-$id",
            title = title,
            status = "active",
            companyId = companyId
        )
}
