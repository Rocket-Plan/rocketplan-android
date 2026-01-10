package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
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

        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: equipment.updatedAt.toApiTimestamp()
        val synced = pushPendingEquipmentUpsert(equipment, projectServerId, roomServerId, lockUpdatedAt)
        synced?.let { ctx.localDataService.saveEquipment(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: equipment.updatedAt.toApiTimestamp()
        val synced = pushPendingEquipmentDeletion(equipment, lockUpdatedAt)
        synced?.let { ctx.localDataService.saveEquipment(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
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
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Failed to push equipment ${equipment.uuid}", error)
        }.getOrNull()

        return synced
    }

    private suspend fun pushPendingEquipmentDeletion(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String? = null
    ): OfflineEquipmentEntity? {
        if (equipment.serverId == null) {
            // Never reached server; treat as resolved locally
            return equipment.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }

        val deleteRequest = DeleteWithTimestampRequest(
            updatedAt = lockUpdatedAt ?: equipment.updatedAt.toApiTimestamp()
        )
        return runCatching {
            ctx.api.deleteEquipment(equipment.serverId, deleteRequest)
            equipment.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() -> equipment.copy(
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = ctx.now()
                )
                else -> throw error
            }
        }.onFailure {
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Failed to delete equipment ${equipment.uuid}", it)
        }.getOrNull()
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
