package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.model.AddressResourceResponse
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.ProjectDetailResourceResponse
import com.example.rocketplan_android.data.model.ProjectResourceResponse
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.repository.mapper.PendingProjectCreationPayload
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.gson
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

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectPushHandlerTest {

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

    private val handler = ProjectPushHandler(ctx)

    // ===== Helpers =====

    private fun createPayload(
        localProjectId: Long = 100L,
        projectUuid: String = "project-uuid",
        companyId: Long = 1L,
        projectStatusId: Int = 1,
        address: String = "123 Test St",
        idempotencyKey: String? = null
    ): ByteArray {
        val payload = PendingProjectCreationPayload(
            localProjectId = localProjectId,
            projectUuid = projectUuid,
            companyId = companyId,
            projectStatusId = projectStatusId,
            addressRequest = CreateAddressRequest(
                address = address,
                city = "Test City",
                state = "TS",
                zip = "12345"
            ),
            idempotencyKey = idempotencyKey
        )
        return gson.toJson(payload).toByteArray(Charsets.UTF_8)
    }

    private fun createOperation(
        operationType: SyncOperationType = SyncOperationType.UPDATE,
        entityId: Long = 100L,
        payload: ByteArray = ByteArray(0)
    ) = PushHandlerTestFixtures.createSyncOperation(
        entityType = "project",
        entityId = entityId,
        entityUuid = "project-uuid",
        operationType = operationType,
        payload = payload
    )

    private fun mockAddressResponse(addressId: Long = 5000L, address: String = "123 Test St"): AddressResourceResponse {
        val addressDto = mockk<ProjectAddressDto>(relaxed = true) {
            coEvery { id } returns addressId
            coEvery { this@mockk.address } returns address
        }
        return mockk<AddressResourceResponse> {
            coEvery { data } returns addressDto
        }
    }

    private fun mockProjectResponse(
        projectId: Long = 1000L,
        companyId: Long? = 1L,
        uuid: String? = "project-uuid",
        uid: String? = "P-001",
        createdAt: String? = "2026-01-30T12:00:00.000000Z",
        updatedAt: String? = "2026-01-30T12:00:00.000000Z",
        propertyId: Long? = null,
        alias: String? = null
    ): ProjectResourceResponse {
        val dto = mockk<ProjectDto>(relaxed = true) {
            coEvery { this@mockk.id } returns projectId
            coEvery { this@mockk.companyId } returns companyId
            coEvery { this@mockk.uuid } returns uuid
            coEvery { this@mockk.uid } returns uid
            coEvery { this@mockk.createdAt } returns createdAt
            coEvery { this@mockk.updatedAt } returns updatedAt
            coEvery { this@mockk.propertyId } returns propertyId
            coEvery { this@mockk.alias } returns alias
        }
        return mockk<ProjectResourceResponse> {
            coEvery { data } returns dto
        }
    }

    private fun mockProjectDetailResponse(
        projectId: Long = 1000L,
        updatedAt: String? = "2026-01-30T14:00:00.000000Z",
        alias: String? = null,
        status: String? = "active"
    ): ProjectDetailResourceResponse {
        val dto = mockk<ProjectDetailDto>(relaxed = true) {
            coEvery { this@mockk.id } returns projectId
            coEvery { this@mockk.updatedAt } returns updatedAt
            coEvery { this@mockk.alias } returns alias
            coEvery { this@mockk.status } returns status
        }
        return mockk<ProjectDetailResourceResponse> {
            coEvery { data } returns dto
        }
    }

    private fun create409Response(): Response<Unit> {
        val body = """{"updated_at":"2026-01-30T12:00:00.000000Z"}"""
            .toResponseBody("application/json".toMediaType())
        return Response.error(409, body)
    }

    private fun create404Response(): Response<Unit> {
        val body = "".toResponseBody("application/json".toMediaType())
        return Response.error(404, body)
    }

    private fun create410Response(): Response<Unit> {
        val body = "".toResponseBody("application/json".toMediaType())
        return Response.error(410, body)
    }

    private fun create422Response(): Response<Unit> {
        val body = """{"error":"validation failed"}"""
            .toResponseBody("application/json".toMediaType())
        return Response.error(422, body)
    }

    // ===== handleCreate Tests =====

    @Test
    fun `handleCreate happy path returns PendingProjectSyncResult`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = null)
        val payload = createPayload()
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = payload
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createAddress(any()) } returns mockAddressResponse()
        coEvery { api.createCompanyProject(1L, any()) } returns mockProjectResponse()

        val result = handler.handleCreate(operation)

        assertThat(result).isNotNull()
        assertThat(result!!.localProjectId).isEqualTo(100L)
        assertThat(result.serverProjectId).isEqualTo(1000L)

        coVerify { api.createAddress(any()) }
        coVerify { api.createCompanyProject(1L, any()) }
        coVerify { localDataService.saveProjects(any()) }
    }

    @Test
    fun `handleCreate returns null for invalid payload`() = runTest {
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = "invalid json garbage".toByteArray(Charsets.UTF_8)
        )

        val result = handler.handleCreate(operation)

        assertThat(result).isNull()
        coVerify(exactly = 0) { api.createAddress(any()) }
    }

    @Test
    fun `handleCreate returns null when project not found locally`() = runTest {
        val payload = createPayload()
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = payload
        )

        coEvery { localDataService.getProject(100L) } returns null

        val result = handler.handleCreate(operation)

        assertThat(result).isNull()
        coVerify(exactly = 0) { api.createAddress(any()) }
    }

    @Test
    fun `handleCreate returns null on 422 from createAddress`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = null)
        val payload = createPayload()
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = payload
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createAddress(any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleCreate(operation)

        assertThat(result).isNull()
        coVerify(exactly = 0) { api.createCompanyProject(any(), any()) }
    }

    @Test
    fun `handleCreate returns null on 422 from createCompanyProject`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = null)
        val payload = createPayload()
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = payload
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createAddress(any()) } returns mockAddressResponse()
        coEvery { api.createCompanyProject(1L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleCreate(operation)

        assertThat(result).isNull()
        coVerify { api.createAddress(any()) }
    }

    @Test
    fun `handleCreate throws on company mismatch`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = null)
        val payload = createPayload(companyId = 1L)
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = payload
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createAddress(any()) } returns mockAddressResponse()
        // Server returns companyId=999, but we requested companyId=1
        coEvery { api.createCompanyProject(1L, any()) } returns mockProjectResponse(companyId = 999L)

        var thrownException: Exception? = null
        try {
            handler.handleCreate(operation)
        } catch (e: IllegalStateException) {
            thrownException = e
        }

        assertThat(thrownException).isNotNull()
        assertThat(thrownException!!.message).contains("wrong company")
    }

    @Test
    fun `handleCreate updates alias when project has pending alias`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = null,
            alias = "Custom Alias"
        )
        val payload = createPayload()
        val operation = createOperation(
            operationType = SyncOperationType.CREATE,
            payload = payload
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createAddress(any()) } returns mockAddressResponse()
        // Server creates project without alias
        coEvery { api.createCompanyProject(1L, any()) } returns mockProjectResponse(alias = null)
        // Alias update succeeds
        coEvery { api.updateProject(1000L, any()) } returns mockProjectResponse(alias = "Custom Alias")

        val result = handler.handleCreate(operation)

        assertThat(result).isNotNull()
        assertThat(result!!.serverProjectId).isEqualTo(1000L)

        coVerify { api.updateProject(1000L, match { it.alias == "Custom Alias" }) }
        // saveProjects called twice: once after create, once after alias update
        coVerify(atLeast = 2) { localDataService.saveProjects(any()) }
    }

    // ===== handleUpdate Tests =====

    @Test
    fun `handleUpdate happy path returns SUCCESS`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L,
            status = "wip"
        )
        val operation = createOperation()

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateProject(1000L, any()) } returns mockProjectResponse()

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateProject(1000L, any()) }
        coVerify { localDataService.saveProjects(any()) }
    }

    @Test
    fun `handleUpdate returns DROP when project not found`() = runTest {
        val operation = createOperation()

        coEvery { localDataService.getProject(100L) } returns null

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.updateProject(any(), any()) }
    }

    @Test
    fun `handleUpdate returns SKIP when no serverId`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = null
        )
        val operation = createOperation()

        coEvery { localDataService.getProject(100L) } returns project

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.updateProject(any(), any()) }
    }

    @Test
    fun `handleUpdate 409 then retry succeeds returns SUCCESS`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L,
            status = "wip"
        )
        val operation = createOperation()

        coEvery { localDataService.getProject(100L) } returns project
        // First call: 409 conflict
        coEvery { api.updateProject(1000L, any()) } throws PushHandlerTestFixtures.create409WithUpdatedAt() andThen mockProjectResponse()
        // Fetch fresh project detail
        coEvery { api.getProjectDetail(1000L, any()) } returns mockProjectDetailResponse()

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.getProjectDetail(1000L, any()) }
        // updateProject called twice: original + retry
        coVerify(exactly = 2) { api.updateProject(1000L, any()) }
    }

    @Test
    fun `handleUpdate double 409 returns CONFLICT_PENDING`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L,
            status = "wip"
        )
        val operation = createOperation()

        coEvery { localDataService.getProject(100L) } returns project
        // Both calls: 409 conflict
        coEvery { api.updateProject(1000L, any()) } throws PushHandlerTestFixtures.create409WithUpdatedAt()
        coEvery { api.getProjectDetail(1000L, any()) } returns mockProjectDetailResponse()

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify { localDataService.upsertConflict(any()) }
    }

    @Test
    fun `handleUpdate 422 returns DROP`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L,
            status = "wip"
        )
        val operation = createOperation()

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateProject(1000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== handleDelete Tests =====

    @Test
    fun `handleDelete happy path returns SUCCESS`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L
        )
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.deleteProject(1000L, any()) } returns Response.success(Unit)

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteProject(1000L, any()) }
        coVerify {
            localDataService.saveProjects(match { list ->
                list.any { it.isDeleted && it.syncStatus == SyncStatus.SYNCED && !it.isDirty }
            })
        }
    }

    @Test
    fun `handleDelete returns DROP when project not found locally`() = runTest {
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns null

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.deleteProject(any(), any()) }
    }

    @Test
    fun `handleDelete returns SUCCESS when no serverId`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = null
        )
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns project

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deleteProject(any(), any()) }
    }

    @Test
    fun `handleDelete 409 then retry succeeds returns SUCCESS`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L
        )
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns project
        // First call: 409
        coEvery { api.deleteProject(1000L, any()) } returns create409Response() andThen Response.success(Unit)
        coEvery { api.getProjectDetail(1000L, any()) } returns mockProjectDetailResponse()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.getProjectDetail(1000L, any()) }
        coVerify(exactly = 2) { api.deleteProject(1000L, any()) }
    }

    @Test
    fun `handleDelete double 409 restores from server and returns DROP`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L
        )
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns project
        // Both calls: 409
        coEvery { api.deleteProject(1000L, any()) } returns create409Response()
        coEvery { api.getProjectDetail(1000L, any()) } returns mockProjectDetailResponse()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        // Verify the project was restored from server data
        coVerify {
            localDataService.saveProjects(match { list ->
                list.isNotEmpty()
            })
        }
    }

    @Test
    fun `handleDelete 404 returns SUCCESS`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L
        )
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.deleteProject(1000L, any()) } returns create404Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveProjects(match { list ->
                list.any { it.isDeleted }
            })
        }
    }

    @Test
    fun `handleDelete 410 returns SUCCESS`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L
        )
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.deleteProject(1000L, any()) } returns create410Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveProjects(match { list ->
                list.any { it.isDeleted }
            })
        }
    }

    @Test
    fun `handleDelete 422 returns DROP`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L
        )
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.deleteProject(1000L, any()) } returns create422Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }
}
