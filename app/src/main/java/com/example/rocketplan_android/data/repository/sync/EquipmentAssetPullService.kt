package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.model.offline.EquipmentAssetDto
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementDto
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
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
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private companion object {
        /** Statuses a client may set via updateAsset (metadata edit), vs lifecycle "deployed"/"retired". */
        val EDITABLE_STATUSES = setOf("available", "maintenance")
    }

    suspend fun refreshRoom(roomLocalId: Long, companyId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                // 1. Company pool. Review round-9 #1/#2: only treat it as an AUTHORITATIVE snapshot
                //    (and delete missing rows) when the pagination is provably COMPLETE — otherwise a
                //    null/omitted `meta` or a spurious empty 200 would silently wipe the local pool.
                val pool = pullCompanyPool(companyId)
                if (pool.complete) {
                    markMissingAssetsDeleted(companyId, pool.seen)
                }

                // 2. Room deployed assets — authoritative set of open placements in this room.
                val roomServerId = localDataService.getRoom(roomLocalId)?.serverId
                    ?: return@runCatching // can't reconcile the room without its server id
                val roomAssets = api.getRoomEquipmentAssets(roomServerId).data
                saveAssetsProtectingLifecycle(roomAssets)
                val roomAssetServerIds = roomAssets.map { it.id }.toSet()

                // 3. Per-asset placement history — authoritative per asset (incl. empty history).
                for (dto in roomAssets) {
                    val local = localDataService.getEquipmentAssetByServerId(dto.id) ?: continue
                    val placements = api.getEquipmentAssetPlacements(dto.id).data
                    reconcileAssetPlacements(local.assetId, placements)
                }

                // 4. Close clean local OPEN placements in this room whose asset the server no longer
                //    lists here (moved out / checked out / retired by another client). Review #5: only
                //    close when we can positively confirm the asset is server-known and absent from the
                //    room set — a serverId-less asset can't be proven missing, so leave it alone.
                for (open in localDataService.getCleanOpenPlacementsForRoom(roomLocalId)) {
                    val serverId = localDataService.getEquipmentAsset(open.assetId)?.serverId
                    if (serverId != null && serverId !in roomAssetServerIds) {
                        localDataService.saveEquipmentPlacements(listOf(open.markReconciledDeleted()))
                    }
                }
            }.onFailure { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                remoteLogger?.log(
                    LogLevel.WARN, "API", "Serialized equipment pull failed",
                    mapOf(
                        "roomId" to roomLocalId.toString(),
                        "companyId" to companyId.toString(),
                        "error" to (e::class.java.simpleName + ": " + (e.message ?: ""))
                    )
                )
            }
        }

    private data class PoolSnapshot(val seen: Set<Long>, val complete: Boolean)

    /**
     * Pulls (and upserts) the whole company pool. `complete` is true ONLY when the pagination
     * proves it — every page carried a well-formed `meta` and we reached currentPage >= lastPage,
     * or an empty page was confirmed authoritative by `meta.total == 0`. A null/omitted `meta`
     * yields `complete = false` so no reconciliation-deletion runs on a partial/uncertain pull.
     */
    private suspend fun pullCompanyPool(companyId: Long): PoolSnapshot {
        val seen = mutableSetOf<Long>()
        var page = 1
        val maxPages = 1000
        while (page <= maxPages) {
            val resp = api.getCompanyEquipmentAssets(companyId = companyId, perPage = 100, page = page)
            val meta = resp.meta
            if (resp.data.isEmpty()) {
                return PoolSnapshot(seen, complete = meta?.total == 0)
            }
            saveAssetsProtectingLifecycle(resp.data)
            seen += resp.data.map { it.id }
            val current = meta?.currentPage
            val last = meta?.lastPage
            if (current == null || last == null) {
                return PoolSnapshot(seen, complete = false)
            }
            if (current >= last) {
                val total = meta.total
                return PoolSnapshot(seen, complete = total == null || seen.size >= total)
            }
            page += 1
        }
        remoteLogger?.log(
            LogLevel.WARN, "API", "Equipment pool pagination exceeded cap",
            mapOf("companyId" to companyId.toString(), "pages" to maxPages.toString())
        )
        return PoolSnapshot(seen, complete = false)
    }

    /**
     * Review round-6 #3/#4: an equipment-specific field merge (not whole-row preserveDirty).
     *
     *  - Clean asset with no live placement op → adopt the server row fully (authoritative).
     *  - Otherwise (a pending metadata/retire edit, or a live placement op) → keep the local
     *    edited fields and optimistic lifecycle, BUT adopt the server's authoritative identity
     *    and — crucially — its fresh `serverUpdatedAt` (optimistic-lock token) so a subsequent
     *    pending update doesn't conflict on a stale timestamp after a lifecycle op changed the
     *    server asset.
     *
     * "Live placement op" is narrowed to PENDING/SYNCING (#4) — a FAILED op must not protect
     * optimistic state indefinitely.
     *
     * RP-BUG-336: when no serverId match exists, attempt natural-key adoption of a pending
     * register row (serverId==null) to prevent duplicate rows when the register response is lost.
     */
    private suspend fun saveAssetsProtectingLifecycle(dtos: List<EquipmentAssetDto>) {
        if (dtos.isEmpty()) return
        val servers = dtos.map { it.toEntity() }
        val existingByServer = localDataService
            .getEquipmentAssetsByServerIds(servers.mapNotNull { it.serverId })
            .associateBy { it.serverId }

        // Hoist: one query per distinct companyId before the loop (RP-BUG-340 efficiency fix).
        val unsyncedByNaturalKey = mutableMapOf<NaturalKey, OfflineEquipmentAssetEntity>()
        val seenKeys = mutableMapOf<NaturalKey, Long>()
        for (companyId in servers.map { it.companyId }.distinct()) {
            val unsynced = localDataService.getUnsyncedEquipmentAssets(companyId)
            for (asset in unsynced) {
                val key = NaturalKey(asset.companyId, asset.catalogUuid, asset.serialNumber, asset.name)
                val existingAssetId = seenKeys[key]
                if (existingAssetId != null && existingAssetId != asset.assetId) {
                    remoteLogger?.log(
                        LogLevel.WARN, "API", "Multiple unsynced assets matching natural key — adopting oldest",
                        mapOf("naturalKey" to key.toString(), "adoptingAssetId" to asset.assetId.toString())
                    )
                }
                val current = unsyncedByNaturalKey[key]
                if (current == null || asset.createdAt.before(current.createdAt)) {
                    unsyncedByNaturalKey[key] = asset
                    seenKeys[key] = asset.assetId
                }
            }
        }

        val livePlacementAssetIds = localDataService.getPendingEquipmentPlacements()
            .filter { it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.SYNCING }
            .map { it.assetId }
            .toSet()

        // RP-BUG-340: consume on adopt so each pending local row is adopted by at most one server row.
        val adoptedAssetIds = mutableSetOf<Long>()
        val merged = servers.map { server ->
            val local = existingByServer[server.serverId]
            if (local != null) {
                adoptOrMerge(server, local, livePlacementAssetIds)
            } else {
                val key = NaturalKey(server.companyId, server.catalogUuid, server.serialNumber, server.name)
                val naturalMatch = unsyncedByNaturalKey[key]?.takeIf { it.assetId !in adoptedAssetIds }
                if (naturalMatch != null) {
                    adoptedAssetIds += naturalMatch.assetId
                    unsyncedByNaturalKey.remove(key)
                    adoptOrMerge(server, naturalMatch, livePlacementAssetIds)
                } else {
                    server
                }
            }
        }
        localDataService.saveEquipmentAssets(merged)
    }

    private data class NaturalKey(
        val companyId: Long,
        val catalogUuid: String?,
        val serialNumber: String?,
        val name: String?
    )

    private fun adoptOrMerge(
        server: OfflineEquipmentAssetEntity,
        local: OfflineEquipmentAssetEntity,
        livePlacementAssetIds: Set<Long>
    ): OfflineEquipmentAssetEntity {
        val metadataDirty = local.isDirty
        val hasLivePlacement = local.assetId in livePlacementAssetIds
        if (!metadataDirty && !hasLivePlacement) {
            return server.copy(assetId = local.assetId, uuid = local.uuid)
        }
        return server.copy(
            assetId = local.assetId,
            uuid = local.uuid,
            name = if (metadataDirty) local.name else server.name,
            manufacturer = if (metadataDirty) local.manufacturer else server.manufacturer,
            model = if (metadataDirty) local.model else server.model,
            serialNumber = if (metadataDirty) local.serialNumber else server.serialNumber,
            assetTag = if (metadataDirty) local.assetTag else server.assetTag,
            vendor = if (metadataDirty) local.vendor else server.vendor,
            note = if (metadataDirty) local.note else server.note,
            purchaseDate = if (metadataDirty) local.purchaseDate else server.purchaseDate,
            purchasePrice = if (metadataDirty) local.purchasePrice else server.purchasePrice,
            warrantyExpiresAt = if (metadataDirty) local.warrantyExpiresAt else server.warrantyExpiresAt,
            rentalDayRate = if (metadataDirty) local.rentalDayRate else server.rentalDayRate,
            status = when {
                hasLivePlacement -> local.status
                metadataDirty && server.status in EDITABLE_STATUSES -> local.status
                else -> server.status
            },
            currentPlacementServerId = if (hasLivePlacement) local.currentPlacementServerId else server.currentPlacementServerId,
            serverUpdatedAt = if (metadataDirty) local.serverUpdatedAt else server.serverUpdatedAt,
            isDirty = metadataDirty,
            syncStatus = if (metadataDirty) local.syncStatus else server.syncStatus,
            isDeleted = if (metadataDirty) local.isDeleted else server.isDeleted,
            lastSyncedAt = server.lastSyncedAt
        )
    }

    private suspend fun markMissingAssetsDeleted(companyId: Long, seen: Set<Long>) {
        val missing = localDataService.getSyncedEquipmentAssetsForCompany(companyId)
            .filter { it.serverId != null && it.serverId !in seen }
            // Review #3: never delete an asset we still hold deployed locally, even if the company
            // index omitted it (defends against a backend index that excludes deployed rows).
            .filter { localDataService.getOpenPlacementForAsset(it.assetId) == null }
        if (missing.isNotEmpty()) {
            localDataService.saveEquipmentAssets(missing.map { it.markReconciledDeleted() })
            remoteLogger?.log(
                LogLevel.DEBUG, "API", "Pull reconcile removed missing pool assets",
                mapOf("companyId" to companyId.toString(), "count" to missing.size.toString())
            )
        }
    }

    private suspend fun reconcileAssetPlacements(assetLocalId: Long, dtos: List<EquipmentAssetPlacementDto>) {
        if (dtos.isEmpty()) {
            return
        }
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
        val serverIds = dtos.map { it.id }.toSet()
        val stale = localDataService.getSyncedPlacementsForAsset(assetLocalId)
            .filter { it.serverId !in serverIds }
        if (stale.isNotEmpty()) {
            localDataService.saveEquipmentPlacements(stale.map { it.markReconciledDeleted() })
            remoteLogger?.log(
                LogLevel.DEBUG, "API", "Pull reconcile closed stale placements",
                mapOf("assetId" to assetLocalId.toString(), "count" to stale.size.toString())
            )
        }
    }

    private fun OfflineEquipmentAssetEntity.markReconciledDeleted() =
        copy(isDeleted = true, isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = Date())

    private fun OfflineEquipmentPlacementEntity.markReconciledDeleted() =
        copy(isDeleted = true, isOpen = false, isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = Date())
}
