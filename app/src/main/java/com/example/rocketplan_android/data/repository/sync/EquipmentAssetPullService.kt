package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementDto
import com.example.rocketplan_android.data.repository.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * RP-FR-019 (review round 1 #1 + round 2 #1) — inbound pull for serialized
 * equipment, treated as an AUTHORITATIVE snapshot: rows the server no longer
 * returns are removed locally, not just left behind. Without the removal step a
 * cross-client move-out / check-out / retirement would leave the asset showing
 * as deployed here forever (or in both the room and the pool).
 *
 * Invariant: dirty local rows (a pending register/deploy/move/check-out) are
 * NEVER removed or overwritten — only clean, server-known rows are reconciled.
 */
class EquipmentAssetPullService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun refreshRoom(roomLocalId: Long, companyId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                // 1. Company pool — the completed pagination is an authoritative snapshot.
                val seenAssetIds = pullCompanyPool(companyId)
                markMissingAssetsDeleted(companyId, seenAssetIds)

                // 2. Room deployed assets — authoritative set of open placements in this room.
                val roomServerId = localDataService.getRoom(roomLocalId)?.serverId
                    ?: return@runCatching // can't reconcile the room without its server id
                val roomAssets = api.getRoomEquipmentAssets(roomServerId).data
                localDataService.saveEquipmentAssets(roomAssets.map { it.toEntity() }, preserveDirty = true)
                val roomAssetServerIds = roomAssets.map { it.id }.toSet()

                // 3. Per-asset placement history — authoritative per asset (incl. empty history).
                for (dto in roomAssets) {
                    val local = localDataService.getEquipmentAssetByServerId(dto.id) ?: continue
                    val placements = api.getEquipmentAssetPlacements(dto.id).data
                    reconcileAssetPlacements(local.assetId, placements)
                }

                // 4. Close clean local OPEN placements in this room whose asset the server no
                //    longer lists here (moved out / checked out / retired by another client).
                for (open in localDataService.getCleanOpenPlacementsForRoom(roomLocalId)) {
                    val serverId = localDataService.getEquipmentAsset(open.assetId)?.serverId
                    if (serverId == null || serverId !in roomAssetServerIds) {
                        localDataService.saveEquipmentPlacements(listOf(open.markReconciledDeleted()))
                    }
                }
            }
        }

    /** @return the set of server asset ids the company pool authoritatively contains. */
    private suspend fun pullCompanyPool(companyId: Long): Set<Long> {
        val seen = mutableSetOf<Long>()
        var page = 1
        while (true) {
            val resp = api.getCompanyEquipmentAssets(companyId = companyId, perPage = 100, page = page)
            if (resp.data.isEmpty()) break
            localDataService.saveEquipmentAssets(resp.data.map { it.toEntity() }, preserveDirty = true)
            seen += resp.data.map { it.id }
            val current = resp.meta?.currentPage ?: page
            val last = resp.meta?.lastPage ?: current
            if (current >= last) break
            page = current + 1
        }
        return seen
    }

    private suspend fun markMissingAssetsDeleted(companyId: Long, seen: Set<Long>) {
        val missing = localDataService.getSyncedEquipmentAssetsForCompany(companyId)
            .filter { it.serverId != null && it.serverId !in seen }
        if (missing.isNotEmpty()) {
            localDataService.saveEquipmentAssets(missing.map { it.markReconciledDeleted() })
        }
    }

    private suspend fun reconcileAssetPlacements(assetLocalId: Long, dtos: List<EquipmentAssetPlacementDto>) {
        if (dtos.isNotEmpty()) {
            val entities = dtos.map { dto ->
                val localRoom = dto.roomId?.let { localDataService.getRoomByServerId(it) }
                dto.toEntity(
                    existing = null,
                    assetLocalId = assetLocalId,
                    roomLocalId = localRoom?.roomId,
                    projectLocalId = localRoom?.projectId
                )
            }
            localDataService.saveEquipmentPlacements(entities, preserveDirty = true)
        }
        // Authoritative: any clean local placement the server didn't return is stale (incl. the
        // empty-history case, which is why we DON'T early-return above when dtos is empty).
        val serverIds = dtos.map { it.id }.toSet()
        val stale = localDataService.getSyncedPlacementsForAsset(assetLocalId)
            .filter { it.serverId !in serverIds }
        if (stale.isNotEmpty()) {
            localDataService.saveEquipmentPlacements(stale.map { it.markReconciledDeleted() })
        }
    }

    private fun OfflineEquipmentAssetEntity.markReconciledDeleted() =
        copy(isDeleted = true, isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = Date())

    private fun OfflineEquipmentPlacementEntity.markReconciledDeleted() =
        copy(isDeleted = true, isOpen = false, isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = Date())
}
