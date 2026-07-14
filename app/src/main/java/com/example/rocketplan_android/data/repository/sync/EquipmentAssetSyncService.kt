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
 * Phase 2 covers register / deploy / retire (the actions whose sync landed in
 * Phase 1b). Move / check-out arrive with Phase 1c.
 */
class EquipmentAssetSyncService(
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    /** Register a new unit into the company pool (status = available). */
    suspend fun registerAsset(
        companyId: Long,
        name: String,
        catalogUuid: String? = null,
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
            catalogUuid = catalogUuid ?: uuid,
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
        withContext(ioDispatcher) {
            val lockUpdatedAt = asset.serverId?.let { (asset.serverUpdatedAt ?: asset.updatedAt).toApiTimestamp() }
            val updated = asset.copy(
                updatedAt = now(),
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.saveEquipmentAssets(listOf(updated))
            syncQueueEnqueuer().enqueueEquipmentAssetUpsert(updated, lockUpdatedAt)
            updated
        }

    /** Deploy (check-in) an available asset into a room. */
    suspend fun deployAsset(
        assetLocalId: Long,
        roomLocalId: Long,
        projectLocalId: Long? = null,
        dateIn: Date? = null,
        note: String? = null
    ): OfflineEquipmentPlacementEntity? = withContext(ioDispatcher) {
        val asset = localDataService.getEquipmentAsset(assetLocalId) ?: return@withContext null
        val timestamp = now()
        val uuid = UuidUtils.generateUuidV7()
        val placement = OfflineEquipmentPlacementEntity(
            placementId = -System.currentTimeMillis(),
            serverId = null,
            uuid = uuid,
            assetId = asset.assetId,
            roomId = roomLocalId,
            projectId = projectLocalId,
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
        // Optimistically reflect on the local asset so the pool view updates immediately.
        if (!asset.isDirty) {
            localDataService.saveEquipmentAssets(listOf(asset.copy(status = "deployed")))
        }
        val saved = localDataService.getEquipmentPlacementByUuid(uuid) ?: placement
        syncQueueEnqueuer().enqueuePlacementDeploy(saved)
        saved
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
        localDataService.saveEquipmentAssets(listOf(updated))
        if (asset.serverId == null) {
            localDataService.removeSyncOperationsForEntity("equipment_asset", asset.assetId)
            val cleaned = updated.copy(isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = now())
            localDataService.saveEquipmentAssets(listOf(cleaned))
            return@withContext cleaned
        }
        syncQueueEnqueuer().enqueueEquipmentAssetRetire(updated, lockUpdatedAt)
        updated
    }
}
