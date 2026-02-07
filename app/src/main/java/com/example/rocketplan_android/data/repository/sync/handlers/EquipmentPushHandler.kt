package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toRequest
import com.example.rocketplan_android.logging.LogLevel

/**
 * Handles pushing equipment upsert/delete operations to the server.
 */
class EquipmentPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (equipment.isDeleted) return OperationOutcome.DROP

        val projectServerId = resolveServerProjectId(equipment.projectId)
        if (projectServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Equipment waiting for project to sync",
                mapOf(
                    "equipmentUuid" to equipment.uuid,
                    "projectId" to equipment.projectId.toString()
                )
            )
            return OperationOutcome.SKIP
        }

        val roomServerId = equipment.roomId?.let { roomId ->
            ctx.localDataService.getRoom(roomId)?.serverId
        }
        if (equipment.roomId != null && roomServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Equipment waiting for room to sync",
                mapOf(
                    "equipmentUuid" to equipment.uuid,
                    "roomId" to equipment.roomId.toString()
                )
            )
            return OperationOutcome.SKIP
        }

        val lockUpdatedAt = (equipment.serverUpdatedAt ?: equipment.updatedAt).toApiTimestamp()
        return try {
            val synced = pushPendingEquipmentUpsert(equipment, projectServerId, roomServerId, lockUpdatedAt)
            synced?.let { ctx.localDataService.saveEquipment(listOf(it)) }
            if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
        } catch (e: Exception) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment dropped - 422 validation error",
                    mapOf("equipmentUuid" to equipment.uuid, "serverId" to (equipment.serverId?.toString() ?: "null"))
                )
                OperationOutcome.DROP
            } else throw e
        }
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val lockUpdatedAt = (equipment.serverUpdatedAt ?: equipment.updatedAt).toApiTimestamp()
        return try {
            val synced = pushPendingEquipmentDeletion(equipment, lockUpdatedAt)
            synced?.let { ctx.localDataService.saveEquipment(listOf(it)) }
            if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
        } catch (e: Exception) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment delete ${equipment.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment delete dropped - 422 validation error",
                    mapOf("equipmentUuid" to equipment.uuid, "serverId" to (equipment.serverId?.toString() ?: "null"))
                )
                OperationOutcome.DROP
            } else throw e
        }
    }

    private suspend fun pushPendingEquipmentUpsert(
        equipment: OfflineEquipmentEntity,
        projectServerId: Long,
        roomServerIdOverride: Long? = null,
        lockUpdatedAt: String? = null
    ): OfflineEquipmentEntity? {
        val roomServerId = roomServerIdOverride ?: equipment.roomId?.let { roomId ->
            ctx.localDataService.getRoom(roomId)?.serverId
        }
        val request = equipment.toRequest(projectServerId, roomServerId, lockUpdatedAt)
        val synced = runCatching {
            val dto = if (equipment.serverId == null) {
                ctx.api.createProjectEquipment(projectServerId, request.copy(updatedAt = null))
            } else {
                ctx.api.updateEquipment(equipment.serverId, request)
            }
            dto.toEntity().copy(
                equipmentId = equipment.equipmentId,
                uuid = equipment.uuid,
                projectId = equipment.projectId,
                roomId = equipment.roomId ?: dto.roomId,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = false,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                equipment.serverId != null && error.isMissingOnServer() -> {
                    val created = ctx.api.createProjectEquipment(
                        projectServerId,
                        request.copy(updatedAt = null)
                    )
                    created.toEntity().copy(
                        equipmentId = equipment.equipmentId,
                        uuid = equipment.uuid,
                        projectId = equipment.projectId,
                        roomId = equipment.roomId ?: created.roomId,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = ctx.now()
                    )
                }
                else -> throw error
            }
        }.onFailure { error ->
            val errorBody = (error as? retrofit2.HttpException)?.response()?.errorBody()?.string()
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Failed to push equipment ${equipment.uuid}: $errorBody", error)
        }.getOrElse { throw it }

        return synced
    }

    private suspend fun pushPendingEquipmentDeletion(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String? = null
    ): OfflineEquipmentEntity? {
        if (equipment.serverId == null) {
            // Never reached server; treat as resolved locally
            return equipment.copy(
                isDeleted = true,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }

        val deleteRequest = DeleteWithTimestampRequest(
            updatedAt = lockUpdatedAt ?: (equipment.serverUpdatedAt ?: equipment.updatedAt).toApiTimestamp()
        )
        return runCatching {
            ctx.api.deleteEquipment(equipment.serverId, deleteRequest)
            // Clear tombstone now that server confirmed deletion
            DeletionTombstoneCache.clearTombstone("equipment", equipment.serverId)
            equipment.copy(
                isDeleted = true,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() -> {
                    // Clear tombstone - item is already gone from server
                    DeletionTombstoneCache.clearTombstone("equipment", equipment.serverId)
                    equipment.copy(
                        isDeleted = true,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        lastSyncedAt = ctx.now()
                    )
                }
                else -> throw error
            }
        }.onFailure {
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Failed to delete equipment ${equipment.uuid}", it)
        }.getOrElse { throw it }
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }
}
