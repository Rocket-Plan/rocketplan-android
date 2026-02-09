package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.repository.mapper.PendingProjectUserPayload
import com.example.rocketplan_android.logging.LogLevel

/**
 * Handles pushing project-user (crew) add/remove operations to the server.
 *
 * Add crew: POST /api/projects/{projectId}/users/{userId} → 204 No Content
 * Remove crew: DELETE /api/projects/{projectId}/users/{userId} → 204 No Content
 */
class CrewPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleAdd(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = deserializePayload(operation) ?: return OperationOutcome.DROP

        return try {
            val response = ctx.api.addUserToProject(payload.projectServerId, payload.userServerId)
            if (response.isSuccessful || response.code() == 204) {
                ctx.localDataService.clearProjectUserPendingAdd(payload.projectServerId, payload.userServerId)
                Log.d(SYNC_TAG, "✅ [crew] Added user ${payload.userServerId} to project ${payload.projectServerId}")
                OperationOutcome.SUCCESS
            } else {
                Log.w(SYNC_TAG, "⚠️ [crew] Failed to add user: HTTP ${response.code()}")
                throw retrofit2.HttpException(response)
            }
        } catch (e: Exception) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "⚠️ [crew] Dropping add user ${payload.userServerId}: 422 validation error")
                ctx.localDataService.deleteProjectUser(payload.projectServerId, payload.userServerId)
                OperationOutcome.DROP
            } else if (e.isMissingOnServer()) {
                Log.w(SYNC_TAG, "⚠️ [crew] Project ${payload.projectServerId} not found on server, dropping")
                ctx.localDataService.deleteProjectUser(payload.projectServerId, payload.userServerId)
                OperationOutcome.DROP
            } else {
                throw e
            }
        }
    }

    suspend fun handleRemove(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = deserializePayload(operation) ?: return OperationOutcome.DROP

        return try {
            val response = ctx.api.removeUserFromProject(payload.projectServerId, payload.userServerId)
            if (response.isSuccessful || response.code() == 204) {
                ctx.localDataService.deleteProjectUser(payload.projectServerId, payload.userServerId)
                Log.d(SYNC_TAG, "✅ [crew] Removed user ${payload.userServerId} from project ${payload.projectServerId}")
                OperationOutcome.SUCCESS
            } else {
                Log.w(SYNC_TAG, "⚠️ [crew] Failed to remove user: HTTP ${response.code()}")
                throw retrofit2.HttpException(response)
            }
        } catch (e: Exception) {
            if (e.isMissingOnServer()) {
                // Already gone from server, just clean up locally
                ctx.localDataService.deleteProjectUser(payload.projectServerId, payload.userServerId)
                OperationOutcome.SUCCESS
            } else if (e.isValidationError()) {
                ctx.localDataService.deleteProjectUser(payload.projectServerId, payload.userServerId)
                OperationOutcome.DROP
            } else {
                throw e
            }
        }
    }

    private fun deserializePayload(operation: OfflineSyncQueueEntity): PendingProjectUserPayload? {
        return runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingProjectUserPayload::class.java
            )
        }.onFailure {
            Log.e(SYNC_TAG, "⚠️ [crew] Failed to deserialize payload for op=${operation.operationId}", it)
            ctx.remoteLogger?.log(
                LogLevel.ERROR, SYNC_TAG, "Crew payload deserialization failed",
                mapOf("operationId" to operation.operationId)
            )
        }.getOrNull()
    }
}
