package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

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
            if (e is CancellationException) throw e
            if (e.isConflict() && equipment.serverId != null) {
                return handle409Conflict(e as HttpException, equipment, projectServerId, roomServerId, operation)
            }
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

    private suspend fun handle409Conflict(
        error: HttpException,
        equipment: OfflineEquipmentEntity,
        projectServerId: Long,
        roomServerId: Long?,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] 409 conflict for equipment ${equipment.serverId}; extracting fresh timestamp and retrying")
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Equipment update 409 conflict",
            mapOf("equipmentServerId" to (equipment.serverId?.toString() ?: "null"), "equipmentUuid" to equipment.uuid)
        )

        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        if (freshUpdatedAt == null) {
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Could not extract updated_at from 409 body for equipment ${equipment.serverId}; will retry later")
            return OperationOutcome.SKIP
        }

        // Retry with fresh timestamp
        val request = equipment.toRequest(projectServerId, roomServerId, freshUpdatedAt)
        val retryResult = runCatching { ctx.api.updateEquipment(equipment.serverId!!, request) }
            .onFailure { if (it is CancellationException) throw it }

        retryResult.onFailure { retryError ->
            if (retryError.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Retry still got 409; recording conflict for user resolution")
                val conflict = OfflineConflictResolutionEntity(
                    conflictId = UuidUtils.generateUuidV7(),
                    entityType = "equipment",
                    entityId = equipment.equipmentId,
                    entityUuid = equipment.uuid,
                    localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                        "type" to equipment.type,
                        "brand" to equipment.brand,
                        "model" to equipment.model,
                        "quantity" to equipment.quantity,
                        "status" to equipment.status
                    )).toByteArray(Charsets.UTF_8),
                    remoteVersion = ctx.gson.toJson(mapOf<String, Any?>(
                        "updatedAt" to freshUpdatedAt
                    )).toByteArray(Charsets.UTF_8),
                    conflictType = "UPDATE_CONFLICT",
                    detectedAt = ctx.now(),
                    originalOperationId = operation.operationId
                )
                ctx.recordConflict(conflict)
                return OperationOutcome.CONFLICT_PENDING
            }
            if (retryError.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid}: server validation error (422)")
                return OperationOutcome.DROP
            }
            throw retryError
        }

        // Retry succeeded - save
        val dto = retryResult.getOrThrow()
        val synced = dto.toEntity().copy(
            equipmentId = equipment.equipmentId,
            uuid = equipment.uuid,
            projectId = equipment.projectId,
            roomId = equipment.roomId ?: dto.roomId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveEquipment(listOf(synced))
        Log.d(SYNC_TAG, "✅ [syncPendingEquipment] Retry update succeeded for equipment ${equipment.serverId}")
        return OperationOutcome.SUCCESS
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
