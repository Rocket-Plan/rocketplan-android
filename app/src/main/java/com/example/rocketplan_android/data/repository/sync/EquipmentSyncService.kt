package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import com.example.rocketplan_android.util.UuidUtils

/**
 * Handles equipment CRUD operations and queues sync work via SyncQueueProcessor.
 */
class EquipmentSyncService(
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val logLocalDeletion: (String, Long, String?) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    suspend fun upsertEquipmentOffline(
        projectId: Long,
        roomId: Long?,
        type: String,
        brand: String? = null,
        model: String? = null,
        serialNumber: String? = null,
        quantity: Int = 1,
        status: String = "active",
        startDate: Date? = null,
        endDate: Date? = null,
        equipmentId: Long? = null,
        uuid: String? = null
    ): OfflineEquipmentEntity = withContext(ioDispatcher) {
        val timestamp = now()
        val existing = equipmentId?.let { localDataService.getEquipment(it) }
            ?: uuid?.let { localDataService.getEquipmentByUuid(it) }
        val lockUpdatedAt = existing?.serverId?.let { existing.updatedAt.toApiTimestamp() }

        val resolvedId = existing?.equipmentId ?: equipmentId ?: -System.currentTimeMillis()
        val resolvedUuid = existing?.uuid ?: uuid ?: UuidUtils.generateUuidV7()

        val entity = OfflineEquipmentEntity(
            equipmentId = resolvedId,
            serverId = existing?.serverId,
            uuid = resolvedUuid,
            projectId = existing?.projectId ?: projectId,
            roomId = roomId ?: existing?.roomId,
            type = type,
            brand = brand,
            model = model,
            serialNumber = serialNumber,
            quantity = quantity,
            status = status,
            startDate = startDate ?: existing?.startDate,
            endDate = endDate ?: existing?.endDate,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
            lastSyncedAt = existing?.lastSyncedAt,
            syncStatus = SyncStatus.PENDING,
            syncVersion = existing?.syncVersion ?: 0,
            isDirty = true,
            isDeleted = false
        )

        localDataService.saveEquipment(listOf(entity))
        val saved = localDataService.getEquipmentByUuid(resolvedUuid) ?: entity
        syncQueueEnqueuer().enqueueEquipmentUpsert(saved, lockUpdatedAt)
        saved
    }

    suspend fun deleteEquipmentOffline(
        equipmentId: Long? = null,
        uuid: String? = null
    ): OfflineEquipmentEntity? = withContext(ioDispatcher) {
        val existing = when {
            equipmentId != null -> localDataService.getEquipment(equipmentId)
            uuid != null -> localDataService.getEquipmentByUuid(uuid)
            else -> null
        } ?: return@withContext null

        val lockUpdatedAt = existing.serverId?.let { existing.updatedAt.toApiTimestamp() }
        val timestamp = now()
        val updated = existing.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = timestamp
        )
        localDataService.saveEquipment(listOf(updated))
        if (existing.serverId == null) {
            localDataService.removeSyncOperationsForEntity(entityType = "equipment", entityId = existing.equipmentId)
            logLocalDeletion("equipment", existing.equipmentId, existing.uuid)
            val cleaned = updated.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
            localDataService.saveEquipment(listOf(cleaned))
            return@withContext cleaned
        }
        syncQueueEnqueuer().enqueueEquipmentDeletion(updated, lockUpdatedAt)
        updated
    }
}
