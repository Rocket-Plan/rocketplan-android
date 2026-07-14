package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementDto
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementResponse
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentAssetPlacementPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val ctx = PushHandlerTestFixtures.createContext(api, localDataService, remoteLogger)
    private val handler = EquipmentAssetPlacementPushHandler(ctx)

    private fun placement(serverId: Long? = null, roomId: Long? = 400L) =
        OfflineEquipmentPlacementEntity(
            placementId = 60L,
            serverId = serverId,
            uuid = "placement-uuid",
            assetId = 50L,
            roomId = roomId,
            projectId = 100L,
            dateIn = Date(),
            isOpen = true,
            isDirty = true,
            createdAt = Date(),
            updatedAt = Date()
        )

    private fun asset(serverId: Long? = 900L) = OfflineEquipmentAssetEntity(
        assetId = 50L, serverId = serverId, uuid = "asset-uuid", companyId = 7L,
        status = "available", createdAt = Date(), updatedAt = Date()
    )

    private fun placementDto() = EquipmentAssetPlacementDto(
        id = 12L, uuid = "server-placement-uuid", equipmentAssetId = 900L, roomId = 4000L,
        projectId = 1000L, dateIn = "2026-07-01T10:00:00.000000Z", dateOut = null,
        placedByUserId = null, note = null, idempotencyKey = null,
        createdAt = "2026-07-01T10:00:00.000000Z", updatedAt = "2026-07-01T10:00:00.000000Z", isOpen = true
    )

    private val op = PushHandlerTestFixtures.createSyncOperation(
        "equipment_asset_placement", 60L, "placement-uuid", SyncOperationType.CREATE
    )

    @Test
    fun `deploy posts placement and returns success`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement()
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        coEvery { localDataService.getRoom(400L) } returns PushHandlerTestFixtures.createRoom(serverId = 4000L)
        coEvery { api.deployEquipmentAsset(900L, any()) } returns EquipmentAssetPlacementResponse(placementDto())

        val outcome = handler.handleDeploy(op)

        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 1) { api.deployEquipmentAsset(900L, any()) }
    }

    @Test
    fun `skips until asset is registered`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement()
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = null)

        assertThat(handler.handleDeploy(op)).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.deployEquipmentAsset(any(), any()) }
    }

    @Test
    fun `skips until room is synced`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement()
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        coEvery { localDataService.getRoom(400L) } returns PushHandlerTestFixtures.createRoom(serverId = null)

        assertThat(handler.handleDeploy(op)).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.deployEquipmentAsset(any(), any()) }
    }

    @Test
    fun `already-pushed placement succeeds without api call`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement(serverId = 12L)
        assertThat(handler.handleDeploy(op)).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deployEquipmentAsset(any(), any()) }
    }

    @Test
    fun `missing placement drops`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns null
        assertThat(handler.handleDeploy(op)).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `placement without room drops`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement(roomId = null)
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        assertThat(handler.handleDeploy(op)).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `422 drops`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement()
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        coEvery { localDataService.getRoom(400L) } returns PushHandlerTestFixtures.createRoom(serverId = 4000L)
        coEvery { api.deployEquipmentAsset(900L, any()) } throws PushHandlerTestFixtures.create422Response()

        assertThat(handler.handleDeploy(op)).isEqualTo(OperationOutcome.DROP)
    }

    // ===== Move (Phase 1c) =====

    private fun assetDto() = com.example.rocketplan_android.data.model.offline.EquipmentAssetDto(
        id = 900L, uuid = "asset-uuid", companyId = 7L, catalogUuid = "cat", name = "Air Mover",
        manufacturer = null, model = null, isStandard = true, serialNumber = null, assetTag = null,
        status = "deployed", currentPlacementId = 13L, purchaseDate = null, purchasePrice = null,
        vendor = null, warrantyExpiresAt = null, rentalDayRate = null, idempotencyKey = null, note = null,
        createdAt = "2026-07-01T10:00:00.000000Z", updatedAt = "2026-07-05T10:00:00.000000Z",
        placements = listOf(placementDto())
    )

    private val moveOp = PushHandlerTestFixtures.createSyncOperation(
        "equipment_asset_placement", 60L, "placement-uuid", SyncOperationType.UPDATE
    )

    @Test
    fun `move posts to move endpoint and reconciles`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement(serverId = 12L)
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        coEvery { localDataService.getRoom(400L) } returns PushHandlerTestFixtures.createRoom(serverId = 4000L)
        coEvery { api.moveEquipmentAsset(900L, any()) } returns
            com.example.rocketplan_android.data.model.offline.EquipmentAssetResponse(assetDto())

        val outcome = handler.handleMove(moveOp)

        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 1) { api.moveEquipmentAsset(900L, any()) }
        coVerify { localDataService.saveEquipmentAssets(any()) }
    }

    @Test
    fun `move skips until asset registered`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement(serverId = 12L)
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = null)
        assertThat(handler.handleMove(moveOp)).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.moveEquipmentAsset(any(), any()) }
    }

    @Test
    fun `move 409 records conflict`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement(serverId = 12L)
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        coEvery { localDataService.getRoom(400L) } returns PushHandlerTestFixtures.createRoom(serverId = 4000L)
        coEvery { api.moveEquipmentAsset(900L, any()) } throws PushHandlerTestFixtures.create409WithUpdatedAt()

        assertThat(handler.handleMove(moveOp)).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify(exactly = 1) { localDataService.upsertConflict(any()) }
    }

    // ===== Check-out (Phase 1c) =====

    private val checkoutOp = PushHandlerTestFixtures.createSyncOperation(
        "equipment_asset_placement", 60L, "placement-uuid", SyncOperationType.DELETE
    )

    @Test
    fun `check-out posts to check-out endpoint`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement(serverId = 12L)
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        coEvery { api.checkOutEquipmentAsset(900L, any()) } returns
            com.example.rocketplan_android.data.model.offline.EquipmentAssetResponse(assetDto())

        val outcome = handler.handleCheckOut(checkoutOp)

        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 1) { api.checkOutEquipmentAsset(900L, any()) }
    }

    @Test
    fun `check-out skips until the placement itself has synced`() = runTest {
        coEvery { localDataService.getEquipmentPlacementByUuid("placement-uuid") } returns placement(serverId = null)
        coEvery { localDataService.getEquipmentAsset(50L) } returns asset(serverId = 900L)
        assertThat(handler.handleCheckOut(checkoutOp)).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.checkOutEquipmentAsset(any(), any()) }
    }
}
