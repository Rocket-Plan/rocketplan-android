package com.example.rocketplan_android.ui.rocketdry

import app.cash.turbine.test
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedAssetDetailViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val assetLocalId = 7L
    private val companyId = 99L

    @Test
    fun `RP-BUG-369 - mode OFF yields Disabled and refuses writes`() = runTest {
        val repo = mockk<OfflineSyncRepository>(relaxed = true)
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "deployed")),
            placements = MutableStateFlow(listOf(openPlacement())),
            repo = repo,
            mode = false
        )

        vm.uiState.test {
            assertThat(awaitNonLoading()).isEqualTo(SerializedAssetDetailUiState.Disabled)
            cancelAndConsumeRemainingEvents()
        }

        // Every write on the hub must be refused while the company is not in serialized mode.
        vm.checkOut()
        vm.retire()
        vm.move(toRoomLocalId = 3L)
        vm.deploy(roomLocalId = 3L, projectLocalId = 11L)
        coVerify(exactly = 0) { repo.checkOutEquipmentAssetOffline(any()) }
        coVerify(exactly = 0) { repo.retireEquipmentAssetOffline(any()) }
        coVerify(exactly = 0) { repo.moveEquipmentAssetOffline(any(), any()) }
        coVerify(exactly = 0) { repo.deployEquipmentAssetOffline(any(), any(), any()) }
    }

    @Test
    fun `RP-BUG-369 - mode UNKNOWN yields Disabled`() = runTest {
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "available")),
            placements = MutableStateFlow(emptyList()),
            mode = null
        )

        vm.uiState.test {
            assertThat(awaitNonLoading()).isEqualTo(SerializedAssetDetailUiState.Disabled)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `Ready renders all fields and current placement`() = runTest {
        val repo = mockk<OfflineSyncRepository>(relaxed = true)
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "deployed")),
            placements = MutableStateFlow(listOf(openPlacement())),
            repo = repo,
            room = OfflineRoomEntity(roomId = 3, uuid = "room-3", projectId = 11, title = "Kitchen"),
            project = project(11, "123 Main St")
        )

        vm.uiState.test {
            val ready = awaitReady()
            val asset = ready.asset
            assertThat(asset.manufacturer).isEqualTo("Phoenix")
            assertThat(asset.model).isEqualTo("AirMax")
            assertThat(asset.serialNumber).isEqualTo("SN-1")
            assertThat(asset.assetTag).isEqualTo("TAG-1")
            assertThat(asset.vendor).isEqualTo("Acme")
            assertThat(asset.purchasePrice).isEqualTo("1200")
            assertThat(ready.isDeployed).isTrue()
            assertThat(ready.currentPlacement?.roomName).isEqualTo("Kitchen")
            assertThat(ready.currentPlacement?.projectName).isEqualTo("123 Main St")
            assertThat(ready.currentPlacement?.dateIn).isNotEmpty()
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `NotFound when the row is absent`() = runTest {
        val vm = createViewModel(
            asset = MutableStateFlow(null),
            placements = MutableStateFlow(emptyList())
        )

        vm.uiState.test {
            assertThat(awaitNonLoading()).isInstanceOf(SerializedAssetDetailUiState.NotFound::class.java)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `NotFound when the row is soft-deleted`() = runTest {
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "available").copy(isDeleted = true)),
            placements = MutableStateFlow(emptyList())
        )

        vm.uiState.test {
            assertThat(awaitNonLoading()).isInstanceOf(SerializedAssetDetailUiState.NotFound::class.java)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `deploy delegates to the repository`() = runTest {
        val repo = mockk<OfflineSyncRepository>(relaxed = true)
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "available")),
            placements = MutableStateFlow(emptyList()),
            repo = repo
        )
        vm.uiState.test { awaitReady(); cancelAndConsumeRemainingEvents() }

        vm.deploy(roomLocalId = 3, projectLocalId = 11)
        coVerify(timeout = 2_000) { repo.deployEquipmentAssetOffline(assetLocalId, 3, 11) }
    }

    @Test
    fun `move delegates to the repository`() = runTest {
        val repo = mockk<OfflineSyncRepository>(relaxed = true)
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "deployed")),
            placements = MutableStateFlow(listOf(openPlacement())),
            repo = repo
        )
        vm.uiState.test { awaitReady(); cancelAndConsumeRemainingEvents() }

        vm.move(toRoomLocalId = 5)
        coVerify(timeout = 2_000) { repo.moveEquipmentAssetOffline(assetLocalId, 5) }
    }

    @Test
    fun `checkOut delegates to the repository`() = runTest {
        val repo = mockk<OfflineSyncRepository>(relaxed = true)
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "deployed")),
            placements = MutableStateFlow(listOf(openPlacement())),
            repo = repo
        )
        vm.uiState.test { awaitReady(); cancelAndConsumeRemainingEvents() }

        vm.checkOut()
        coVerify(timeout = 2_000) { repo.checkOutEquipmentAssetOffline(assetLocalId) }
    }

    @Test
    fun `retire delegates to the repository`() = runTest {
        val repo = mockk<OfflineSyncRepository>(relaxed = true)
        val vm = createViewModel(
            asset = MutableStateFlow(fullAsset(status = "available")),
            placements = MutableStateFlow(emptyList()),
            repo = repo
        )
        vm.uiState.test { awaitReady(); cancelAndConsumeRemainingEvents() }

        vm.retire()
        coVerify(timeout = 2_000) { repo.retireEquipmentAssetOffline(assetLocalId) }
    }

    // --- helpers ---

    private suspend fun app.cash.turbine.ReceiveTurbine<SerializedAssetDetailUiState>.awaitReady(): SerializedAssetDetailUiState.Ready {
        var state = awaitItem()
        while (state !is SerializedAssetDetailUiState.Ready) state = awaitItem()
        return state
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<SerializedAssetDetailUiState>.awaitNonLoading(): SerializedAssetDetailUiState {
        var state = awaitItem()
        while (state is SerializedAssetDetailUiState.Loading) state = awaitItem()
        return state
    }

    private fun createViewModel(
        asset: MutableStateFlow<OfflineEquipmentAssetEntity?>,
        placements: MutableStateFlow<List<OfflineEquipmentPlacementEntity>>,
        repo: OfflineSyncRepository = mockk(relaxed = true),
        room: OfflineRoomEntity? = null,
        project: OfflineProjectEntity? = null,
        // RP-BUG-369: owner-company serialized mode. true = ON (the default for existing cases),
        // false = OFF, null = UNKNOWN — both non-ON values must yield Disabled and block writes.
        mode: Boolean? = true
    ): SerializedAssetDetailViewModel {
        val localDataService = mockk<LocalDataService>(relaxed = true)
        val remoteLogger = mockk<RemoteLogger>(relaxed = true)

        every { localDataService.observeEquipmentAsset(assetLocalId) } returns asset
        every { localDataService.observePlacementsForAsset(assetLocalId) } returns placements
        coEvery { localDataService.getRoom(any()) } returns room
        coEvery { localDataService.getProject(any()) } returns project

        val secureStorage = mockk<SecureStorage>()
        every { secureStorage.observeSerializedEquipmentEnabled(companyId) } returns flowOf(mode)

        val application = mockk<RocketPlanApplication>()
        every { application.localDataService } returns localDataService
        every { application.offlineSyncRepository } returns repo
        every { application.remoteLogger } returns remoteLogger
        every { application.secureStorage } returns secureStorage
        every { application.getString(any()) } returns "message"

        return SerializedAssetDetailViewModel(application, assetLocalId)
    }

    private fun fullAsset(status: String): OfflineEquipmentAssetEntity =
        OfflineEquipmentAssetEntity(
            assetId = assetLocalId,
            serverId = assetLocalId,
            uuid = "asset-$assetLocalId",
            companyId = companyId,
            catalogUuid = "catalog-1",
            name = "Air Mover",
            manufacturer = "Phoenix",
            model = "AirMax",
            serialNumber = "SN-1",
            assetTag = "TAG-1",
            vendor = "Acme",
            purchaseDate = "2025-01-10",
            purchasePrice = "1200",
            warrantyExpiresAt = "2027-01-10",
            rentalDayRate = "35",
            note = "Handle with care",
            status = status,
            createdAt = Date(),
            updatedAt = Date(),
            syncStatus = SyncStatus.SYNCED
        )

    private fun openPlacement(): OfflineEquipmentPlacementEntity =
        OfflineEquipmentPlacementEntity(
            placementId = 1,
            serverId = 1,
            uuid = "placement-1",
            assetId = assetLocalId,
            roomId = 3,
            projectId = 11,
            dateIn = Date(),
            isOpen = true,
            createdAt = Date(),
            updatedAt = Date(),
            syncStatus = SyncStatus.SYNCED
        )

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
