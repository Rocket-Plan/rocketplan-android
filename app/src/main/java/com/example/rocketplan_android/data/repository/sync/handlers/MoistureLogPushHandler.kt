package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DamageMaterialRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
import com.example.rocketplan_android.util.parseTargetMoisture
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

private const val DEFAULT_DAMAGE_TYPE_ID: Long = 1L

/**
 * Handles pushing moisture log upsert/delete operations to the server.
 */
class MoistureLogPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getMoistureLogByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (log.isDeleted) return OperationOutcome.DROP
        val lockUpdatedAt = (log.serverUpdatedAt ?: log.updatedAt).toApiTimestamp()
        return try {
            val synced = pushPendingMoistureLogUpsert(log, lockUpdatedAt)
            synced?.let { ctx.localDataService.saveMoistureLogs(listOf(it)) }
            if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.isConflict() && log.serverId != null) {
                return handle409Conflict(e as HttpException, log, operation)
            }
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping moisture log ${log.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Moisture log dropped - 422 validation error",
                    mapOf("logUuid" to log.uuid, "serverId" to (log.serverId?.toString() ?: "null"))
                )
                OperationOutcome.DROP
            } else throw e
        }
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getMoistureLogByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val lockUpdatedAt = (log.serverUpdatedAt ?: log.updatedAt).toApiTimestamp()
        return try {
            val synced = pushPendingMoistureLogDeletion(log, lockUpdatedAt)
            synced?.let { ctx.localDataService.saveMoistureLogs(listOf(it)) }
            if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
        } catch (e: Exception) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping moisture log delete ${log.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Moisture log delete dropped - 422 validation error",
                    mapOf("logUuid" to log.uuid, "serverId" to (log.serverId?.toString() ?: "null"))
                )
                OperationOutcome.DROP
            } else throw e
        }
    }

    private suspend fun handle409Conflict(
        error: HttpException,
        log: OfflineMoistureLogEntity,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        Log.w(SYNC_TAG, "⚠️ [syncPendingMoistureLogs] 409 conflict for moisture log ${log.serverId}; extracting fresh timestamp and retrying")
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Moisture log update 409 conflict",
            mapOf("logServerId" to (log.serverId?.toString() ?: "null"), "logUuid" to log.uuid)
        )

        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        if (freshUpdatedAt == null) {
            Log.w(SYNC_TAG, "⚠️ [syncPendingMoistureLogs] Could not extract updated_at from 409 body for log ${log.serverId}; will retry later")
            return OperationOutcome.SKIP
        }

        // Retry with fresh timestamp
        val request = log.toRequest(freshUpdatedAt)
        val retryResult = runCatching { ctx.api.updateMoistureLog(log.serverId!!, request) }
            .onFailure { if (it is CancellationException) throw it }

        retryResult.onFailure { retryError ->
            if (retryError.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ [syncPendingMoistureLogs] Retry still got 409; recording conflict for user resolution")
                val conflict = OfflineConflictResolutionEntity(
                    conflictId = UuidUtils.generateUuidV7(),
                    entityType = "moisture_log",
                    entityId = log.logId,
                    entityUuid = log.uuid,
                    localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                        "moistureContent" to log.moistureContent,
                        "depth" to log.depth,
                        "materialId" to log.materialId
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
                Log.w(SYNC_TAG, "Dropping moisture log ${log.uuid}: server validation error (422)")
                return OperationOutcome.DROP
            }
            throw retryError
        }

        // Retry succeeded - save
        val dto = retryResult.getOrThrow()
        val synced = dto.toEntity()?.copy(
            logId = log.logId,
            uuid = log.uuid,
            projectId = log.projectId,
            roomId = log.roomId,
            materialId = log.materialId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = ctx.now()
        ) ?: return OperationOutcome.SKIP
        ctx.localDataService.saveMoistureLogs(listOf(synced))
        Log.d(SYNC_TAG, "✅ [syncPendingMoistureLogs] Retry update succeeded for log ${log.serverId}")
        return OperationOutcome.SUCCESS
    }

    private suspend fun pushPendingMoistureLogUpsert(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
    ): OfflineMoistureLogEntity? {
        val room = ctx.localDataService.getRoom(log.roomId)
        val roomServerId = room?.serverId
        var material = ctx.localDataService.getMaterial(log.materialId)
        val projectServerId = resolveServerProjectId(log.projectId)

        if (projectServerId == null || roomServerId == null) {
            Log.w(
                SYNC_TAG,
                "⚠️ [syncPendingMoistureLogs] Missing server ids " +
                    "(project=$projectServerId, room=$roomServerId) for log uuid=${log.uuid}"
            )
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Moisture log waiting for dependencies to sync",
                mapOf(
                    "logUuid" to log.uuid,
                    "hasProjectServerId" to (projectServerId != null).toString(),
                    "hasRoomServerId" to (roomServerId != null).toString()
                )
            )
            return null
        }

        if (material?.serverId == null) {
            material = material?.let { ensureMaterialSynced(it, projectServerId, roomServerId, log) }
        }
        val materialServerId = material?.serverId
        if (materialServerId == null) {
            Log.w(
                SYNC_TAG,
                "⚠️ [syncPendingMoistureLogs] Unable to resolve material server id for log uuid=${log.uuid}"
            )
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Moisture log waiting for material to sync",
                mapOf("logUuid" to log.uuid, "materialId" to log.materialId.toString())
            )
            return null
        }

        val request = log.toRequest(lockUpdatedAt)
        val synced = runCatching {
            val dto = if (log.serverId == null) {
                ctx.api.createMoistureLog(roomServerId, materialServerId, request.copy(updatedAt = null))
            } else {
                ctx.api.updateMoistureLog(log.serverId, request)
            }
            dto.toEntity()?.copy(
                logId = log.logId,
                uuid = log.uuid,
                projectId = log.projectId,
                roomId = log.roomId,
                materialId = log.materialId,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = false,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                log.serverId != null && error.isMissingOnServer() -> {
                    val created = ctx.api.createMoistureLog(
                        roomServerId,
                        materialServerId,
                        request.copy(updatedAt = null)
                    )
                    created.toEntity()?.copy(
                        logId = log.logId,
                        uuid = log.uuid,
                        projectId = log.projectId,
                        roomId = log.roomId,
                        materialId = log.materialId,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = ctx.now()
                    )
                }
                else -> throw error
            }
        }.onFailure { error ->
            Log.w(SYNC_TAG, "⚠️ [syncPendingMoistureLogs] Failed to push moisture log ${log.uuid}", error)
        }.getOrElse { throw it }

        return synced
    }

    private suspend fun pushPendingMoistureLogDeletion(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
    ): OfflineMoistureLogEntity? {
        if (log.serverId == null) {
            return log.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = true,
                lastSyncedAt = ctx.now()
            )
        }

        val deleteRequest = DeleteWithTimestampRequest(
            updatedAt = lockUpdatedAt ?: (log.serverUpdatedAt ?: log.updatedAt).toApiTimestamp()
        )
        return runCatching {
            ctx.api.deleteMoistureLog(log.serverId, deleteRequest)
            // Clear tombstone now that server confirmed deletion
            DeletionTombstoneCache.clearTombstone("moisture_log", log.serverId)
            log.copy(
                isDeleted = true,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() -> {
                    // Clear tombstone - item is already gone from server
                    DeletionTombstoneCache.clearTombstone("moisture_log", log.serverId)
                    log.copy(
                        isDeleted = true,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        lastSyncedAt = ctx.now()
                    )
                }
                else -> throw error
            }
        }.onFailure {
            Log.w(SYNC_TAG, "⚠️ [syncPendingMoistureLog] Failed to delete log ${log.uuid}", it)
        }.getOrElse { throw it }
    }

    private suspend fun ensureMaterialSynced(
        material: OfflineMaterialEntity,
        projectServerId: Long,
        roomServerId: Long,
        log: OfflineMoistureLogEntity
    ): OfflineMaterialEntity? {
        val request = DamageMaterialRequest(
            name = material.name.ifBlank { "Material ${material.materialId}" },
            damageTypeId = DEFAULT_DAMAGE_TYPE_ID,
            description = material.description,
            dryingGoal = material.description?.let { parseTargetMoisture(it) },
            idempotencyKey = material.uuid
        )

        val created = runCatching {
            ctx.api.createProjectDamageMaterial(projectServerId, request.copy(updatedAt = null))
        }.recoverCatching { error ->
            if (error.isConflict()) {
                val existing = runCatching { ctx.api.getProjectDamageMaterials(projectServerId).data }
                    .getOrElse { emptyList() }
                    .firstOrNull { dto ->
                        dto.title.equals(material.name, ignoreCase = true) &&
                            (dto.damageTypeId ?: DEFAULT_DAMAGE_TYPE_ID) == request.damageTypeId
                    }
                existing ?: throw error
            } else {
                throw error
            }
        }.onFailure { error ->
            Log.w(
                SYNC_TAG,
                "⚠️ [ensureMaterialSynced] Failed to create material ${material.uuid}",
                error
            )
        }.getOrNull() ?: return null

        val timestamp = ctx.now()
        val updated = material.copy(
            serverId = created.id,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = (material.syncVersion + 1),
            lastSyncedAt = timestamp,
            updatedAt = timestamp
        )
        ctx.localDataService.saveMaterials(listOf(updated))
        return updated
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }
}
