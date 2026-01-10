package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.AtmosphericLogRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import retrofit2.HttpException

/**
 * Handles pushing atmospheric log upsert/delete operations to the server.
 */
class AtmosphericLogPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getAtmosphericLogByUuid(operation.entityUuid)
            ?: ctx.localDataService.getAtmosphericLog(operation.entityId)
            ?: return OperationOutcome.DROP

        if (log.isDeleted) return OperationOutcome.DROP

        val project = ctx.localDataService.getProject(log.projectId)
        val projectServerId = project?.serverId
            ?: return OperationOutcome.SKIP

        // If there's a room, we need its server ID
        val roomServerId: Long? = if (log.roomId != null) {
            val room = ctx.localDataService.getRoom(log.roomId)
            room?.serverId ?: return OperationOutcome.SKIP
        } else {
            null
        }

        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: log.updatedAt.toApiTimestamp()

        val request = AtmosphericLogRequest(
            uuid = log.uuid,
            date = log.date.toApiTimestamp(),
            temperature = log.temperature,
            relativeHumidity = log.relativeHumidity,
            dewPoint = log.dewPoint,
            gpp = log.gpp,
            pressure = log.pressure,
            windSpeed = log.windSpeed,
            isExternal = log.isExternal,
            isInlet = log.isInlet,
            inletId = log.inletId,
            roomUuid = log.roomId?.let { ctx.localDataService.getRoom(it)?.uuid },
            projectUuid = project.uuid,
            idempotencyKey = log.uuid,
            updatedAt = lockUpdatedAt
        )

        val dto = if (log.serverId == null) {
            // CREATE - route to room or project
            if (roomServerId != null) {
                ctx.api.createRoomAtmosphericLog(roomServerId, request)
            } else {
                ctx.api.createProjectAtmosphericLog(projectServerId, request)
            }
        } else {
            // UPDATE
            ctx.api.updateAtmosphericLog(log.serverId, request)
        }

        val synced = log.copy(
            serverId = dto.id,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveAtmosphericLogs(listOf(synced))
        return OperationOutcome.SUCCESS
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val log = ctx.localDataService.getAtmosphericLogByUuid(operation.entityUuid)
            ?: ctx.localDataService.getAtmosphericLog(operation.entityId)
            ?: return OperationOutcome.DROP

        val serverId = log.serverId
            ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: log.updatedAt.toApiTimestamp()

        try {
            val response = ctx.api.deleteAtmosphericLog(
                serverId,
                DeleteWithTimestampRequest(updatedAt = lockUpdatedAt)
            )
            if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
                throw HttpException(response)
            }
        } catch (error: Throwable) {
            if (!error.isMissingOnServer()) {
                throw error
            }
        }

        val cleaned = log.copy(
            isDirty = false,
            isDeleted = true,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveAtmosphericLogs(listOf(cleaned))
        return OperationOutcome.SUCCESS
    }

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
