package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineSupportCategoryEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportConversationEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportMessageEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportMessageAttachmentEntity
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.SupportCategoryDto
import com.example.rocketplan_android.data.model.offline.SupportConversationDto
import com.example.rocketplan_android.data.model.offline.SupportMessageAttachmentDto
import com.example.rocketplan_android.data.model.offline.SupportMessageDto
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SupportSyncServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val testDispatcher = StandardTestDispatcher()
    private val api = mockk<OfflineSyncApi>()
    private val localDataService = mockk<LocalDataService>(relaxed = true)
    private val syncQueueEnqueuer = mockk<SyncQueueEnqueuer>(relaxed = true)

    private fun createService() = SupportSyncService(
        api = api,
        localDataService = localDataService,
        syncQueueEnqueuer = { syncQueueEnqueuer },
        ioDispatcher = testDispatcher
    )

    // ============================================================================
    // Categories Tests
    // ============================================================================

    @Test
    fun `syncCategories returns success with categories`() = runTest(testDispatcher) {
        val dto1 = createCategoryDto(id = 1L, name = "Billing", description = "Billing issues")
        val dto2 = createCategoryDto(id = 2L, name = "Technical", description = "Tech support")
        val response = PaginatedResponse(data = listOf(dto1, dto2))

        coEvery { api.getSupportCategories() } returns response
        coJustRun { localDataService.replaceSupportCategories(any()) }

        val service = createService()
        val result = service.syncCategories()

        assertThat(result.isSuccess).isTrue()
        val categories = result.getOrNull()!!
        assertThat(categories).hasSize(2)
        assertThat(categories.map { it.name }).containsExactly("Billing", "Technical")
    }

    @Test
    fun `syncCategories saves to local data service`() = runTest(testDispatcher) {
        val dto = createCategoryDto(id = 1L, name = "General")
        val response = PaginatedResponse(data = listOf(dto))

        coEvery { api.getSupportCategories() } returns response
        val savedSlot = slot<List<OfflineSupportCategoryEntity>>()
        coJustRun { localDataService.replaceSupportCategories(capture(savedSlot)) }

        val service = createService()
        service.syncCategories()

        coVerify { localDataService.replaceSupportCategories(any()) }
        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured.first().categoryId).isEqualTo(1L)
        assertThat(savedSlot.captured.first().name).isEqualTo("General")
    }

    @Test
    fun `syncCategories returns failure on network error`() = runTest(testDispatcher) {
        coEvery { api.getSupportCategories() } throws IOException("Network error")

        val service = createService()
        val result = service.syncCategories()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `syncCategories handles empty categories list`() = runTest(testDispatcher) {
        val response = PaginatedResponse(data = emptyList<SupportCategoryDto>())

        coEvery { api.getSupportCategories() } returns response
        coJustRun { localDataService.replaceSupportCategories(any()) }

        val service = createService()
        val result = service.syncCategories()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    // ============================================================================
    // Conversations Tests
    // ============================================================================

    @Test
    fun `syncConversations returns success with conversations`() = runTest(testDispatcher) {
        val dto = createConversationDto(id = 100L, subject = "Help needed", status = "open")
        val response = PaginatedResponse(data = listOf(dto))

        coEvery { api.getSupportConversations() } returns response
        coJustRun { localDataService.saveSupportConversations(any()) }

        val service = createService()
        val result = service.syncConversations()

        assertThat(result.isSuccess).isTrue()
        val conversations = result.getOrNull()!!
        assertThat(conversations).hasSize(1)
        assertThat(conversations.first().subject).isEqualTo("Help needed")
    }

    @Test
    fun `syncConversations saves to local data service`() = runTest(testDispatcher) {
        val dto = createConversationDto(id = 100L, subject = "Test", status = "open")
        val response = PaginatedResponse(data = listOf(dto))

        coEvery { api.getSupportConversations() } returns response
        val savedSlot = slot<List<OfflineSupportConversationEntity>>()
        coJustRun { localDataService.saveSupportConversations(capture(savedSlot)) }

        val service = createService()
        service.syncConversations()

        coVerify { localDataService.saveSupportConversations(any()) }
        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured.first().serverId).isEqualTo(100L)
    }

    @Test
    fun `syncConversations returns failure on API error`() = runTest(testDispatcher) {
        coEvery { api.getSupportConversations() } throws RuntimeException("API Error")

        val service = createService()
        val result = service.syncConversations()

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `syncConversations handles empty response`() = runTest(testDispatcher) {
        val response = PaginatedResponse(data = emptyList<SupportConversationDto>())

        coEvery { api.getSupportConversations() } returns response
        coJustRun { localDataService.saveSupportConversations(any()) }

        val service = createService()
        val result = service.syncConversations()

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEmpty()
    }

    // ============================================================================
    // Messages Tests
    // ============================================================================

    @Test
    fun `syncMessages returns success with messages`() = runTest(testDispatcher) {
        val dto = createMessageDto(id = 10L, body = "Hello support")
        val response = PaginatedResponse(data = listOf(dto))

        coEvery { api.getSupportMessages(100L) } returns response

        val service = createService()
        val result = service.syncMessages(100L)

        assertThat(result.isSuccess).isTrue()
        val messages = result.getOrNull()!!
        assertThat(messages).hasSize(1)
        assertThat(messages.first().body).isEqualTo("Hello support")
        coVerify { localDataService.saveSupportMessages(any()) }
    }

    @Test
    fun `syncMessages saves attachments when present`() = runTest(testDispatcher) {
        val attachment = SupportMessageAttachmentDto(
            id = 50L,
            messageId = 10L,
            fileName = "screenshot.png",
            fileUrl = "https://example.com/screenshot.png",
            fileSize = 2048,
            mimeType = "image/png"
        )
        val dto = createMessageDto(id = 10L, body = "See attached", attachments = listOf(attachment))
        val response = PaginatedResponse(data = listOf(dto))

        coEvery { api.getSupportMessages(100L) } returns response
        // RP-FR-005: attachment FK is resolved to the LOCAL message PK via the server message id.
        coEvery { localDataService.getSupportMessageByServerId(10L) } returns
            OfflineSupportMessageEntity(
                messageId = 700L, serverId = 10L, uuid = "msg-uuid",
                conversationId = 500L, senderId = 5L, senderType = "user", body = "See attached"
            )

        val service = createService()
        service.syncMessages(100L)

        coVerify {
            localDataService.saveSupportMessageAttachments(
                match { it.size == 1 && it.first().fileName == "screenshot.png" && it.first().messageId == 700L }
            )
        }
    }

    @Test
    fun `syncMessages skips attachments when owning message cannot be resolved (RP-FR-005)`() = runTest(testDispatcher) {
        val attachment = SupportMessageAttachmentDto(
            id = 50L, messageId = 10L, fileName = "x.png",
            fileUrl = "https://example.com/x.png", fileSize = 1, mimeType = "image/png"
        )
        val dto = createMessageDto(id = 10L, body = "See attached", attachments = listOf(attachment))
        coEvery { api.getSupportMessages(any()) } returns PaginatedResponse(data = listOf(dto))
        coEvery { localDataService.getSupportMessageByServerId(any()) } returns null

        createService().syncMessages(100L)

        // Better to skip than key the attachment by the wrong (server) id.
        coVerify(exactly = 0) { localDataService.saveSupportMessageAttachments(any()) }
    }

    @Test
    fun `syncMessages does not save attachments when none present`() = runTest(testDispatcher) {
        val dto = createMessageDto(id = 10L, body = "No attachment")
        val response = PaginatedResponse(data = listOf(dto))

        coEvery { api.getSupportMessages(100L) } returns response

        val service = createService()
        service.syncMessages(100L)

        coVerify(exactly = 0) { localDataService.saveSupportMessageAttachments(any()) }
    }

    @Test
    fun `syncMessages returns failure on network error`() = runTest(testDispatcher) {
        coEvery { api.getSupportMessages(any()) } throws IOException("Network error")

        val service = createService()
        val result = service.syncMessages(100L)

        assertThat(result.isFailure).isTrue()
    }

    // ============================================================================
    // RP-BUG-036: identity reconciliation on pull (no duplicate-on-refresh)
    // ============================================================================

    @Test
    fun `syncConversations reconciles by serverId preserving local PK and uuid`() = runTest(testDispatcher) {
        // Conversation created offline: local PK 500, serverId 900 (set by push), client uuid.
        coEvery { localDataService.getSupportConversationByServerId(900L) } returns
            OfflineSupportConversationEntity(
                conversationId = 500L, serverId = 900L, uuid = "client-uuid",
                userId = 5L, categoryId = 1L, subject = "Test"
            )
        // Server returns the same conversation with its OWN uuid ("conv-900").
        coEvery { api.getSupportConversations() } returns
            PaginatedResponse(data = listOf(createConversationDto(id = 900L, subject = "Test")))
        val savedSlot = slot<List<OfflineSupportConversationEntity>>()
        coJustRun { localDataService.saveSupportConversations(capture(savedSlot)) }

        createService().syncConversations()

        assertThat(savedSlot.captured).hasSize(1)
        // Updated in place: keep local PK + local uuid, do not insert a second row.
        assertThat(savedSlot.captured.first().conversationId).isEqualTo(500L)
        assertThat(savedSlot.captured.first().uuid).isEqualTo("client-uuid")
        assertThat(savedSlot.captured.first().serverId).isEqualTo(900L)
    }

    @Test
    fun `syncConversations inserts new conversation with no local match`() = runTest(testDispatcher) {
        coEvery { localDataService.getSupportConversationByServerId(any()) } returns null
        coEvery { api.getSupportConversations() } returns
            PaginatedResponse(data = listOf(createConversationDto(id = 901L, subject = "Test")))
        val savedSlot = slot<List<OfflineSupportConversationEntity>>()
        coJustRun { localDataService.saveSupportConversations(capture(savedSlot)) }

        createService().syncConversations()

        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured.first().conversationId).isEqualTo(0L) // autoGenerate insert
        assertThat(savedSlot.captured.first().uuid).isEqualTo("conv-901")
    }

    @Test
    fun `syncMessages reconciles by serverId and attaches to canonical local conversationId`() = runTest(testDispatcher) {
        // Conversation: local PK 500, serverId 900.
        coEvery { localDataService.getSupportConversationByServerId(900L) } returns
            OfflineSupportConversationEntity(
                conversationId = 500L, serverId = 900L, uuid = "client-uuid",
                userId = 5L, categoryId = 1L, subject = "Test"
            )
        // Message created offline: local PK 700, serverId 950 (set by push), client uuid.
        coEvery { localDataService.getSupportMessageByServerId(950L) } returns
            OfflineSupportMessageEntity(
                messageId = 700L, serverId = 950L, uuid = "client-msg-uuid",
                conversationId = 500L, senderId = 5L, senderType = "user", body = "hi"
            )
        coEvery { api.getSupportMessages(any()) } returns
            PaginatedResponse(data = listOf(createMessageDto(id = 950L, body = "hi")))
        val savedSlot = slot<List<OfflineSupportMessageEntity>>()
        coJustRun { localDataService.saveSupportMessages(capture(savedSlot)) }

        createService().syncMessages(conversationServerId = 900L)

        assertThat(savedSlot.captured).hasSize(1)
        val m = savedSlot.captured.first()
        // Updated in place AND attached to the canonical local conversationId (500), not 900.
        assertThat(m.messageId).isEqualTo(700L)
        assertThat(m.uuid).isEqualTo("client-msg-uuid")
        assertThat(m.conversationId).isEqualTo(500L)
        assertThat(m.serverId).isEqualTo(950L)
    }

    @Test
    fun `syncMessages inserts new message attached to canonical local conversationId`() = runTest(testDispatcher) {
        coEvery { localDataService.getSupportConversationByServerId(900L) } returns
            OfflineSupportConversationEntity(
                conversationId = 500L, serverId = 900L, uuid = "client-uuid",
                userId = 5L, categoryId = 1L, subject = "Test"
            )
        coEvery { localDataService.getSupportMessageByServerId(any()) } returns null
        coEvery { api.getSupportMessages(any()) } returns
            PaginatedResponse(data = listOf(createMessageDto(id = 951L, body = "hi")))
        val savedSlot = slot<List<OfflineSupportMessageEntity>>()
        coJustRun { localDataService.saveSupportMessages(capture(savedSlot)) }

        createService().syncMessages(conversationServerId = 900L)

        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured.first().messageId).isEqualTo(0L) // autoGenerate insert
        assertThat(savedSlot.captured.first().conversationId).isEqualTo(500L) // canonical local id, not 900
    }

    @Test
    fun `syncMessages falls back to server conversation id when conversation not synced locally`() = runTest(testDispatcher) {
        coEvery { localDataService.getSupportConversationByServerId(900L) } returns null
        coEvery { localDataService.getSupportMessageByServerId(any()) } returns null
        coEvery { api.getSupportMessages(any()) } returns
            PaginatedResponse(data = listOf(createMessageDto(id = 952L, body = "hi")))
        val savedSlot = slot<List<OfflineSupportMessageEntity>>()
        coJustRun { localDataService.saveSupportMessages(capture(savedSlot)) }

        createService().syncMessages(conversationServerId = 900L)

        // No local conversation yet → keep the DTO's conversationId mapping (no crash, no orphan logic).
        assertThat(savedSlot.captured).hasSize(1)
        assertThat(savedSlot.captured.first().conversationId).isEqualTo(100L)
    }

    // ============================================================================
    // Create Conversation Tests
    // ============================================================================

    @Test
    fun `createConversation saves locally and enqueues sync`() = runTest(testDispatcher) {
        val savedConversation = OfflineSupportConversationEntity(
            conversationId = 1L,
            uuid = "test-uuid",
            userId = 5L,
            categoryId = 2L,
            subject = "New issue",
            status = "open"
        )

        coEvery { localDataService.saveSupportConversation(any()) } returns 1L
        coEvery { localDataService.getSupportConversation(1L) } returns savedConversation

        val service = createService()
        val result = service.createConversation(
            userId = 5L,
            categoryId = 2L,
            subject = "New issue",
            initialMessageBody = "Please help"
        )

        assertThat(result.subject).isEqualTo("New issue")
        coVerify { localDataService.saveSupportConversation(any()) }
        coVerify { syncQueueEnqueuer.enqueueSupportConversationCreation(any(), "Please help") }
    }

    @Test
    fun `createConversation falls back to copy when getSupportConversation returns null`() = runTest(testDispatcher) {
        coEvery { localDataService.saveSupportConversation(any()) } returns 1L
        coEvery { localDataService.getSupportConversation(1L) } returns null

        val service = createService()
        val result = service.createConversation(
            userId = 5L,
            categoryId = 2L,
            subject = "Fallback test",
            initialMessageBody = "body"
        )

        assertThat(result.conversationId).isEqualTo(1L)
        assertThat(result.subject).isEqualTo("Fallback test")
    }

    // ============================================================================
    // Send Message Tests
    // ============================================================================

    @Test
    fun `sendMessage saves locally and enqueues sync`() = runTest(testDispatcher) {
        val conversation = OfflineSupportConversationEntity(
            conversationId = 10L,
            serverId = 100L,
            uuid = "conv-uuid",
            userId = 5L,
            categoryId = 1L,
            subject = "Test"
        )
        val savedMessage = OfflineSupportMessageEntity(
            messageId = 1L,
            uuid = "msg-uuid",
            conversationId = 10L,
            conversationServerId = 100L,
            senderId = 5L,
            senderType = "user",
            body = "Hello"
        )

        coEvery { localDataService.saveSupportMessage(any()) } returns 1L
        coEvery { localDataService.getSupportMessage(1L) } returns savedMessage

        val service = createService()
        val result = service.sendMessage(conversation, senderId = 5L, body = "Hello")

        assertThat(result.body).isEqualTo("Hello")
        coVerify { localDataService.saveSupportMessage(any()) }
        coVerify { localDataService.saveSupportConversation(match { it.conversationId == 10L }) }
        coVerify { syncQueueEnqueuer.enqueueSupportMessageCreation(any()) }
    }

    // ============================================================================
    // Close Conversation Tests
    // ============================================================================

    @Test
    fun `closeConversation calls API and updates local status`() = runTest(testDispatcher) {
        val conversation = OfflineSupportConversationEntity(
            conversationId = 10L,
            serverId = 100L,
            uuid = "conv-uuid",
            userId = 5L,
            categoryId = 1L,
            subject = "Test"
        )

        coJustRun { api.closeSupportConversation(100L) }

        val service = createService()
        val result = service.closeConversation(conversation)

        assertThat(result.isSuccess).isTrue()
        coVerify { api.closeSupportConversation(100L) }
        coVerify { localDataService.updateSupportConversationStatus(10L, "closed") }
    }

    @Test
    fun `closeConversation skips API when no serverId`() = runTest(testDispatcher) {
        val conversation = OfflineSupportConversationEntity(
            conversationId = 10L,
            serverId = null,
            uuid = "conv-uuid",
            userId = 5L,
            categoryId = 1L,
            subject = "Local only"
        )

        val service = createService()
        val result = service.closeConversation(conversation)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { api.closeSupportConversation(any()) }
        coVerify { localDataService.updateSupportConversationStatus(10L, "closed") }
    }

    @Test
    fun `closeConversation returns failure on API error`() = runTest(testDispatcher) {
        val conversation = OfflineSupportConversationEntity(
            conversationId = 10L,
            serverId = 100L,
            uuid = "conv-uuid",
            userId = 5L,
            categoryId = 1L,
            subject = "Test"
        )

        coEvery { api.closeSupportConversation(100L) } throws IOException("Server error")

        val service = createService()
        val result = service.closeConversation(conversation)

        assertThat(result.isFailure).isTrue()
    }

    // ============================================================================
    // Mark As Read Tests
    // ============================================================================

    @Test
    fun `markAsRead calls API and updates local state`() = runTest(testDispatcher) {
        val conversation = OfflineSupportConversationEntity(
            conversationId = 10L,
            serverId = 100L,
            uuid = "conv-uuid",
            userId = 5L,
            categoryId = 1L,
            subject = "Test",
            unreadCount = 3
        )

        coJustRun { api.markSupportMessagesAsRead(100L) }

        val service = createService()
        val result = service.markAsRead(conversation)

        assertThat(result.isSuccess).isTrue()
        coVerify { api.markSupportMessagesAsRead(100L) }
        coVerify { localDataService.markSupportMessagesAsRead(10L) }
        coVerify { localDataService.saveSupportConversation(match { it.unreadCount == 0 }) }
    }

    @Test
    fun `markAsRead skips API when no serverId`() = runTest(testDispatcher) {
        val conversation = OfflineSupportConversationEntity(
            conversationId = 10L,
            serverId = null,
            uuid = "conv-uuid",
            userId = 5L,
            categoryId = 1L,
            subject = "Local only",
            unreadCount = 1
        )

        val service = createService()
        val result = service.markAsRead(conversation)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 0) { api.markSupportMessagesAsRead(any()) }
        coVerify { localDataService.markSupportMessagesAsRead(10L) }
    }

    @Test
    fun `markAsRead returns failure on API error`() = runTest(testDispatcher) {
        val conversation = OfflineSupportConversationEntity(
            conversationId = 10L,
            serverId = 100L,
            uuid = "conv-uuid",
            userId = 5L,
            categoryId = 1L,
            subject = "Test"
        )

        coEvery { api.markSupportMessagesAsRead(100L) } throws IOException("Server error")

        val service = createService()
        val result = service.markAsRead(conversation)

        assertThat(result.isFailure).isTrue()
    }

    // ============================================================================
    // Helper Functions
    // ============================================================================

    private fun createCategoryDto(
        id: Long,
        name: String,
        description: String? = null
    ) = SupportCategoryDto(
        id = id,
        name = name,
        description = description,
        createdAt = "2025-01-15T10:00:00.000Z",
        updatedAt = "2025-01-15T10:00:00.000Z"
    )

    private fun createConversationDto(
        id: Long,
        subject: String,
        status: String = "open"
    ) = SupportConversationDto(
        id = id,
        uuid = "conv-$id",
        userId = 5L,
        categoryId = 1L,
        subject = subject,
        status = status,
        unreadCount = 0,
        lastMessageAt = null,
        category = null,
        createdAt = "2025-01-15T10:00:00.000Z",
        updatedAt = "2025-01-15T10:00:00.000Z"
    )

    private fun createMessageDto(
        id: Long,
        body: String,
        attachments: List<SupportMessageAttachmentDto>? = null
    ) = SupportMessageDto(
        id = id,
        uuid = "msg-$id",
        conversationId = 100L,
        senderId = 5L,
        senderType = "user",
        body = body,
        isRead = false,
        attachments = attachments,
        createdAt = "2025-01-15T10:00:00.000Z",
        updatedAt = "2025-01-15T10:00:00.000Z"
    )
}
