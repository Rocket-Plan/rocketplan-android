package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.offline.SupportConversationDto
import com.example.rocketplan_android.data.model.offline.SupportMessageDto
import com.example.rocketplan_android.data.repository.mapper.PendingSupportConversationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingSupportMessagePayload
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SupportPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val gson = Gson()

    private val ctx = PushHandlerTestFixtures.createContext(
        api = api,
        localDataService = localDataService,
        remoteLogger = remoteLogger
    )
    private val handler = SupportPushHandler(ctx)

    // ===== Conversation Create Tests =====

    @Test
    fun `handleConversationCreate creates conversation and updates serverId`() = runTest {
        val conversation = PushHandlerTestFixtures.createSupportConversation(
            conversationId = 1200L, serverId = null, uuid = "conv-uuid"
        )
        val payload = PendingSupportConversationPayload(
            localConversationId = 1200L,
            conversationUuid = "conv-uuid",
            categoryId = 1L,
            subject = "Test Subject",
            initialMessageBody = "Hello",
            idempotencyKey = "key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_conversation",
            entityId = 1200L,
            entityUuid = "conv-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )
        val conversationDto = mockk<SupportConversationDto>(relaxed = true) {
            every { id } returns 9000L
        }

        coEvery { localDataService.getSupportConversationByUuid("conv-uuid") } returns conversation
        coEvery { api.createSupportConversation(any()) } returns conversationDto

        val result = handler.handleConversationCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createSupportConversation(any()) }
        coVerify {
            localDataService.updateSupportConversationServerId(
                conversationId = 1200L,
                serverId = 9000L
            )
        }
    }

    @Test
    fun `handleConversationCreate returns DROP for invalid payload`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_conversation",
            entityId = 1200L,
            entityUuid = "conv-uuid",
            payload = "not valid json!!!".toByteArray(Charsets.UTF_8)
        )

        val result = handler.handleConversationCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createSupportConversation(any()) }
    }

    @Test
    fun `handleConversationCreate returns DROP when conversation not found locally`() = runTest {
        val payload = PendingSupportConversationPayload(
            localConversationId = 1200L,
            conversationUuid = "conv-uuid",
            categoryId = 1L,
            subject = "Test Subject",
            initialMessageBody = "Hello",
            idempotencyKey = "key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_conversation",
            entityId = 1200L,
            entityUuid = "conv-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )

        coEvery { localDataService.getSupportConversationByUuid("conv-uuid") } returns null

        val result = handler.handleConversationCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createSupportConversation(any()) }
    }

    @Test
    fun `handleConversationCreate returns SUCCESS when already synced`() = runTest {
        val conversation = PushHandlerTestFixtures.createSupportConversation(
            conversationId = 1200L, serverId = 9000L, uuid = "conv-uuid"
        )
        val payload = PendingSupportConversationPayload(
            localConversationId = 1200L,
            conversationUuid = "conv-uuid",
            categoryId = 1L,
            subject = "Test Subject",
            initialMessageBody = "Hello",
            idempotencyKey = "key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_conversation",
            entityId = 1200L,
            entityUuid = "conv-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )

        coEvery { localDataService.getSupportConversationByUuid("conv-uuid") } returns conversation

        val result = handler.handleConversationCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.createSupportConversation(any()) }
    }

    @Test
    fun `handleConversationCreate returns DROP on 422 validation error`() = runTest {
        val conversation = PushHandlerTestFixtures.createSupportConversation(
            conversationId = 1200L, serverId = null, uuid = "conv-uuid"
        )
        val payload = PendingSupportConversationPayload(
            localConversationId = 1200L,
            conversationUuid = "conv-uuid",
            categoryId = 1L,
            subject = "Test Subject",
            initialMessageBody = "Hello",
            idempotencyKey = "key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_conversation",
            entityId = 1200L,
            entityUuid = "conv-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )

        coEvery { localDataService.getSupportConversationByUuid("conv-uuid") } returns conversation
        coEvery { api.createSupportConversation(any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleConversationCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== Message Create Tests =====

    @Test
    fun `handleMessageCreate creates message and updates serverId`() = runTest {
        val message = PushHandlerTestFixtures.createSupportMessage(
            messageId = 1300L, serverId = null, uuid = "msg-uuid", conversationId = 1200L
        )
        val payload = PendingSupportMessagePayload(
            localMessageId = 1300L,
            messageUuid = "msg-uuid",
            conversationId = 1200L,
            conversationServerId = 9000L,
            body = "Hello support",
            idempotencyKey = "msg-key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_message",
            entityId = 1300L,
            entityUuid = "msg-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )
        val messageDto = mockk<SupportMessageDto>(relaxed = true) {
            every { id } returns 9500L
        }

        coEvery { localDataService.getSupportMessageByUuid("msg-uuid") } returns message
        coEvery { api.createSupportMessage(9000L, any()) } returns messageDto

        val result = handler.handleMessageCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createSupportMessage(9000L, any()) }
        coVerify {
            localDataService.updateSupportMessageServerId(
                messageId = 1300L,
                serverId = 9500L
            )
        }
    }

    @Test
    fun `handleMessageCreate returns DROP for invalid payload`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_message",
            entityId = 1300L,
            entityUuid = "msg-uuid",
            payload = "garbage data".toByteArray(Charsets.UTF_8)
        )

        val result = handler.handleMessageCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createSupportMessage(any(), any()) }
    }

    @Test
    fun `handleMessageCreate returns DROP when message not found locally`() = runTest {
        val payload = PendingSupportMessagePayload(
            localMessageId = 1300L,
            messageUuid = "msg-uuid",
            conversationId = 1200L,
            conversationServerId = 9000L,
            body = "Hello support",
            idempotencyKey = "msg-key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_message",
            entityId = 1300L,
            entityUuid = "msg-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )

        coEvery { localDataService.getSupportMessageByUuid("msg-uuid") } returns null

        val result = handler.handleMessageCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createSupportMessage(any(), any()) }
    }

    @Test
    fun `handleMessageCreate returns SUCCESS when already synced`() = runTest {
        val message = PushHandlerTestFixtures.createSupportMessage(
            messageId = 1300L, serverId = 9500L, uuid = "msg-uuid", conversationId = 1200L
        )
        val payload = PendingSupportMessagePayload(
            localMessageId = 1300L,
            messageUuid = "msg-uuid",
            conversationId = 1200L,
            conversationServerId = 9000L,
            body = "Hello support",
            idempotencyKey = "msg-key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_message",
            entityId = 1300L,
            entityUuid = "msg-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )

        coEvery { localDataService.getSupportMessageByUuid("msg-uuid") } returns message

        val result = handler.handleMessageCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.createSupportMessage(any(), any()) }
    }

    @Test
    fun `handleMessageCreate returns SKIP when conversation not synced`() = runTest {
        val message = PushHandlerTestFixtures.createSupportMessage(
            messageId = 1300L, serverId = null, uuid = "msg-uuid", conversationId = 1200L
        )
        val conversation = PushHandlerTestFixtures.createSupportConversation(
            conversationId = 1200L, serverId = null, uuid = "conv-uuid"
        )
        val payload = PendingSupportMessagePayload(
            localMessageId = 1300L,
            messageUuid = "msg-uuid",
            conversationId = 1200L,
            conversationServerId = null,
            body = "Hello support",
            idempotencyKey = "msg-key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_message",
            entityId = 1300L,
            entityUuid = "msg-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )

        coEvery { localDataService.getSupportMessageByUuid("msg-uuid") } returns message
        coEvery { localDataService.getSupportConversation(1200L) } returns conversation

        val result = handler.handleMessageCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createSupportMessage(any(), any()) }
    }

    @Test
    fun `handleMessageCreate returns DROP on 422 validation error`() = runTest {
        val message = PushHandlerTestFixtures.createSupportMessage(
            messageId = 1300L, serverId = null, uuid = "msg-uuid", conversationId = 1200L
        )
        val payload = PendingSupportMessagePayload(
            localMessageId = 1300L,
            messageUuid = "msg-uuid",
            conversationId = 1200L,
            conversationServerId = 9000L,
            body = "Hello support",
            idempotencyKey = "msg-key-123"
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "support_message",
            entityId = 1300L,
            entityUuid = "msg-uuid",
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        )

        coEvery { localDataService.getSupportMessageByUuid("msg-uuid") } returns message
        coEvery { api.createSupportMessage(9000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleMessageCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }
}
