package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toAttachRequest
import com.example.rocketplan_android.data.repository.mapper.toCatalogRequest
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toPivotUpdateRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

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
            } else {
                if (e is CancellationException) throw e
                Log.w(SYNC_TAG, "EquipmentPushHandler unknown error; retrying", e)
                OperationOutcome.RETRY
            }
        }
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val serverId = equipment.serverId
        if (serverId == null) {
            ctx.localDataService.saveEquipment(listOf(deletedCopy(equipment)))
            return OperationOutcome.SUCCESS
        }
        val lockUpdatedAt = (equipment.serverUpdatedAt ?: equipment.updatedAt).toApiTimestamp()
        val outcome = try {
            resolveDeleteWithStaleRetry(lockUpdatedAt) { ts ->
                ctx.api.deleteEquipmentRoom(serverId, DeleteWithTimestampRequest(updatedAt = ts))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(SYNC_TAG, "EquipmentPushHandler delete error; retrying", e)
            return OperationOutcome.RETRY
        }
        outcome?.let {
            if (it == OperationOutcome.DROP) {
                Log.w(SYNC_TAG, "Dropping equipment delete ${equipment.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment delete dropped - 422 validation error",
                    mapOf("equipmentUuid" to equipment.uuid, "serverId" to serverId.toString())
                )
            }
            return it
        }
        DeletionTombstoneCache.clearTombstone("equipment", serverId)
        ctx.localDataService.saveEquipment(listOf(deletedCopy(equipment)))
        return OperationOutcome.SUCCESS
    }

    private fun deletedCopy(equipment: OfflineEquipmentEntity) = equipment.copy(
        isDeleted = true,
        isDirty = false,
        syncStatus = SyncStatus.SYNCED,
        lastSyncedAt = ctx.now()
    )

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

        if (equipment.serverId == null) {
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] 409 but no pivot serverId for equipment ${equipment.uuid}; will retry later")
            return OperationOutcome.SKIP
        }

        val request = equipment.toPivotUpdateRequest(freshUpdatedAt)
        val retryResult = runCatching { ctx.api.updateEquipmentRoom(equipment.serverId, request) }
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
            if (retryError.isMissingOnServer()) {
                Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Pivot gone on server for equipment ${equipment.uuid}; resolving as delete")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment pivot missing on server",
                    mapOf("equipmentUuid" to equipment.uuid, "serverId" to equipment.serverId.toString())
                )
                return OperationOutcome.DROP
            }
            Log.w(SYNC_TAG, "Retrying equipment ${equipment.uuid} after conflict")
            return OperationOutcome.RETRY
        }

        val dto = retryResult.getOrThrow()
        val synced = dto.toEntity().copy(
            equipmentId = equipment.equipmentId,
            uuid = equipment.uuid,
            projectId = equipment.projectId,
            roomId = equipment.roomId ?: dto.roomId,
            catalogServerId = dto.equipmentId ?: equipment.catalogServerId,
            catalogUuid = equipment.catalogUuid,
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

        return runCatching {
            when {
                equipment.serverId != null -> {
                    val request = equipment.toPivotUpdateRequest(lockUpdatedAt)
                    val dto = ctx.api.updateEquipmentRoom(equipment.serverId, request)
                    dto.toEntity().copy(
                        equipmentId = equipment.equipmentId,
                        uuid = equipment.uuid,
                        projectId = equipment.projectId,
                        roomId = equipment.roomId ?: dto.roomId,
                        catalogServerId = dto.equipmentId ?: equipment.catalogServerId,
                        catalogUuid = equipment.catalogUuid,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = ctx.now()
                    )
                }
                equipment.catalogServerId != null && roomServerId != null -> {
                    val request = equipment.toAttachRequest(roomServerId)
                    val dto = ctx.api.attachRoomEquipment(roomServerId, request)
                    dto.toEntity().copy(
                        equipmentId = equipment.equipmentId,
                        uuid = equipment.uuid,
                        projectId = equipment.projectId,
                        roomId = equipment.roomId ?: dto.roomId,
                        catalogServerId = dto.equipmentId ?: equipment.catalogServerId,
                        catalogUuid = equipment.catalogUuid,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = ctx.now()
                    )
                }
                else -> {
                    val catalogId = resolveOrMintCatalog(equipment, projectServerId)
                    if (catalogId == null) {
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "equipment_catalog_unresolved",
                            mapOf(
                                "equipmentUuid" to equipment.uuid,
                                "projectId" to equipment.projectId.toString(),
                                "typeName" to equipment.type
                            )
                        )
                        throw IllegalStateException("Could not resolve catalog for equipment type: ${equipment.type}")
                    }
                    if (roomServerId == null) {
                        throw IllegalStateException("Cannot attach equipment without room serverId")
                    }
                    val cataloged = equipment.copy(catalogServerId = catalogId)
                    val request = cataloged.toAttachRequest(roomServerId)
                    val dto = ctx.api.attachRoomEquipment(roomServerId, request)
                    dto.toEntity().copy(
                        equipmentId = equipment.equipmentId,
                        uuid = equipment.uuid,
                        projectId = equipment.projectId,
                        roomId = equipment.roomId ?: dto.roomId,
                        catalogServerId = dto.equipmentId ?: catalogId,
                        catalogUuid = equipment.catalogUuid,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = ctx.now()
                    )
                }
            }
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() && equipment.serverId != null -> {
                    Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Pivot gone on server for equipment ${equipment.uuid}; logging warning and dropping")
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "equipment_pivot_id_missing",
                        mapOf("equipmentUuid" to equipment.uuid, "serverId" to equipment.serverId.toString())
                    )
                    throw error
                }
                else -> throw error
            }
        }.onFailure { error ->
            if (error.isConflict()) return@onFailure
            val errorBody = if (error.isConflict()) null
            else (error as? retrofit2.HttpException)?.response()?.errorBody()?.string()
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Failed to push equipment ${equipment.uuid}: $errorBody", error)
        }.getOrElse { throw it }
    }

    private suspend fun resolveOrMintCatalog(equipment: OfflineEquipmentEntity, projectServerId: Long): Long? {
        val existing = ctx.localDataService.getProjectEquipmentByType(equipment.projectId, equipment.type)
        if (existing != null && existing.catalogServerId != null) {
            return existing.catalogServerId
        }
        return try {
            val catalogRequest = equipment.toCatalogRequest()
            val created = ctx.api.createProjectEquipment(projectServerId, catalogRequest)
            created.id
        } catch (e: Throwable) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Could not mint catalog for type ${equipment.type}: 422, looking up existing")
                lookupProjectEquipmentByTypeName(equipment.type, projectServerId)
            } else {
                throw e
            }
        }
    }

    private suspend fun lookupProjectEquipmentByTypeName(typeName: String, projectServerId: Long): Long? {
        return try {
            val resp = ctx.api.getProjectEquipment(projectServerId)
            resp.data.find { it.type == typeName }?.equipmentId
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w(SYNC_TAG, "Failed to look up project equipment for type $typeName", e)
            null
        }
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }
}
