package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentAssetSyncServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val local: LocalDataService = mockk(relaxed = true)
    private val enqueuer: SyncQueueEnqueuer = mockk(relaxed = true)
    private val service = EquipmentAssetSyncService(local, { enqueuer }, Dispatchers.Unconfined)

    @Test
    fun `registerAsset saves an available dirty asset and enqueues upsert`() = runTest {
        val saved = slot<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saved)) } just Runs
        coEvery { local.getEquipmentAssetByUuid(any()) } answers { saved.captured.first() }
        val enq = slot<OfflineEquipmentAssetEntity>()
        coEvery { enqueuer.enqueueEquipmentAssetUpsert(capture(enq), any()) } just Runs

        service.registerAsset(companyId = 7L, name = "Air Mover")

        val entity = saved.captured.first()
        assertThat(entity.companyId).isEqualTo(7L)
        assertThat(entity.name).isEqualTo("Air Mover")
        assertThat(entity.status).isEqualTo("available")
        assertThat(entity.serverId).isNull()
        assertThat(entity.isDirty).isTrue()
        // catalogUuid defaults to the asset uuid when not supplied.
        assertThat(entity.catalogUuid).isEqualTo(entity.uuid)
        coVerify(exactly = 1) { enqueuer.enqueueEquipmentAssetUpsert(any(), any()) }
    }

    @Test
    fun `deployAsset creates an open placement and enqueues deploy`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        val savedP = slot<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(savedP)) } just Runs
        coEvery { local.getEquipmentPlacementByUuid(any()) } answers { savedP.captured.first() }

        val result = service.deployAsset(assetLocalId = 50L, roomLocalId = 400L, projectLocalId = 100L)

        val placement = savedP.captured.first()
        assertThat(placement.assetId).isEqualTo(50L)
        assertThat(placement.roomId).isEqualTo(400L)
        assertThat(placement.isOpen).isTrue()
        assertThat(placement.serverId).isNull()
        assertThat(result).isNotNull()
        coVerify(exactly = 1) { enqueuer.enqueuePlacementDeploy(any()) }
        // Clean asset optimistically flips to deployed.
        coVerify { local.saveEquipmentAssets(match { it.first().status == "deployed" }) }
    }

    @Test
    fun `deployAsset with unknown asset returns null and does not enqueue`() = runTest {
        coEvery { local.getEquipmentAsset(50L) } returns null
        val result = service.deployAsset(assetLocalId = 50L, roomLocalId = 400L)
        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementDeploy(any()) }
    }

    @Test
    fun `retireAsset with serverId enqueues retire`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset

        service.retireAsset(50L)

        coVerify(exactly = 1) { enqueuer.enqueueEquipmentAssetRetire(match { it.isDeleted && it.status == "retired" }, any()) }
    }

    @Test
    fun `retireAsset without serverId resolves locally without enqueue`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = null, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.removeSyncOperationsForEntity(any(), any()) } just Runs

        val result = service.retireAsset(50L)

        assertThat(result?.isDeleted).isTrue()
        coVerify(exactly = 0) { enqueuer.enqueueEquipmentAssetRetire(any(), any()) }
        coVerify(exactly = 1) { local.removeSyncOperationsForEntity("equipment_asset", 50L) }
    }
}
