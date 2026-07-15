package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.model.SingleDataResponse
import com.example.rocketplan_android.data.model.offline.EquipmentAssetDto
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPageResponse
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementDto
import com.example.rocketplan_android.data.model.offline.PaginationMeta
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
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
class EquipmentAssetPullServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val local: LocalDataService = mockk(relaxed = true)
    private val service = EquipmentAssetPullService(api, local, Dispatchers.Unconfined)

    private fun assetDto(id: Long) = EquipmentAssetDto(
        id = id, uuid = "srv-$id", companyId = 7L, catalogUuid = "cat", name = "Air Mover",
        manufacturer = null, model = null, isStandard = true, serialNumber = null, assetTag = null,
        status = "deployed", currentPlacementId = null, purchaseDate = null, purchasePrice = null,
        vendor = null, warrantyExpiresAt = null, rentalDayRate = null, idempotencyKey = null, note = null,
        createdAt = "2026-07-01T10:00:00.000000Z", updatedAt = "2026-07-01T10:00:00.000000Z"
    )

    private fun localAsset(assetId: Long, serverId: Long) = OfflineEquipmentAssetEntity(
        assetId = assetId, serverId = serverId, uuid = "loc-$assetId", companyId = 7L,
        status = "deployed", createdAt = Date(), updatedAt = Date()
    )

    private fun page(vararg dtos: EquipmentAssetDto) =
        EquipmentAssetPageResponse(dtos.toList(), PaginationMeta(1, 1, 100, dtos.size))

    @Test
    fun `pool snapshot marks clean local assets missing from the server as deleted`() = runTest {
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns page(assetDto(900))
        // Locally we have 900 (still present) and 901 (server dropped it).
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns
            listOf(localAsset(50L, 900L), localAsset(51L, 901L))
        // #3 guard: neither is deployed locally, so the missing one is safe to delete.
        coEvery { local.getOpenPlacementForAsset(any()) } returns null
        val saves = mutableListOf<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saves), any()) } just Runs
        // No room server id → skip the room reconciliation branch.
        coEvery { local.getRoom(any()) } returns null

        val result = service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        assertThat(result.isSuccess).isTrue()
        val deleted = saves.flatten().filter { it.isDeleted }
        assertThat(deleted.map { it.serverId }).containsExactly(901L)
    }

    @Test
    fun `an asset missing from the pool but deployed locally is NOT deleted`() = runTest {
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns page(assetDto(900))
        // 901 is absent from the pool response but still has a local open placement.
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns
            listOf(localAsset(50L, 900L), localAsset(51L, 901L))
        coEvery { local.getOpenPlacementForAsset(51L) } returns OfflineEquipmentPlacementEntity(
            placementId = 70L, serverId = 12L, uuid = "open", assetId = 51L, roomId = 400L,
            isOpen = true, createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getOpenPlacementForAsset(50L) } returns null
        val saves = mutableListOf<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saves), any()) } just Runs
        coEvery { local.getRoom(any()) } returns null

        service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        // #3: 901 is deployed locally → must NOT be marked deleted despite being absent from the pool.
        assertThat(saves.flatten().none { it.isDeleted }).isTrue()
    }

    @Test
    fun `empty placement history removes a stale local open placement`() = runTest {
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns page(assetDto(900))
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns listOf(localAsset(50L, 900L))
        coEvery { local.getRoom(400L) } returns PushHandlerTestFixtures.createRoom(roomId = 400L, serverId = 4000L)
        coEvery { api.getRoomEquipmentAssets(4000L) } returns SingleDataResponse(listOf(assetDto(900)))
        coEvery { local.getEquipmentAssetByServerId(900L) } returns localAsset(50L, 900L)
        // Server says this asset has NO placements, but locally an open one lingers.
        coEvery { api.getEquipmentAssetPlacements(900L) } returns SingleDataResponse(emptyList<EquipmentAssetPlacementDto>())
        coEvery { local.getSyncedPlacementsForAsset(50L) } returns listOf(
            OfflineEquipmentPlacementEntity(
                placementId = 60L, serverId = 12L, uuid = "p", assetId = 50L, roomId = 400L,
                isOpen = true, createdAt = Date(), updatedAt = Date()
            )
        )
        coEvery { local.getCleanOpenPlacementsForRoom(400L) } returns emptyList()
        val placementSaves = mutableListOf<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(placementSaves), any()) } just Runs

        val result = service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        assertThat(result.isSuccess).isTrue()
        val deleted = placementSaves.flatten().filter { it.isDeleted }
        assertThat(deleted.map { it.serverId }).contains(12L)
        assertThat(deleted.all { !it.isOpen }).isTrue()
    }

    @Test
    fun `dirty metadata with a live placement keeps local edits and PRESERVES the base lock`() = runTest {
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns
            page(assetDto(900).copy(name = "Server Name", status = "available"))
        val editBase = java.util.Date(1000L)
        val existing = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "loc", companyId = 7L,
            name = "Local Edit", status = "deployed", isDirty = true,
            serverUpdatedAt = editBase, createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAssetsByServerIds(listOf(900L)) } returns listOf(existing)
        coEvery { local.getPendingEquipmentPlacements() } returns listOf(
            OfflineEquipmentPlacementEntity(
                placementId = 60L, serverId = null, uuid = "p", assetId = 50L, roomId = 400L,
                isOpen = true, isDirty = true, createdAt = Date(), updatedAt = Date()
            )
        )
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns emptyList()
        coEvery { local.getRoom(400L) } returns null // skip room reconcile
        val saves = mutableListOf<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saves), any()) } just Runs

        service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        val merged = saves.flatten().first { it.serverId == 900L }
        assertThat(merged.name).isEqualTo("Local Edit")   // metadata preserved
        assertThat(merged.status).isEqualTo("deployed")   // optimistic lifecycle preserved
        assertThat(merged.isDirty).isTrue()
        // Blocker #2: the edit-base lock is PRESERVED (not rebased onto the server's newer stamp),
        // so a genuine cross-client change still produces a 409.
        assertThat(merged.serverUpdatedAt).isEqualTo(editBase)
    }

    @Test
    fun `dirty metadata WITHOUT a live placement adopts server lifecycle (two-axis)`() = runTest {
        // Server moved the asset (deployed) while a local metadata edit is pending; no live op.
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns
            page(assetDto(900).copy(name = "Server Name", status = "deployed", currentPlacementId = 77L))
        val existing = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "loc", companyId = 7L,
            name = "Local Edit", status = "available", currentPlacementServerId = null, isDirty = true,
            createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAssetsByServerIds(listOf(900L)) } returns listOf(existing)
        coEvery { local.getPendingEquipmentPlacements() } returns emptyList() // no live op
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns emptyList()
        coEvery { local.getRoom(400L) } returns null
        val saves = mutableListOf<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saves), any()) } just Runs

        service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        val merged = saves.flatten().first { it.serverId == 900L }
        assertThat(merged.name).isEqualTo("Local Edit")            // metadata axis: local
        assertThat(merged.status).isEqualTo("deployed")            // lifecycle axis: server (no live op)
        assertThat(merged.currentPlacementServerId).isEqualTo(77L) // lifecycle axis: server
    }

    @Test
    fun `pending maintenance edit survives a pull while server is still available`() = runTest {
        // Local pending available→maintenance edit, no placement op; server still "available".
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns
            page(assetDto(900).copy(status = "available"))
        val existing = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "loc", companyId = 7L,
            name = "Air Mover", status = "maintenance", isDirty = true,
            createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAssetsByServerIds(listOf(900L)) } returns listOf(existing)
        coEvery { local.getPendingEquipmentPlacements() } returns emptyList()
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns emptyList()
        coEvery { local.getRoom(400L) } returns null
        val saves = mutableListOf<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saves), any()) } just Runs

        service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        // The pending maintenance edit is preserved (server still in the editable range).
        assertThat(saves.flatten().first { it.serverId == 900L }.status).isEqualTo("maintenance")
    }

    @Test
    fun `a FAILED placement does not protect optimistic lifecycle`() = runTest {
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns
            page(assetDto(900).copy(status = "available"))
        val existing = OfflineEquipmentAssetEntity(
            assetId = 50L, serverId = 900L, uuid = "loc", companyId = 7L,
            name = "Air Mover", status = "deployed", isDirty = false,
            createdAt = Date(), updatedAt = Date()
        )
        coEvery { local.getEquipmentAssetsByServerIds(listOf(900L)) } returns listOf(existing)
        coEvery { local.getPendingEquipmentPlacements() } returns listOf(
            OfflineEquipmentPlacementEntity(
                placementId = 60L, serverId = 12L, uuid = "p", assetId = 50L, roomId = 400L,
                isOpen = true, isDirty = false, syncStatus = com.example.rocketplan_android.data.local.SyncStatus.FAILED,
                createdAt = Date(), updatedAt = Date()
            )
        )
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns emptyList()
        coEvery { local.getRoom(400L) } returns null
        val saves = mutableListOf<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(saves), any()) } just Runs

        service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        // FAILED op must NOT keep the stale "deployed" — the server's "available" wins.
        val merged = saves.flatten().first { it.serverId == 900L }
        assertThat(merged.status).isEqualTo("available")
    }

    @Test
    fun `pagination failure aborts without deleting anything`() = runTest {
        // Page 1 says there are 2 pages; page 2 throws → the whole pull fails and we must
        // NOT treat a partial snapshot as authoritative (no stale deletion).
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns
            EquipmentAssetPageResponse(listOf(assetDto(900)), PaginationMeta(1, 2, 100, 2))
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 2) } throws RuntimeException("network")
        val assetSaves = mutableListOf<List<OfflineEquipmentAssetEntity>>()
        coEvery { local.saveEquipmentAssets(capture(assetSaves), any()) } just Runs

        val result = service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        assertThat(result.isFailure).isTrue()
        // Only the page-1 upsert happened; no reconciliation-deletion was performed.
        assertThat(assetSaves.flatten().none { it.isDeleted }).isTrue()
        io.mockk.coVerify(exactly = 0) { local.getSyncedEquipmentAssetsForCompany(any()) }
    }

    @Test
    fun `open placement whose asset left the room is closed`() = runTest {
        coEvery { api.getCompanyEquipmentAssets(7L, any(), any(), any(), 100, 1) } returns page()
        coEvery { local.getSyncedEquipmentAssetsForCompany(7L) } returns emptyList()
        coEvery { local.getRoom(400L) } returns PushHandlerTestFixtures.createRoom(roomId = 400L, serverId = 4000L)
        // Room no longer lists asset 900 (moved out by another client).
        coEvery { api.getRoomEquipmentAssets(4000L) } returns SingleDataResponse(emptyList<EquipmentAssetDto>())
        coEvery { local.getCleanOpenPlacementsForRoom(400L) } returns listOf(
            OfflineEquipmentPlacementEntity(
                placementId = 60L, serverId = 12L, uuid = "p", assetId = 50L, roomId = 400L,
                isOpen = true, createdAt = Date(), updatedAt = Date()
            )
        )
        coEvery { local.getEquipmentAsset(50L) } returns localAsset(50L, 900L)
        val placementSaves = mutableListOf<List<OfflineEquipmentPlacementEntity>>()
        coEvery { local.saveEquipmentPlacements(capture(placementSaves), any()) } just Runs

        service.refreshRoom(roomLocalId = 400L, companyId = 7L)

        val deleted = placementSaves.flatten().filter { it.isDeleted }
        assertThat(deleted.map { it.serverId }).contains(12L)
    }
}
