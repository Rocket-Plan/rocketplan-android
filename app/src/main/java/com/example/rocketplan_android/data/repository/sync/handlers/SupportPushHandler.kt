package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.CreateSupportConversationRequest
import com.example.rocketplan_android.data.model.offline.CreateSupportMessageRequest
import com.example.rocketplan_android.data.repository.mapper.PendingSupportConversationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingSupportMessagePayload
import com.example.rocketplan_android.logging.LogLevel
import java.util.Date

/**
 * Handles pushing support conversation and message operations to the server.
 */
class SupportPushHandler(private val ctx: PushHandlerContext) {

    /**
     * Handles creating a new support conversation on the server.
     */
    suspend fun handleConversationCreate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = extractConversationPayload(operation.payload)
            ?: run {
                Log.e(SYNC_TAG, "Failed to parse conversation payload for ${operation.entityUuid}")
                return OperationOutcome.DROP
            }

        val conversation = ctx.localDataService.getSupportConversationByUuid(operation.entityUuid)
            ?: run {
                Log.d(SYNC_TAG, "Conversation ${operation.entityUuid} not found locally, dropping")
                return OperationOutcome.DROP
            }

        if (conversation.serverId != null) {
            Log.d(SYNC_TAG, "Conversation ${operation.entityUuid} already synced, dropping")
            return OperationOutcome.SUCCESS
        }

        val request = CreateSupportConversationRequest(
            categoryId = payload.categoryId,
            subject = payload.subject,
            body = payload.initialMessageBody,
            idempotencyKey = payload.idempotencyKey
        )

        return try {
            val dto = ctx.api.createSupportConversation(request)

            // Update local conversation with serverId
            ctx.localDataService.updateSupportConversationServerId(
                conversationId = conversation.conversationId,
                serverId = dto.id
            )

            Log.d(SYNC_TAG, "✅ Synced conversation ${operation.entityUuid} -> serverId=${dto.id}")
            ctx.remoteLogger?.log(
                LogLevel.INFO,
                SYNC_TAG,
                "Support conversation synced",
                mapOf(
                    "conversationUuid" to operation.entityUuid,
                    "serverId" to dto.id.toString()
                )
            )

            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "Failed to sync conversation ${operation.entityUuid}", e)
            ctx.remoteLogger?.log(
                LogLevel.ERROR,
                SYNC_TAG,
                "Support conversation sync failed",
                mapOf(
                    "conversationUuid" to operation.entityUuid,
                    "error" to (e.message ?: "unknown")
                )
            )
            throw e
        }
    }

    /**
     * Handles creating a new support message on the server.
     */
    suspend fun handleMessageCreate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = extractMessagePayload(operation.payload)
            ?: run {
                Log.e(SYNC_TAG, "Failed to parse message payload for ${operation.entityUuid}")
                return OperationOutcome.DROP
            }

        val message = ctx.localDataService.getSupportMessageByUuid(operation.entityUuid)
            ?: run {
                Log.d(SYNC_TAG, "Message ${operation.entityUuid} not found locally, dropping")
                return OperationOutcome.DROP
            }

        if (message.serverId != null) {
            Log.d(SYNC_TAG, "Message ${operation.entityUuid} already synced, dropping")
            return OperationOutcome.SUCCESS
        }

        // Resolve conversation serverId
        val conversationServerId = payload.conversationServerId
            ?: ctx.localDataService.getSupportConversation(payload.conversationId)?.serverId
            ?: run {
                Log.d(
                    SYNC_TAG,
                    "⏳ Message ${operation.entityUuid} waiting for conversation ${payload.conversationId} to sync"
                )
                ctx.remoteLogger?.log(
                    LogLevel.DEBUG,
                    SYNC_TAG,
                    "Sync skip - dependency not ready",
                    mapOf(
                        "entityType" to "support_message",
                        "entityUuid" to operation.entityUuid,
                        "waitingFor" to "conversation",
                        "dependencyId" to payload.conversationId.toString()
                    )
                )
                return OperationOutcome.SKIP
            }

        val request = CreateSupportMessageRequest(
            body = payload.body,
            idempotencyKey = payload.idempotencyKey
        )

        return try {
            val dto = ctx.api.createSupportMessage(conversationServerId, request)

            // Update local message with serverId
            ctx.localDataService.updateSupportMessageServerId(
                messageId = message.messageId,
                serverId = dto.id
            )

            Log.d(SYNC_TAG, "✅ Synced message ${operation.entityUuid} -> serverId=${dto.id}")
            ctx.remoteLogger?.log(
                LogLevel.INFO,
                SYNC_TAG,
                "Support message synced",
                mapOf(
                    "messageUuid" to operation.entityUuid,
                    "serverId" to dto.id.toString(),
                    "conversationServerId" to conversationServerId.toString()
                )
            )

            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            Log.e(SYNC_TAG, "Failed to sync message ${operation.entityUuid}", e)
            ctx.remoteLogger?.log(
                LogLevel.ERROR,
                SYNC_TAG,
                "Support message sync failed",
                mapOf(
                    "messageUuid" to operation.entityUuid,
                    "error" to (e.message ?: "unknown")
                )
            )
            throw e
        }
    }

    private fun extractConversationPayload(payload: ByteArray): PendingSupportConversationPayload? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingSupportConversationPayload::class.java
            )
        }.getOrNull()

    private fun extractMessagePayload(payload: ByteArray): PendingSupportMessagePayload? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingSupportMessagePayload::class.java
            )
        }.getOrNull()

    companion object {
        private const val SYNC_TAG = "SupportPushHandler"
    }
}
