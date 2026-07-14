package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.model.offline.EquipmentAssetDto
import com.example.rocketplan_android.data.model.offline.EquipmentAssetResponse
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentAssetPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val ctx = PushHandlerTestFixtures.createContext(api, localDataService, remoteLogger)
    private val handler = EquipmentAssetPushHandler(ctx)

    private fun asset(
        serverId: Long? = null,
        uuid: String = "asset-uuid",
        isDeleted: Boolean = false
    ) = OfflineEquipmentAssetEntity(
        assetId = 50L,
        serverId = serverId,
        uuid = uuid,
        companyId = 7L,
        catalogUuid = "cat-uuid",
        name = "Air Mover",
        status = if (serverId == null) "available" else "maintenance",
        isDirty = true,
        isDeleted = isDeleted,
        createdAt = Date(),
        updatedAt = Date()
    )

    private fun dto(serverId: Long = 900L) = EquipmentAssetDto(
        id = serverId, uuid = "server-asset-uuid", companyId = 7L, catalogUuid = "cat-uuid",
        name = "Air Mover", manufacturer = null, model = null, isStandard = true,
        serialNumber = null, assetTag = null, status = "available", currentPlacementId = null,
        purchaseDate = null, purchasePrice = null, vendor = null, warrantyExpiresAt = null,
        rentalDayRate = null, idempotencyKey = null, note = null,
        createdAt = "2026-07-01T10:00:00.000000Z", updatedAt = "2026-07-01T10:00:00.000000Z"
    )

    private fun op(type: SyncOperationType = SyncOperationType.CREATE) =
        PushHandlerTestFixtures.createSyncOperation("equipment_asset", 50L, "asset-uuid", type)

    @Test
    fun `register posts to company endpoint and saves synced`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = null)
        coEvery { api.registerEquipmentAsset(7L, any()) } returns EquipmentAssetResponse(dto(), idempotent = null)

        val outcome = handler.handleUpsert(op(SyncOperationType.CREATE))

        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 1) { api.registerEquipmentAsset(7L, any()) }
        coVerify(exactly = 0) { api.updateEquipmentAsset(any(), any()) }
    }

    @Test
    fun `update puts to asset endpoint when serverId present`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = 900L)
        coEvery { api.updateEquipmentAsset(900L, any()) } returns EquipmentAssetResponse(dto(), idempotent = null)

        val outcome = handler.handleUpsert(op(SyncOperationType.UPDATE))

        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 1) { api.updateEquipmentAsset(900L, any()) }
    }

    @Test
    fun `deleted asset drops`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(isDeleted = true)
        assertThat(handler.handleUpsert(op())).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `missing asset drops`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns null
        assertThat(handler.handleUpsert(op())).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `409 then success retries with fresh timestamp`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = 900L)
        val lockSlot = slot<com.example.rocketplan_android.data.model.offline.UpdateEquipmentAssetRequest>()
        var calls = 0
        coEvery { api.updateEquipmentAsset(900L, capture(lockSlot)) } coAnswers {
            calls++
            if (calls == 1) throw PushHandlerTestFixtures.create409WithUpdatedAt("2026-07-09T00:00:00.000000Z")
            EquipmentAssetResponse(dto(), idempotent = null)
        }

        val outcome = handler.handleUpsert(op(SyncOperationType.UPDATE))

        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        // Retry used the fresh timestamp extracted from the 409 body.
        assertThat(lockSlot.captured.updatedAt).isEqualTo("2026-07-09T00:00:00.000000Z")
    }

    @Test
    fun `double 409 records conflict and returns CONFLICT_PENDING`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = 900L)
        // First call 409s (body carries updated_at → retried); retry 409s again → conflict.
        coEvery { api.updateEquipmentAsset(900L, any()) } throws PushHandlerTestFixtures.create409WithUpdatedAt()
        coEvery { localDataService.upsertConflict(any()) } just Runs

        val outcome = handler.handleUpsert(op(SyncOperationType.UPDATE))

        assertThat(outcome).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify(exactly = 1) { localDataService.upsertConflict(any()) }
    }

    @Test
    fun `422 drops`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = 900L)
        coEvery { api.updateEquipmentAsset(900L, any()) } throws PushHandlerTestFixtures.create422Response()
        assertThat(handler.handleUpsert(op(SyncOperationType.UPDATE))).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `retire calls DELETE and saves deleted copy`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = 900L)
        coEvery { api.retireEquipmentAsset(900L) } returns PushHandlerTestFixtures.createEmptySuccessResponse()
        val saved = slot<List<OfflineEquipmentAssetEntity>>()
        coEvery { localDataService.saveEquipmentAssets(capture(saved)) } just Runs

        val outcome = handler.handleDelete(op(SyncOperationType.DELETE))

        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        assertThat(saved.captured.single().isDeleted).isTrue()
        assertThat(saved.captured.single().syncStatus).isEqualTo(SyncStatus.SYNCED)
    }

    @Test
    fun `retire with no serverId resolves locally`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = null)
        val outcome = handler.handleDelete(op(SyncOperationType.DELETE))
        assertThat(outcome).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.retireEquipmentAsset(any()) }
    }

    @Test
    fun `retire 422 drops`() = runTest {
        coEvery { localDataService.getEquipmentAssetByUuid("asset-uuid") } returns asset(serverId = 900L)
        coEvery { api.retireEquipmentAsset(900L) } returns PushHandlerTestFixtures.create422RetrofitResponse()
        assertThat(handler.handleDelete(op(SyncOperationType.DELETE))).isEqualTo(OperationOutcome.DROP)
    }
}
