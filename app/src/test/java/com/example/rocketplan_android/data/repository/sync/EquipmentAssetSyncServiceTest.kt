package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
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

    @org.junit.Before
    fun stubTransaction() {
        // runInTransaction just executes its block in tests (review #4 wraps writes in it).
        coEvery { local.runInTransaction<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        // Default: no queued op for any entity (relaxed would otherwise return a non-null mock,
        // tripping the #5 in-flight guard). Specific tests override for PENDING.
        coEvery { local.getSyncOperationForEntity(any(), any(), any()) } returns null
    }

    @Test
    fun `registerAsset saves an available dirty asset and enqueues upsert`() = runTest {
        val saved = slot<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saved)) } just Runs
        coEvery { local.getEquipmentAssetByUuid(any()) } answers { saved.captured.first() }
        val enq = slot<OfflineEquipmentAssetEntity>()
        coEvery { enqueuer.enqueueEquipmentAssetUpsert(capture(enq), any()) } just Runs

        service.registerAsset(companyId = 7L, name = "Air Mover", catalogUuid = "cat-uuid")

        val entity = saved.captured.first()
        assertThat(entity.companyId).isEqualTo(7L)
        assertThat(entity.name).isEqualTo("Air Mover")
        assertThat(entity.status).isEqualTo("available")
        assertThat(entity.serverId).isNull()
        assertThat(entity.isDirty).isTrue()
        // Review #6: the supplied catalog id is used verbatim (no uuid fallback).
        assertThat(entity.catalogUuid).isEqualTo("cat-uuid")
        coVerify(exactly = 1) { enqueuer.enqueueEquipmentAssetUpsert(any(), any()) }
    }

    @Test
    fun `deployAsset creates an open placement and enqueues deploy`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        // Deploy invariants (review #7): no open placement, room exists + same company.
        coEvery { local.getOpenPlacementForAsset(50L) } returns null
        coEvery { local.getRoom(400L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createRoom(roomId = 400L, projectId = 100L)
        coEvery { local.getProject(100L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createProject(projectId = 100L, companyId = 7L)
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
    fun `deployAsset rejected when asset already has an open placement`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 1L, uuid = "open", assetId = 50L, roomId = 400L, isOpen = true,
            createdAt = Date(), updatedAt = Date()
        )

        val result = service.deployAsset(assetLocalId = 50L, roomLocalId = 400L)

        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementDeploy(any()) }
    }

    @Test
    fun `deployAsset rejected when asset is not available`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset

        val result = service.deployAsset(assetLocalId = 50L, roomLocalId = 400L)

        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementDeploy(any()) }
    }

    @Test
    fun `moveAsset updates the open placement room and enqueues move`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 1L, serverId = 12L, uuid = "open", assetId = 50L, roomId = 400L,
            isOpen = true, createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getRoom(500L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createRoom(roomId = 500L, projectId = 100L)
        coEvery { local.getProject(100L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createProject(projectId = 100L, companyId = 7L)
        val moved = slot<OfflineEquipmentPlacementEntity>()
        coEvery { enqueuer.enqueuePlacementMove(capture(moved)) } just Runs

        val result = service.moveAsset(assetLocalId = 50L, toRoomLocalId = 500L)

        assertThat(result).isNotNull()
        assertThat(moved.captured.roomId).isEqualTo(500L)
        coVerify(exactly = 1) { enqueuer.enqueuePlacementMove(any()) }
    }

    @Test
    fun `moveAsset to the same room is a no-op`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 1L, serverId = 12L, uuid = "open", assetId = 50L, roomId = 400L,
            isOpen = true, createdAt = Date(), updatedAt = Date()
        )

        val result = service.moveAsset(assetLocalId = 50L, toRoomLocalId = 400L)

        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementMove(any()) }
    }

    @Test
    fun `move with a PENDING deploy retargets the original op (no new op, same key)`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 60L, serverId = null, uuid = "p", assetId = 50L, roomId = 400L,
            isOpen = true, isDirty = true, createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getRoom(500L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createRoom(roomId = 500L, projectId = 100L)
        coEvery { local.getProject(100L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createProject(projectId = 100L, companyId = 7L)
        // PROVEN never dispatched: a PENDING deploy CREATE (SYNCING lookup stays null via @Before).
        coEvery { local.getSyncOperationForEntity("equipment_asset_placement", 60L, SyncStatus.PENDING) } returns
            com.example.rocketplan_android.testing.PushHandlerTestFixtures.createSyncOperation(
                entityType = "equipment_asset_placement", entityId = 60L, entityUuid = "p",
                operationType = SyncOperationType.CREATE
            )
        val savedP = slot<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(savedP)) } just Runs

        val result = service.moveAsset(assetLocalId = 50L, toRoomLocalId = 500L)

        // Round-10 #2: retarget the ORIGINAL deploy op (same key) — NO new move/deploy op enqueued.
        assertThat(result).isNotNull()
        assertThat(savedP.captured.single().roomId).isEqualTo(500L)
        coVerify(exactly = 0) { enqueuer.enqueuePlacementMove(any()) }
        coVerify(exactly = 0) { enqueuer.enqueuePlacementDeploy(any()) }
    }

    @Test
    fun `move rejected when the deploy is ambiguous (not proven never-dispatched)`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        // serverId == null but NO PENDING deploy op (e.g. FAILED) — @Before defaults the lookup to null.
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 60L, serverId = null, uuid = "p", assetId = 50L, roomId = 400L,
            isOpen = true, isDirty = true, createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getRoom(500L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createRoom(roomId = 500L, projectId = 100L)
        coEvery { local.getProject(100L) } returns com.example.rocketplan_android.testing.PushHandlerTestFixtures.createProject(projectId = 100L, companyId = 7L)

        val result = service.moveAsset(assetLocalId = 50L, toRoomLocalId = 500L)

        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementMove(any()) }
        coVerify(exactly = 0) { enqueuer.enqueuePlacementDeploy(any()) }
    }

    @Test
    fun `checkout rejected when the deploy is ambiguous (not proven never-dispatched)`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 60L, serverId = null, uuid = "p", assetId = 50L, roomId = 400L,
            isOpen = true, isDirty = true, createdAt = Date(), updatedAt = Date()
        )

        val result = service.checkOutAsset(50L)

        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementCheckout(any()) }
        coVerify(exactly = 0) { local.removeSyncOperationsForEntity(any(), any()) }
    }

    @Test
    fun `checkout before deploy syncs collapses the deploy op`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 60L, serverId = null, uuid = "p", assetId = 50L, roomId = 400L,
            isOpen = true, isDirty = true, createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getSyncOperationForEntity("equipment_asset_placement", 60L, SyncStatus.PENDING) } returns
            com.example.rocketplan_android.testing.PushHandlerTestFixtures.createSyncOperation(
                entityType = "equipment_asset_placement", entityId = 60L, entityUuid = "p",
                operationType = SyncOperationType.CREATE
            )
        coEvery { local.removeSyncOperationsForEntity(any(), any()) } just Runs

        service.checkOutAsset(50L)

        coVerify(exactly = 1) { local.removeSyncOperationsForEntity("equipment_asset_placement", 60L) }
        coVerify(exactly = 0) { enqueuer.enqueuePlacementCheckout(any()) }
    }

    @Test
    fun `checkOutAsset closes the open placement and enqueues checkout`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 1L, serverId = 12L, uuid = "open", assetId = 50L, roomId = 400L,
            isOpen = true, createdAt = Date(), updatedAt = Date()
        )

        val result = service.checkOutAsset(50L)

        assertThat(result).isNotNull()
        coVerify(exactly = 1) { enqueuer.enqueuePlacementCheckout(any()) }
        // Asset optimistically returned to the available pool.
        coVerify { local.saveEquipmentAssets(match { it.first().status == "available" }) }
    }

    @Test
    fun `retireAsset with serverId enqueues retire`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns null // #5 guard: not deployed

        service.retireAsset(50L)

        coVerify(exactly = 1) { enqueuer.enqueueEquipmentAssetRetire(match { it.isDeleted && it.status == "retired" }, any()) }
    }

    @Test
    fun `retireAsset rejects a server-known asset that is still deployed`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getOpenPlacementForAsset(50L) } returns OfflineEquipmentPlacementEntity(
            placementId = 1L, serverId = 12L, uuid = "open", assetId = 50L, roomId = 400L,
            isOpen = true, createdAt = Date(), updatedAt = Date()
        )

        val result = service.retireAsset(50L)

        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueueEquipmentAssetRetire(any(), any()) }
    }

    @Test
    fun `retireAsset without serverId resolves locally without enqueue`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = null, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getAllPlacementsForAsset(50L) } returns emptyList()
        coEvery { local.removeSyncOperationsForEntity(any(), any()) } just Runs

        val result = service.retireAsset(50L)

        assertThat(result?.isDeleted).isTrue()
        coVerify(exactly = 0) { enqueuer.enqueueEquipmentAssetRetire(any(), any()) }
        coVerify(exactly = 1) { local.removeSyncOperationsForEntity("equipment_asset", 50L) }
    }

    @Test
    fun `retiring a never-synced asset collapses its placement op graph`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = null, uuid = "asset-uuid", companyId = 7L,
            status = "deployed", createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAsset(50L) } returns asset
        coEvery { local.getAllPlacementsForAsset(50L) } returns listOf(
            OfflineEquipmentPlacementEntity(
                placementId = 60L, serverId = null, uuid = "p", assetId = 50L, roomId = 400L,
                isOpen = true, isDirty = true, createdAt = Date(), updatedAt = Date()
            )
        )
        coEvery { local.removeSyncOperationsForEntity(any(), any()) } just Runs
        val placementSaves = slot<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(placementSaves)) } just Runs

        service.retireAsset(50L)

        // Review #7: the orphaned placement op is dropped and the placement soft-deleted.
        coVerify(exactly = 1) { local.removeSyncOperationsForEntity("equipment_asset_placement", 60L) }
        coVerify(exactly = 1) { local.removeSyncOperationsForEntity("equipment_asset", 50L) }
        coVerify(exactly = 0) { enqueuer.enqueueEquipmentAssetRetire(any(), any()) }
        assertThat(placementSaves.captured.single().isDeleted).isTrue()
    }

    @Test
    fun `updateAsset saves dirty and enqueues upsert with lock timestamp`() = runTest {
        val asset = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "asset-uuid", companyId = 7L,
            status = "available", createdAt = Date(), updatedAt = Date(),
            serverUpdatedAt = Date()
        )
        val saved = slot<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saved)) } just Runs
        val lock = slot<String>()
        val capturedAsset = slot<OfflineEquipmentAssetEntity>()
        coEvery { enqueuer.enqueueEquipmentAssetUpsert(capture(capturedAsset), capture(lock)) } just Runs

        val edited = asset.copy(serialNumber = "SN-EDITED", status = "maintenance")
        val result = service.updateAsset(edited)

        assertThat(result).isNotNull()
        assertThat(saved.captured.first().serialNumber).isEqualTo("SN-EDITED")
        assertThat(saved.captured.first().isDirty).isTrue()
        assertThat(lock.captured).isNotEmpty()
        coVerify(exactly = 1) { enqueuer.enqueueEquipmentAssetUpsert(any(), any()) }
    }

    // ===== RP-FR-030 correctPlacement =====

    private fun closedServerKnownPlacement() = OfflineEquipmentPlacementEntity(
        placementId = 60L, serverId = 12L, uuid = "p", assetId = 50L, roomId = 400L,
        dateIn = Date(1_700_000_000_000L), dateOut = Date(1_700_600_000_000L),
        isOpen = false, createdAt = Date(), updatedAt = Date(), serverUpdatedAt = Date()
    )

    @Test
    fun `correctPlacement stages new dates dirty and enqueues correction`() = runTest {
        coEvery { local.getEquipmentPlacement(60L) } returns closedServerKnownPlacement()
        val saved = slot<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(saved)) } just Runs

        val newIn = Date(1_700_100_000_000L)
        val result = service.correctPlacement(placementLocalId = 60L, dateIn = newIn, dateOut = null)

        assertThat(result).isNotNull()
        val row = saved.captured.first()
        assertThat(row.dateIn).isEqualTo(newIn)
        assertThat(row.dateOut).isEqualTo(Date(1_700_600_000_000L)) // unchanged
        assertThat(row.isDirty).isTrue()
        assertThat(row.syncStatus).isEqualTo(SyncStatus.PENDING)
        coVerify(exactly = 1) { enqueuer.enqueuePlacementCorrection(any()) }
    }

    @Test
    fun `correctPlacement rejects an open placement`() = runTest {
        coEvery { local.getEquipmentPlacement(60L) } returns OfflineEquipmentPlacementEntity(
            placementId = 60L, serverId = 12L, uuid = "p", assetId = 50L, roomId = 400L,
            dateIn = Date(), dateOut = null, isOpen = true, createdAt = Date(), updatedAt = Date()
        )
        val result = service.correctPlacement(60L, dateIn = Date(), dateOut = null)
        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementCorrection(any()) }
    }

    @Test
    fun `correctPlacement rejects a not-yet-synced placement`() = runTest {
        coEvery { local.getEquipmentPlacement(60L) } returns closedServerKnownPlacement().copy(serverId = null)
        val result = service.correctPlacement(60L, dateIn = Date(), dateOut = Date())
        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementCorrection(any()) }
    }

    // ===== RP-FR-031 deletePlacement =====

    @Test
    fun `deletePlacement marks a closed server-known placement deleted and enqueues delete`() = runTest {
        coEvery { local.getEquipmentPlacement(60L) } returns closedServerKnownPlacement()
        val saved = slot<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(saved)) } just Runs

        val result = service.deletePlacement(60L)

        assertThat(result).isNotNull()
        val row = saved.captured.first()
        assertThat(row.isDeleted).isTrue()
        assertThat(row.isDirty).isTrue()               // dirty so a pull can't resurrect it pre-sync
        assertThat(row.syncStatus).isEqualTo(SyncStatus.PENDING)
        coVerify(exactly = 1) { enqueuer.enqueuePlacementDelete(any()) }
    }

    @Test
    fun `deletePlacement refuses an open placement`() = runTest {
        coEvery { local.getEquipmentPlacement(60L) } returns OfflineEquipmentPlacementEntity(
            placementId = 60L, serverId = 12L, uuid = "p", assetId = 50L, roomId = 400L,
            dateOut = null, isOpen = true, createdAt = Date(), updatedAt = Date()
        )
        val result = service.deletePlacement(60L)
        assertThat(result).isNull()
        coVerify(exactly = 0) { enqueuer.enqueuePlacementDelete(any()) }
    }

    @Test
    fun `deletePlacement collapses an unsynced closed placement locally without enqueuing`() = runTest {
        coEvery { local.getEquipmentPlacement(60L) } returns closedServerKnownPlacement().copy(serverId = null)
        val saved = slot<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(saved)) } just Runs

        val result = service.deletePlacement(60L)

        assertThat(result).isNotNull()
        assertThat(saved.captured.first().isDeleted).isTrue()
        assertThat(saved.captured.first().syncStatus).isEqualTo(SyncStatus.SYNCED)
        coVerify(exactly = 1) { local.removeSyncOperationsForEntity("equipment_asset_placement", 60L) }
        coVerify(exactly = 0) { enqueuer.enqueuePlacementDelete(any()) }
    }
}
