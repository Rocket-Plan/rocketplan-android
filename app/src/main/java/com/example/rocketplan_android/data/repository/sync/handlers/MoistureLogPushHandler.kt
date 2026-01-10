package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.DamageMaterialRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.parseTargetMoisture

private const val DEFAULT_DAMAGE_TYPE_ID: Long = 1L

/**
 * Handles pushing moisture log upsert/delete operations to the server.
 */
class MoistureLogPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getMoistureLogByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (log.isDeleted) return OperationOutcome.DROP
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: log.updatedAt.toApiTimestamp()
        val synced = pushPendingMoistureLogUpsert(log, lockUpdatedAt)
        synced?.let { ctx.localDataService.saveMoistureLogs(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getMoistureLogByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: log.updatedAt.toApiTimestamp()
        val synced = pushPendingMoistureLogDeletion(log, lockUpdatedAt)
        synced?.let { ctx.localDataService.saveMoistureLogs(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
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
                materialId = materialServerId,
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
                        materialId = materialServerId,
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
        }.getOrNull()

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
            updatedAt = lockUpdatedAt ?: log.updatedAt.toApiTimestamp()
        )
        return runCatching {
            ctx.api.deleteMoistureLog(log.serverId, deleteRequest)
            log.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() -> log.copy(
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = ctx.now()
                )
                else -> throw error
            }
        }.onFailure {
            Log.w(SYNC_TAG, "⚠️ [syncPendingMoistureLog] Failed to delete log ${log.uuid}", it)
        }.getOrNull()
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

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
