package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
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
 * RP-FR-019 — write-side orchestration for serialized equipment. Creates dirty
 * local entities and enqueues the matching sync operation. Mirrors
 * [EquipmentSyncService]; negative temp PKs for offline-created rows.
 *
 * Review #4: every method persists its entity change(s) AND the queue insertion
 * inside a single Room transaction ([LocalDataService.runInTransaction]) so a
 * crash can't leave a mutated local row without its queued op (or vice-versa).
 * Read-only validation stays outside the transaction.
 */
class EquipmentAssetSyncService(
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    /**
     * Register a new unit into the company pool (status = available).
     *
     * Review #6: [catalogUuid] is required — it must reference a real catalog item.
     * There is no UUID fallback (that created catalog-orphaned assets server-side).
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
    ): OfflineEquipmentAssetEntity = withContext(ioDispatcher) {
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
        localDataService.runInTransaction {
            localDataService.saveEquipmentAssets(listOf(entity))
            val saved = localDataService.getEquipmentAssetByUuid(uuid) ?: entity
            syncQueueEnqueuer().enqueueEquipmentAssetUpsert(saved)
            saved
        }
    }

    /** Edit an existing asset's metadata (also flips available/maintenance status). */
    suspend fun updateAsset(asset: OfflineEquipmentAssetEntity): OfflineEquipmentAssetEntity =
        withContext(ioDispatcher) {
            val lockUpdatedAt = asset.serverId?.let { (asset.serverUpdatedAt ?: asset.updatedAt).toApiTimestamp() }
            val updated = asset.copy(updatedAt = now(), syncStatus = SyncStatus.PENDING, isDirty = true)
            localDataService.runInTransaction {
                localDataService.saveEquipmentAssets(listOf(updated))
                syncQueueEnqueuer().enqueueEquipmentAssetUpsert(updated, lockUpdatedAt)
                updated
            }
        }

    /**
     * Deploy (check-in) an available asset into a room.
     *
     * Review #7: enforces local invariants before creating a placement — the asset
     * must be available, not retired, have no existing open placement, and the room
     * must belong to the asset's company. Returns null (rejected, nothing persisted)
     * on any violation. The placement, the asset's `deployed` flip, and the queue op
     * are all committed in one transaction (#4).
     */
    suspend fun deployAsset(
        assetLocalId: Long,
        roomLocalId: Long,
        projectLocalId: Long? = null,
        dateIn: Date? = null,
        note: String? = null
    ): OfflineEquipmentPlacementEntity? = withContext(ioDispatcher) {
        val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@withContext null
        if (asset.isDeleted || asset.status != "available") {
            logInvalid("deploy", asset.uuid, "asset not available (status=${asset.status}, deleted=${asset.isDeleted})")
            return@withContext null
        }
        if (localDataService.getOpenPlacementForAsset(asset.assetId) != null) {
            logInvalid("deploy", asset.uuid, "asset already has an open placement")
            return@withContext null
        }
        val room = localDataService.getRoom(roomLocalId)
        if (room == null) {
            logInvalid("deploy", asset.uuid, "room $roomLocalId not found")
            return@withContext null
        }
        val roomCompanyId = localDataService.getProject(room.projectId)?.companyId
        if (roomCompanyId != null && roomCompanyId != asset.companyId) {
            logInvalid("deploy", asset.uuid, "room company $roomCompanyId != asset company ${asset.companyId}")
            return@withContext null
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
        localDataService.runInTransaction {
            localDataService.saveEquipmentPlacements(listOf(placement))
            // Flip the asset to deployed (even if dirty) so it can't appear available while deployed.
            localDataService.saveEquipmentAssets(listOf(asset.copy(status = "deployed", updatedAt = timestamp)))
            val saved = localDataService.getEquipmentPlacementByUuid(uuid) ?: placement
            syncQueueEnqueuer().enqueuePlacementDeploy(saved)
            saved
        }
    }

    /**
     * Move a deployed asset to another room (Phase 1c). Updates the open placement's
     * room and enqueues a MOVE; the handler drives the server move + reconciles the
     * resulting placements. Returns null if there's no open placement, the target is
     * the same room, or it's cross-company.
     */
    suspend fun moveAsset(assetLocalId: Long, toRoomLocalId: Long): OfflineEquipmentPlacementEntity? =
        withContext(ioDispatcher) {
            val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@withContext null
            val open = localDataService.getOpenPlacementForAsset(asset.assetId) ?: return@withContext null
            if (open.roomId == toRoomLocalId) return@withContext null
            val room = localDataService.getRoom(toRoomLocalId) ?: return@withContext null
            val roomCompanyId = localDataService.getProject(room.projectId)?.companyId
            if (roomCompanyId != null && roomCompanyId != asset.companyId) return@withContext null

            val updated = open.copy(
                roomId = toRoomLocalId,
                projectId = room.projectId,
                updatedAt = now(),
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.runInTransaction {
                localDataService.saveEquipmentPlacements(listOf(updated))
                syncQueueEnqueuer().enqueuePlacementMove(updated)
                updated
            }
        }

    /** Check a deployed asset back out to the pool (Phase 1c). */
    suspend fun checkOutAsset(assetLocalId: Long): OfflineEquipmentPlacementEntity? =
        withContext(ioDispatcher) {
            val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@withContext null
            val open = localDataService.getOpenPlacementForAsset(asset.assetId) ?: return@withContext null
            val timestamp = now()
            val updated = open.copy(
                dateOut = timestamp,
                updatedAt = timestamp,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.runInTransaction {
                localDataService.saveEquipmentPlacements(listOf(updated))
                // Optimistically free the asset so the pool view updates immediately.
                localDataService.saveEquipmentAssets(listOf(asset.copy(status = "available", updatedAt = timestamp)))
                syncQueueEnqueuer().enqueuePlacementCheckout(updated)
                updated
            }
        }

    /** Retire an asset (soft-delete + status retired). */
    suspend fun retireAsset(assetLocalId: Long): OfflineEquipmentAssetEntity? = withContext(ioDispatcher) {
        val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@withContext null
        val lockUpdatedAt = asset.serverId?.let { (asset.serverUpdatedAt ?: asset.updatedAt).toApiTimestamp() }
        val updated = asset.copy(
            status = "retired",
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = now()
        )
        localDataService.runInTransaction {
            if (asset.serverId == null) {
                // Never reached the server — resolve locally, no queue op needed.
                localDataService.removeSyncOperationsForEntity("equipment_asset", asset.assetId)
                val cleaned = updated.copy(isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = now())
                localDataService.saveEquipmentAssets(listOf(cleaned))
                cleaned
            } else {
                localDataService.saveEquipmentAssets(listOf(updated))
                syncQueueEnqueuer().enqueueEquipmentAssetRetire(updated, lockUpdatedAt)
                updated
            }
        }
    }

    private fun logInvalid(op: String, assetUuid: String, reason: String) {
        android.util.Log.w("EquipmentAssetSyncService", "Rejecting $op of $assetUuid: $reason")
    }
}
