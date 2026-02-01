package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineSupportCategoryEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportConversationEntity
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.SupportCategoryDto
import com.example.rocketplan_android.data.model.offline.SupportConversationDto
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
}
