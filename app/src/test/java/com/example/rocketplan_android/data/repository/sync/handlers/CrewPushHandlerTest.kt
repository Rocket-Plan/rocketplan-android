package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.repository.mapper.PendingProjectUserPayload
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class CrewPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)

    private val ctx = PushHandlerTestFixtures.createContext(
        api = api,
        localDataService = localDataService,
        remoteLogger = remoteLogger
    )

    private val handler = CrewPushHandler(ctx)

    private fun createPayload(
        projectServerId: Long = 1000L,
        userServerId: Long = 2000L
    ): ByteArray {
        val payload = PendingProjectUserPayload(
            projectServerId = projectServerId,
            userServerId = userServerId
        )
        return PushHandlerTestFixtures.gson.toJson(payload).toByteArray(Charsets.UTF_8)
    }

    private fun createOperation(
        entityId: Long = 100L,
        payload: ByteArray = ByteArray(0),
        operationType: SyncOperationType = SyncOperationType.CREATE
    ) = OfflineSyncQueueEntity(
        operationId = "crew-${System.nanoTime()}",
        entityType = "project_user",
        entityId = entityId,
        entityUuid = "crew-uuid",
        operationType = operationType,
        payload = payload,
        priority = SyncPriority.MEDIUM,
        createdAt = Date()
    )

    private fun create204Response(): Response<Unit> =
        Response.success(Unit)

    private fun create404Response(): Response<Unit> {
        val body = "".toResponseBody("application/json".toMediaType())
        return Response.error(404, body)
    }

    private fun create422Response(): Response<Unit> {
        val body = """{"error":"validation failed"}""".toResponseBody("application/json".toMediaType())
        return Response.error(422, body)
    }

    // ===== handleAdd Tests =====

    @Test
    fun `handleAdd happy path returns SUCCESS`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.CREATE, payload = payload)

        coEvery { api.addUserToProject(1000L, 2000L) } returns create204Response()

        val result = handler.handleAdd(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.addUserToProject(1000L, 2000L) }
        coVerify { localDataService.clearProjectUserPendingAdd(1000L, 2000L) }
    }

    @Test
    fun `handleAdd returns DROP for invalid payload`() = runTest {
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = "invalid json".toByteArray(Charsets.UTF_8)
        )

        val result = handler.handleAdd(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.addUserToProject(any(), any()) }
    }

    @Test
    fun `handleAdd throws on HTTP error and non-validation error`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.CREATE, payload = payload)

        coEvery { api.addUserToProject(1000L, 2000L) } throws PushHandlerTestFixtures.create409WithUpdatedAt()

        var thrownException: Exception? = null
        try {
            handler.handleAdd(operation)
        } catch (e: Exception) {
            thrownException = e
        }

        assertThat(thrownException).isNotNull()
        coVerify { api.addUserToProject(1000L, 2000L) }
    }

    @Test
    fun `handleAdd returns DROP on 422 validation error`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.CREATE, payload = payload)

        coEvery { api.addUserToProject(1000L, 2000L) } returns create422Response()

        val result = handler.handleAdd(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify { localDataService.deleteProjectUser(1000L, 2000L) }
    }

    @Test
    fun `handleAdd returns DROP when project not found on server`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.CREATE, payload = payload)

        coEvery { api.addUserToProject(1000L, 2000L) } returns create404Response()

        val result = handler.handleAdd(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify { localDataService.deleteProjectUser(1000L, 2000L) }
    }

    // ===== handleRemove Tests =====

    @Test
    fun `handleRemove happy path returns SUCCESS`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.DELETE, payload = payload)

        coEvery { api.removeUserFromProject(1000L, 2000L) } returns create204Response()

        val result = handler.handleRemove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.removeUserFromProject(1000L, 2000L) }
        coVerify { localDataService.deleteProjectUser(1000L, 2000L) }
    }

    @Test
    fun `handleRemove returns DROP for invalid payload`() = runTest {
        val operation = createOperation(
            operationType = SyncOperationType.DELETE,
            payload = "invalid json".toByteArray(Charsets.UTF_8)
        )

        val result = handler.handleRemove(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.removeUserFromProject(any(), any()) }
    }

    @Test
    fun `handleRemove returns SUCCESS when project not found on server`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.DELETE, payload = payload)

        coEvery { api.removeUserFromProject(1000L, 2000L) } returns create404Response()

        val result = handler.handleRemove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { localDataService.deleteProjectUser(1000L, 2000L) }
    }

    @Test
    fun `handleRemove returns DROP on 422 validation error`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.DELETE, payload = payload)

        coEvery { api.removeUserFromProject(1000L, 2000L) } returns create422Response()

        val result = handler.handleRemove(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify { localDataService.deleteProjectUser(1000L, 2000L) }
    }

    @Test
    fun `handleRemove throws on other HTTP errors`() = runTest {
        val payload = createPayload()
        val operation = createOperation(operationType = SyncOperationType.DELETE, payload = payload)

        coEvery { api.removeUserFromProject(1000L, 2000L) } throws PushHandlerTestFixtures.create409WithUpdatedAt()

        var thrownException: Exception? = null
        try {
            handler.handleRemove(operation)
        } catch (e: Exception) {
            thrownException = e
        }

        assertThat(thrownException).isNotNull()
        coVerify { api.removeUserFromProject(1000L, 2000L) }
    }
}
