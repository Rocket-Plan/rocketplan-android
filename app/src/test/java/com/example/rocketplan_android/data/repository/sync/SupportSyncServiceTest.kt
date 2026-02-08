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

        val service = createService()
        service.syncMessages(100L)

        coVerify { localDataService.saveSupportMessageAttachments(match { it.size == 1 && it.first().fileName == "screenshot.png" }) }
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
