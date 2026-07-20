package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * RP-FR-019 — write-side orchestration for serialized equipment.
 *
 * Review #3/#4: reads, invariant checks, entity writes AND the queue insertion all
 * run inside a single Room transaction ([LocalDataService.runInTransaction]) so
 * (a) a crash can't leave a mutated row without its queued op, and (b) two rapid
 * calls can't both pass validation — the second transaction sees the first's write.
 *
 * Review #2: lifecycle status flips (deploy→deployed, check-out→available) mark the
 * asset dirty so a pull can't revert the optimistic status before the placement op
 * syncs. Nothing auto-enqueues pending assets, so this does NOT create a spurious
 * metadata update; the placement handler clears the flag from the server response.
 */
class EquipmentAssetSyncService(
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    /**
     * Register a new unit into the company pool (status = available).
     * Review #6: [catalogUuid] is required — no UUID fallback.
     */
    suspend fun registerAsset(
        companyId: Long,
        name: String,
        catalogUuid: String,
        manufacturer: String? = null,
        model: String? = null,
        serialNumber: String? = null,
        assetTag: String? = null,
        isStandard: Boolean = true
    ): OfflineEquipmentAssetEntity = localDataService.runInTransaction {
        val timestamp = now()
        val uuid = UuidUtils.generateUuidV7()
        val entity = OfflineEquipmentAssetEntity(
            assetId = -System.currentTimeMillis(),
            serverId = null,
            uuid = uuid,
            companyId = companyId,
            catalogUuid = catalogUuid,
            name = name,
            manufacturer = manufacturer,
            model = model,
            serialNumber = serialNumber,
            assetTag = assetTag,
            isStandard = isStandard,
            status = "available",
            createdAt = timestamp,
            updatedAt = timestamp,
            syncStatus = SyncStatus.PENDING,
            isDirty = true
        )
        localDataService.saveEquipmentAssets(listOf(entity))
        val saved = localDataService.getEquipmentAssetByUuid(uuid) ?: entity
        syncQueueEnqueuer().enqueueEquipmentAssetUpsert(saved)
        saved
    }

    /** Edit an existing asset's metadata (also flips available/maintenance status). */
    suspend fun updateAsset(asset: OfflineEquipmentAssetEntity): OfflineEquipmentAssetEntity =
        localDataService.runInTransaction {
            val lockUpdatedAt = asset.serverId?.let { (asset.serverUpdatedAt ?: asset.updatedAt).toApiTimestamp() }
            val updated = asset.copy(updatedAt = now(), syncStatus = SyncStatus.PENDING, isDirty = true)
            localDataService.saveEquipmentAssets(listOf(updated))
            syncQueueEnqueuer().enqueueEquipmentAssetUpsert(updated, lockUpdatedAt)
            updated
        }

    /**
     * Deploy (check-in) an available asset into a room. Invariants (asset available,
     * not retired, no open placement, room in the asset's company) are checked INSIDE
     * the transaction (#3). Returns null (nothing persisted) on any violation.
     */
    suspend fun deployAsset(
        assetLocalId: Long,
        roomLocalId: Long,
        projectLocalId: Long? = null,
        dateIn: Date? = null,
        note: String? = null
    ): OfflineEquipmentPlacementEntity? = localDataService.runInTransaction {
        val asset = localDataService.getEquipmentAsset(assetLocalId)
            ?: return@runInTransaction null
        if (asset.isDeleted || asset.status != "available") {
            return@runInTransaction reject("deploy", asset.uuid, "not available (status=${asset.status})")
        }
        if (localDataService.getOpenPlacementForAsset(asset.assetId) != null) {
            return@runInTransaction reject("deploy", asset.uuid, "already has an open placement")
        }
        val room = localDataService.getRoom(roomLocalId)
            ?: return@runInTransaction reject("deploy", asset.uuid, "room $roomLocalId not found")
        val roomCompanyId = localDataService.getProject(room.projectId)?.companyId
        if (roomCompanyId != null && roomCompanyId != asset.companyId) {
            return@runInTransaction reject("deploy", asset.uuid, "room company $roomCompanyId != ${asset.companyId}")
        }

        val timestamp = now()
        val uuid = UuidUtils.generateUuidV7()
        val placement = OfflineEquipmentPlacementEntity(
            placementId = -System.currentTimeMillis(),
            serverId = null,
            uuid = uuid,
            assetId = asset.assetId,
            roomId = roomLocalId,
            projectId = projectLocalId ?: room.projectId,
            dateIn = dateIn ?: timestamp,
            dateOut = null,
            note = note,
            isOpen = true,
            createdAt = timestamp,
            updatedAt = timestamp,
            syncStatus = SyncStatus.PENDING,
            isDirty = true
        )
        localDataService.saveEquipmentPlacements(listOf(placement))
        // Review #3/#4: flip the optimistic STATUS only — do NOT touch isDirty (which is
        // metadata-dirtiness). The pull protects this status via the pending placement op
        // (see EquipmentAssetPullService), and the deploy handler refreshes with
        // preserveDirty=true so a genuine metadata edit is never clobbered.
        localDataService.saveEquipmentAssets(listOf(asset.copy(status = "deployed", updatedAt = timestamp)))
        val saved = localDataService.getEquipmentPlacementByUuid(uuid) ?: placement
        syncQueueEnqueuer().enqueuePlacementDeploy(saved)
        saved
    }

    /**
     * Move a deployed asset to another room (Phase 1c). Reads + checks inside the txn (#3).
     *
     * Review #2 compaction: if the deploy hasn't synced yet (open placement still has a
     * PENDING CREATE), we just retarget the pending CREATE's room and keep it — enqueuing a
     * separate MOVE would remove the CREATE and the server would never receive the check-in.
     */
    suspend fun moveAsset(assetLocalId: Long, toRoomLocalId: Long): OfflineEquipmentPlacementEntity? =
        localDataService.runInTransaction {
            val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@runInTransaction null
            val open = localDataService.getOpenPlacementForAsset(asset.assetId) ?: return@runInTransaction null
            if (open.roomId == toRoomLocalId) return@runInTransaction null
            // Review #5: never compact around an in-flight op — it may already be on the wire.
            if (hasInFlightPlacementOp(open.placementId)) {
                return@runInTransaction reject("move", asset.uuid, "a placement op is in flight — retry after it settles")
            }
            val room = localDataService.getRoom(toRoomLocalId) ?: return@runInTransaction null
            val roomCompanyId = localDataService.getProject(room.projectId)?.companyId
            if (roomCompanyId != null && roomCompanyId != asset.companyId) return@runInTransaction null

            // Review round-10 #2: if the placement isn't server-known, only compact when the deploy is
            // PROVEN never dispatched (PENDING). A FAILED deploy may already be committed server-side
            // (lost response) — re-keying it as a fresh deploy/move could 422 against an already-
            // deployed asset, so reject and let it reconcile.
            if (open.serverId == null && !isDeployProvenNeverDispatched(open.placementId)) {
                return@runInTransaction reject("move", asset.uuid, "deploy hasn't finished syncing — retry after it settles")
            }
            val updated = open.copy(
                roomId = toRoomLocalId,
                projectId = room.projectId,
                updatedAt = now(),
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.saveEquipmentPlacements(listOf(updated))
            if (open.serverId == null) {
                // PENDING deploy → retarget the ORIGINAL deploy op (same idempotency key); it will
                // deploy to the new room. Do NOT enqueue a fresh op (that would change the key).
            } else {
                syncQueueEnqueuer().enqueuePlacementMove(updated)
            }
            updated
        }

    /**
     * Check a deployed asset back out to the pool (Phase 1c). Reads + write inside the txn (#3).
     *
     * Review #2 compaction: if the deploy hasn't synced yet (PENDING CREATE), collapse both
     * ops — drop the CREATE, soft-delete the never-synced placement, and free the asset. The
     * server never saw the deploy, so nothing is sent.
     */
    suspend fun checkOutAsset(assetLocalId: Long): OfflineEquipmentPlacementEntity? =
        localDataService.runInTransaction {
            val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@runInTransaction null
            val open = localDataService.getOpenPlacementForAsset(asset.assetId) ?: return@runInTransaction null
            // Review #5: never compact around an in-flight op — it may already be on the wire.
            if (hasInFlightPlacementOp(open.placementId)) {
                return@runInTransaction reject("check-out", asset.uuid, "a placement op is in flight — retry after it settles")
            }
            val timestamp = now()

            // Review round-10 #2: only collapse when the deploy is PROVEN never dispatched (PENDING).
            // A FAILED deploy may already be committed server-side, so collapsing it locally would
            // leave the server deployed forever — reject and let it reconcile instead.
            if (open.serverId == null && !isDeployProvenNeverDispatched(open.placementId)) {
                return@runInTransaction reject("check-out", asset.uuid, "deploy hasn't finished syncing — retry after it settles")
            }
            if (open.serverId == null) {
                localDataService.removeSyncOperationsForEntity("equipment_asset_placement", open.placementId)
                val closed = open.copy(
                    isDeleted = true, isOpen = false, dateOut = timestamp,
                    isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = timestamp
                )
                localDataService.saveEquipmentPlacements(listOf(closed))
                // Free the asset. If it's server-known, the deploy never reached the server so it's
                // still available there → clean it; if it's itself unsynced, keep it dirty for register.
                val freed = if (asset.serverId == null) {
                    asset.copy(status = "available", updatedAt = timestamp)
                } else {
                    asset.copy(status = "available", isDirty = false, syncStatus = SyncStatus.SYNCED, updatedAt = timestamp, lastSyncedAt = timestamp)
                }
                localDataService.saveEquipmentAssets(listOf(freed))
                return@runInTransaction closed
            }

            val updated = open.copy(
                dateOut = timestamp,
                // Review round-9 (H6): close the placement optimistically so it matches the asset's
                // `available` status (and drops out of the room's deployed list) before the server confirms.
                isOpen = false,
                updatedAt = timestamp,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.saveEquipmentPlacements(listOf(updated))
            // Review #3/#4: optimistic STATUS only (see deployAsset). The pending checkout op
            // protects it from the pull; isDirty stays reserved for metadata edits.
            localDataService.saveEquipmentAssets(listOf(asset.copy(status = "available", updatedAt = timestamp)))
            syncQueueEnqueuer().enqueuePlacementCheckout(updated)
            updated
        }

    /**
     * RP-FR-030 — correct a CLOSED placement's dates. Stages the placement dirty with the new
     * dates and enqueues a correction op (distinct entityType). Reads + invariant checks + write +
     * enqueue all inside one transaction (#3). Returns null on any violation.
     *
     * [dateIn]/[dateOut] are date-only calendar values (UTC midnight) captured by the UI; a null
     * argument means "leave this date unchanged". The placement must be server-known (serverId !=
     * null) — you cannot correct a placement the server has never seen.
     */
    suspend fun correctPlacement(
        placementLocalId: Long,
        dateIn: Date?,
        dateOut: Date?
    ): OfflineEquipmentPlacementEntity? = localDataService.runInTransaction {
        val placement = localDataService.getEquipmentPlacement(placementLocalId)
            ?: return@runInTransaction null
        if (placement.isDeleted) {
            return@runInTransaction reject("correct", placement.uuid, "placement is deleted")
        }
        // Only server-known placements can be corrected (the lock is the placement's own updated_at).
        if (placement.serverId == null) {
            return@runInTransaction reject("correct", placement.uuid, "placement not server-known yet")
        }
        // Only CLOSED placements are date-corrected from the history screen; an open placement has
        // no date_out and correcting it is out of scope (and would risk a reopen 422).
        if (placement.isOpen || placement.dateOut == null) {
            return@runInTransaction reject("correct", placement.uuid, "cannot correct an open placement")
        }
        if (hasInFlightPlacementOp(placement.placementId)) {
            return@runInTransaction reject("correct", placement.uuid, "a placement op is in flight — retry after it settles")
        }
        val newDateIn = dateIn ?: placement.dateIn
        val newDateOut = dateOut ?: placement.dateOut
        if (newDateIn == null) {
            return@runInTransaction reject("correct", placement.uuid, "a placement must have a deploy date")
        }
        if (newDateOut != null && newDateOut.before(newDateIn)) {
            return@runInTransaction reject("correct", placement.uuid, "date_out is before date_in")
        }
        val updated = placement.copy(
            dateIn = newDateIn,
            dateOut = newDateOut,
            updatedAt = now(),
            syncStatus = SyncStatus.PENDING,
            isDirty = true
        )
        localDataService.saveEquipmentPlacements(listOf(updated))
        syncQueueEnqueuer().enqueuePlacementCorrection(updated)
        updated
    }

    /**
     * RP-FR-031 — delete a CLOSED placement. Marks the local row deleted and enqueues a delete op.
     * Client-side guard: refuse to delete an OPEN placement (that's a check-out). If the placement
     * never reached the server (serverId == null), collapse locally — drop its queued ops and
     * soft-delete it — instead of sending a delete for a row the server doesn't know.
     *
     * The pending-delete row is kept `isDirty=true` so an authoritative pull cannot resurrect it
     * before the delete syncs (mergePulledRowsByServerId preserves dirty local rows).
     */
    suspend fun deletePlacement(placementLocalId: Long): OfflineEquipmentPlacementEntity? =
        localDataService.runInTransaction {
            val placement = localDataService.getEquipmentPlacement(placementLocalId)
                ?: return@runInTransaction null
            if (placement.isDeleted) return@runInTransaction null
            // Client-side guard: only CLOSED placements are deletable; end an active one via check-out.
            if (placement.isOpen || placement.dateOut == null) {
                return@runInTransaction reject("delete", placement.uuid, "cannot delete an open placement — check out first")
            }
            if (hasInFlightPlacementOp(placement.placementId)) {
                return@runInTransaction reject("delete", placement.uuid, "a placement op is in flight — retry after it settles")
            }
            val timestamp = now()
            if (placement.serverId == null) {
                // Never synced — collapse locally: drop any queued lifecycle op and soft-delete.
                localDataService.removeSyncOperationsForEntity("equipment_asset_placement", placement.placementId)
                val deleted = placement.copy(
                    isDeleted = true, isOpen = false, isDirty = false,
                    syncStatus = SyncStatus.SYNCED, lastSyncedAt = timestamp
                )
                localDataService.saveEquipmentPlacements(listOf(deleted))
                return@runInTransaction deleted
            }
            val deleted = placement.copy(
                isDeleted = true,
                isOpen = false,
                updatedAt = timestamp,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.saveEquipmentPlacements(listOf(deleted))
            syncQueueEnqueuer().enqueuePlacementDelete(deleted)
            deleted
        }

    /** True if a placement op for this row is currently being synced (SYNCING) — don't touch it. */
    private suspend fun hasInFlightPlacementOp(placementLocalId: Long): Boolean =
        localDataService.getSyncOperationForEntity(
            "equipment_asset_placement", placementLocalId, SyncStatus.SYNCING
        ) != null

    /**
     * Review round-10 #2: a placement is PROVEN never dispatched only when its op is still PENDING
     * (queued, never attempted). serverId==null alone is NOT proof — a FAILED op was dispatched and
     * its response may have been lost, leaving the server deployed. Returns true only for a PENDING
     * deploy CREATE, i.e. the sole case where local compaction is safe.
     */
    private suspend fun isDeployProvenNeverDispatched(placementLocalId: Long): Boolean =
        localDataService.getSyncOperationForEntity(
            "equipment_asset_placement", placementLocalId, SyncStatus.PENDING
        )?.operationType == SyncOperationType.CREATE

    /**
     * Retire an asset. Review #7: if it never reached the server, collapse the WHOLE
     * unsynced graph atomically — drop its asset AND placement queue ops and soft-delete
     * its local placements — so orphaned placement ops can't wait forever for a
     * registration that was removed.
     */
    suspend fun retireAsset(assetLocalId: Long): OfflineEquipmentAssetEntity? =
        localDataService.runInTransaction {
            val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@runInTransaction null
            val timestamp = now()
            // Review #5: a server-known asset with an open placement must be checked out first —
            // retiring it would 422 on the backend and strand the asset locally-deleted. (An
            // unsynced asset's open placement is collapsed below.)
            if (asset.serverId != null && localDataService.getOpenPlacementForAsset(asset.assetId) != null) {
                return@runInTransaction reject("retire", asset.uuid, "asset still has an open placement")
            }
            if (asset.serverId == null) {
                val placements = localDataService.getAllPlacementsForAsset(asset.assetId)
                placements.forEach {
                    localDataService.removeSyncOperationsForEntity("equipment_asset_placement", it.placementId)
                }
                localDataService.removeSyncOperationsForEntity("equipment_asset", asset.assetId)
                if (placements.isNotEmpty()) {
                    localDataService.saveEquipmentPlacements(
                        placements.map {
                            it.copy(isDeleted = true, isOpen = false, isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = timestamp)
                        }
                    )
                }
                val cleaned = asset.copy(
                    status = "retired", isDeleted = true, isDirty = false,
                    syncStatus = SyncStatus.SYNCED, updatedAt = timestamp, lastSyncedAt = timestamp
                )
                localDataService.saveEquipmentAssets(listOf(cleaned))
                cleaned
            } else {
                val lockUpdatedAt = (asset.serverUpdatedAt ?: asset.updatedAt).toApiTimestamp()
                val updated = asset.copy(
                    status = "retired", isDeleted = true, isDirty = true,
                    syncStatus = SyncStatus.PENDING, updatedAt = timestamp
                )
                localDataService.saveEquipmentAssets(listOf(updated))
                syncQueueEnqueuer().enqueueEquipmentAssetRetire(updated, lockUpdatedAt)
                updated
            }
        }

    private fun reject(op: String, assetUuid: String, reason: String): Nothing? {
        android.util.Log.w("EquipmentAssetSyncService", "Rejecting $op of $assetUuid: $reason")
        return null
    }
}
