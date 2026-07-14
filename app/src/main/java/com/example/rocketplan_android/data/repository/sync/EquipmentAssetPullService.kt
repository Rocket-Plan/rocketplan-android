package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementDto
import com.example.rocketplan_android.data.repository.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RP-FR-019 (review #1) — inbound pull for serialized equipment. Without this the
 * UI would only ever show locally-created rows; server/backfilled assets and
 * changes from web/iOS would never appear.
 *
 * All saves use `preserveDirty = true` so an in-flight local edit (a pending
 * register/deploy/move/check-out) is never clobbered by the pull, and rows are
 * reconciled by serverId (no duplicates).
 */
class EquipmentAssetPullService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Refresh what a room screen needs: the company asset pool, the units currently
     * deployed in the room, and their placement history.
     */
    suspend fun refreshRoom(roomLocalId: Long, companyId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                pullCompanyPool(companyId)

                val roomServerId = localDataService.getRoom(roomLocalId)?.serverId
                if (roomServerId != null) {
                    val roomAssets = api.getRoomEquipmentAssets(roomServerId).data
                    localDataService.saveEquipmentAssets(roomAssets.map { it.toEntity() }, preserveDirty = true)
                    // Pull each deployed asset's placements so "deployed in this room" resolves.
                    for (dto in roomAssets) {
                        val local = localDataService.getEquipmentAssetByServerId(dto.id) ?: continue
                        val placements = api.getEquipmentAssetPlacements(dto.id).data
                        reconcilePlacements(local.assetId, placements)
                    }
                }
            }
        }

    private suspend fun pullCompanyPool(companyId: Long) {
        var page = 1
        while (true) {
            val resp = api.getCompanyEquipmentAssets(companyId = companyId, perPage = 100, page = page)
            if (resp.data.isEmpty()) break
            localDataService.saveEquipmentAssets(resp.data.map { it.toEntity() }, preserveDirty = true)
            val current = resp.meta?.currentPage ?: page
            val last = resp.meta?.lastPage ?: current
            if (current >= last) break
            page = current + 1
        }
    }

    private suspend fun reconcilePlacements(assetLocalId: Long, dtos: List<EquipmentAssetPlacementDto>) {
        if (dtos.isEmpty()) return
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
}
