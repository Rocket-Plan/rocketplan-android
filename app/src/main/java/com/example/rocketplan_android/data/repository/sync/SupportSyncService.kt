package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSupportCategoryEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportConversationEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportMessageEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportMessageAttachmentEntity
import com.example.rocketplan_android.data.model.offline.SupportConversationDto
import com.example.rocketplan_android.data.model.offline.SupportMessageDto
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Handles Support feature operations including:
 * - Syncing categories from server
 * - Syncing conversations from server
 * - Syncing messages for a conversation
 * - Creating new conversations (offline-first)
 * - Sending messages (offline-first)
 * - Closing conversations
 * - Marking messages as read
 */
class SupportSyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseApiDate(dateStr: String?): Date? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            apiDateFormat.parse(dateStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse date: $dateStr", e)
            null
        }
    }

    // ============================================================================
    // Categories
    // ============================================================================

    /**
     * Fetches support categories from server and caches them locally.
     */
    suspend fun syncCategories(): Result<List<OfflineSupportCategoryEntity>> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "Syncing support categories...")
            val response = api.getSupportCategories()
            val entities = response.data.map { dto ->
                OfflineSupportCategoryEntity(
                    categoryId = dto.id,
                    name = dto.name ?: "",
                    description = dto.description,
                    fetchedAt = now()
                )
            }
            localDataService.replaceSupportCategories(entities)
            Log.d(TAG, "Synced ${entities.size} support categories")
            Result.success(entities)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync support categories", e)
            Result.failure(e)
        }
    }

    // ============================================================================
    // Conversations
    // ============================================================================

    /**
     * Fetches user's conversations from server and merges with local data.
     */
    suspend fun syncConversations(): Result<List<OfflineSupportConversationEntity>> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "Syncing support conversations...")
            val response = api.getSupportConversations()
            val entities = response.data.map { dto -> dto.toEntity() }
            localDataService.saveSupportConversations(entities)
            Log.d(TAG, "Synced ${entities.size} support conversations")
            Result.success(entities)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync support conversations", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches messages for a specific conversation from server.
     */
    suspend fun syncMessages(conversationServerId: Long): Result<List<OfflineSupportMessageEntity>> = withContext(ioDispatcher) {
        try {
            Log.d(TAG, "Syncing messages for conversation $conversationServerId...")
            val response = api.getSupportMessages(conversationServerId)
            val entities = response.data.map { dto -> dto.toEntity(conversationServerId) }
            localDataService.saveSupportMessages(entities)

            // Save attachments
            val attachments = response.data.flatMap { dto ->
                dto.attachments?.map { attachment ->
                    OfflineSupportMessageAttachmentEntity(
                        serverId = attachment.id,
                        messageId = dto.id,
                        fileName = attachment.fileName ?: "",
                        fileUrl = attachment.fileUrl,
                        localPath = null,
                        fileSize = attachment.fileSize ?: 0,
                        mimeType = attachment.mimeType
                    )
                } ?: emptyList()
            }
            if (attachments.isNotEmpty()) {
                localDataService.saveSupportMessageAttachments(attachments)
            }

            Log.d(TAG, "Synced ${entities.size} messages for conversation $conversationServerId")
            Result.success(entities)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync messages for conversation $conversationServerId", e)
            Result.failure(e)
        }
    }

    /**
     * Creates a new support conversation locally and queues it for sync.
     */
    suspend fun createConversation(
        userId: Long,
        categoryId: Long,
        subject: String,
        initialMessageBody: String
    ): OfflineSupportConversationEntity = withContext(ioDispatcher) {
        val timestamp = now()
        val uuid = UuidUtils.generateUuidV7()

        val conversation = OfflineSupportConversationEntity(
            conversationId = -System.currentTimeMillis(),
            uuid = uuid,
            userId = userId,
            categoryId = categoryId,
            subject = subject,
            status = "open",
            unreadCount = 0,
            lastMessageAt = timestamp,
            createdAt = timestamp,
            updatedAt = timestamp,
            syncStatus = SyncStatus.PENDING,
            isDirty = true
        )

        val conversationId = localDataService.saveSupportConversation(conversation)
        val saved = localDataService.getSupportConversation(conversationId)
            ?: conversation.copy(conversationId = conversationId)

        // Queue sync
        syncQueueEnqueuer().enqueueSupportConversationCreation(saved, initialMessageBody)

        Log.d(TAG, "Created conversation locally: id=$conversationId, uuid=$uuid")
        saved
    }

    /**
     * Sends a message in a conversation locally and queues it for sync.
     */
    suspend fun sendMessage(
        conversation: OfflineSupportConversationEntity,
        senderId: Long,
        body: String
    ): OfflineSupportMessageEntity = withContext(ioDispatcher) {
        val timestamp = now()
        val uuid = UuidUtils.generateUuidV7()

        val message = OfflineSupportMessageEntity(
            messageId = -System.currentTimeMillis(),
            uuid = uuid,
            conversationId = conversation.conversationId,
            conversationServerId = conversation.serverId,
            senderId = senderId,
            senderType = "user",
            body = body,
            isRead = true,
            createdAt = timestamp,
            updatedAt = timestamp,
            syncStatus = SyncStatus.PENDING,
            isDirty = true
        )

        val messageId = localDataService.saveSupportMessage(message)
        val saved = localDataService.getSupportMessage(messageId)
            ?: message.copy(messageId = messageId)

        // Update conversation's lastMessageAt
        val updatedConversation = conversation.copy(
            lastMessageAt = timestamp,
            updatedAt = timestamp
        )
        localDataService.saveSupportConversation(updatedConversation)

        // Queue sync
        syncQueueEnqueuer().enqueueSupportMessageCreation(saved)

        Log.d(TAG, "Created message locally: id=$messageId, uuid=$uuid, conversationId=${conversation.conversationId}")
        saved
    }

    /**
     * Closes a conversation locally and syncs with server.
     */
    suspend fun closeConversation(conversation: OfflineSupportConversationEntity): Result<Unit> = withContext(ioDispatcher) {
        try {
            val serverId = conversation.serverId
            if (serverId != null && serverId > 0) {
                api.closeSupportConversation(serverId)
            }

            localDataService.updateSupportConversationStatus(conversation.conversationId, "closed")
            Log.d(TAG, "Closed conversation ${conversation.conversationId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close conversation ${conversation.conversationId}", e)
            Result.failure(e)
        }
    }

    /**
     * Marks messages as read for a conversation.
     */
    suspend fun markAsRead(conversation: OfflineSupportConversationEntity): Result<Unit> = withContext(ioDispatcher) {
        try {
            val serverId = conversation.serverId
            if (serverId != null && serverId > 0) {
                api.markSupportMessagesAsRead(serverId)
            }

            localDataService.markSupportMessagesAsRead(conversation.conversationId)

            // Reset unread count
            val updated = conversation.copy(unreadCount = 0)
            localDataService.saveSupportConversation(updated)

            Log.d(TAG, "Marked messages as read for conversation ${conversation.conversationId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark messages as read for conversation ${conversation.conversationId}", e)
            Result.failure(e)
        }
    }

    // ============================================================================
    // Mappers
    // ============================================================================

    private fun SupportConversationDto.toEntity(): OfflineSupportConversationEntity {
        val now = now()
        return OfflineSupportConversationEntity(
            serverId = id,
            uuid = uuid ?: UuidUtils.generateUuidV7(),
            userId = userId ?: 0,
            categoryId = categoryId ?: 0,
            subject = subject ?: "",
            status = status ?: "open",
            unreadCount = unreadCount ?: 0,
            lastMessageAt = parseApiDate(lastMessageAt),
            createdAt = parseApiDate(createdAt) ?: now,
            updatedAt = parseApiDate(updatedAt) ?: now,
            lastSyncedAt = now,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false
        )
    }

    private fun SupportMessageDto.toEntity(conversationServerId: Long): OfflineSupportMessageEntity {
        val now = now()
        return OfflineSupportMessageEntity(
            serverId = id,
            uuid = uuid ?: UuidUtils.generateUuidV7(),
            conversationId = conversationId ?: 0,
            conversationServerId = conversationServerId,
            senderId = senderId ?: 0,
            senderType = senderType ?: "user",
            body = body ?: "",
            isRead = isRead ?: false,
            createdAt = parseApiDate(createdAt) ?: now,
            updatedAt = parseApiDate(updatedAt) ?: now,
            lastSyncedAt = now,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false
        )
    }

    companion object {
        private const val TAG = "SupportSyncService"
    }
}
