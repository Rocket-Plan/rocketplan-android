package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.PropertyResourceResponse
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.repository.ImageProcessorRepository
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyUpdatePayload
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.create404Response
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.create409WithUpdatedAt
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.create410Response
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.create422Response
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PropertyPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val queueManager: ImageProcessorQueueManager = mockk(relaxed = true)
    private val imageProcessorRepository: ImageProcessorRepository = mockk(relaxed = true)
    private val gson = Gson()

    private val persistedProperties = mutableListOf<PropertyDto>()

    private fun createContext() = PushHandlerContext(
        api = api,
        localDataService = localDataService,
        gson = gson,
        remoteLogger = remoteLogger,
        syncProjectEssentials = { mockk() },
        persistProperty = { _, property, _, _, _ ->
            persistedProperties.add(property)
            PushHandlerTestFixtures.createProperty()
        },
        imageProcessorQueueManagerProvider = { queueManager },
        imageProcessorRepositoryProvider = { imageProcessorRepository }
    )

    private fun createPropertyDto(
        id: Long = 2000L,
        uuid: String = "prop-uuid",
        propertyTypeId: Long? = 1L,
        name: String? = "Test Property",
        createdAt: String? = "2026-01-30T12:00:00.000000Z",
        updatedAt: String? = "2026-01-30T12:00:00.000000Z"
    ) = PropertyDto(
        id = id,
        uuid = uuid,
        address = "123 Test St",
        city = "Testville",
        state = "TX",
        postalCode = "12345",
        latitude = null,
        longitude = null,
        propertyTypeId = propertyTypeId,
        propertyType = "residential",
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun createCreationPayloadBytes(
        localPropertyId: Long = 200L,
        propertyUuid: String = "prop-uuid",
        projectId: Long = 100L,
        propertyTypeId: Int = 1,
        propertyTypeValue: String? = "residential",
        idempotencyKey: String? = "idem-key-1"
    ): ByteArray {
        val payload = PendingPropertyCreationPayload(
            localPropertyId = localPropertyId,
            propertyUuid = propertyUuid,
            projectId = projectId,
            propertyTypeId = propertyTypeId,
            propertyTypeValue = propertyTypeValue,
            idempotencyKey = idempotencyKey
        )
        return gson.toJson(payload).toByteArray(Charsets.UTF_8)
    }

    private fun createUpdatePayloadBytes(
        projectId: Long = 100L,
        propertyId: Long = 200L,
        propertyTypeId: Int = 1,
        name: String? = "Updated Property",
        propertyTypeValue: String? = "residential",
        lockUpdatedAt: String? = "2026-01-30T12:00:00.000000Z"
    ): ByteArray {
        val payload = PendingPropertyUpdatePayload(
            projectId = projectId,
            propertyId = propertyId,
            request = PropertyMutationRequest(
                propertyTypeId = propertyTypeId,
                name = name
            ),
            propertyTypeValue = propertyTypeValue,
            lockUpdatedAt = lockUpdatedAt
        )
        return gson.toJson(payload).toByteArray(Charsets.UTF_8)
    }

    // =====================================================================
    // handleCreate tests
    // =====================================================================

    @Test
    fun `handleCreate happy path - creates property and persists`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val existing = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = null)
        val responseDto = createPropertyDto(id = 2000L, uuid = "prop-uuid")

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns existing
        coEvery { api.createProjectProperty(1000L, any()) } returns PropertyResourceResponse(responseDto)
        coEvery { api.getProperty(2000L) } returns PropertyResourceResponse(responseDto)
        coEvery { localDataService.getLocations(100L) } returns emptyList()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            entityUuid = "prop-uuid",
            operationType = SyncOperationType.CREATE,
            payload = createCreationPayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createProjectProperty(1000L, any()) }
        coVerify { api.getProperty(2000L) }
        assertThat(persistedProperties).hasSize(1)
        assertThat(persistedProperties[0].id).isEqualTo(2000L)
    }

    @Test
    fun `handleCreate returns DROP for invalid payload`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.CREATE,
            payload = "not valid json{{{".toByteArray(Charsets.UTF_8)
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createProjectProperty(any(), any()) }
    }

    @Test
    fun `handleCreate returns SKIP when project not found`() = runTest {
        coEvery { localDataService.getProject(100L) } returns null

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.CREATE,
            payload = createCreationPayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createProjectProperty(any(), any()) }
    }

    @Test
    fun `handleCreate returns SKIP when project has no serverId`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = null)
        coEvery { localDataService.getProject(100L) } returns project

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.CREATE,
            payload = createCreationPayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createProjectProperty(any(), any()) }
    }

    @Test
    fun `handleCreate returns DROP on 422 validation error`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createProjectProperty(1000L, any()) } throws create422Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.CREATE,
            payload = createCreationPayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleCreate syncs local level serverIds after creation`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val existing = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = null)
        val responseDto = createPropertyDto(id = 2000L)

        val pendingLocation = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = null,
            projectId = 100L,
            title = "First Floor",
            type = "level"
        )
        val remoteLevel = LocationDto(
            id = 3000L,
            uuid = "remote-level-uuid",
            projectId = 100L,
            title = "First Floor",
            name = null,
            type = "level",
            locationType = null,
            parentLocationId = null,
            isAccessible = null,
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T12:00:00.000000Z"
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns existing
        coEvery { api.createProjectProperty(1000L, any()) } returns PropertyResourceResponse(responseDto)
        coEvery { api.getProperty(2000L) } returns PropertyResourceResponse(responseDto)
        coEvery { localDataService.getLocations(100L) } returns listOf(pendingLocation)
        coEvery { api.getPropertyLevels(2000L) } returns PaginatedResponse(data = listOf(remoteLevel))
        coEvery { api.getPropertyLocations(2000L) } returns PaginatedResponse(data = emptyList())

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.CREATE,
            payload = createCreationPayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.getPropertyLevels(2000L) }
        coVerify {
            localDataService.saveLocations(match { locations ->
                locations.any { it.serverId == 3000L && it.syncStatus == SyncStatus.SYNCED }
            })
        }
    }

    @Test
    fun `handleCreate uses refreshed property data when getProperty succeeds`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val existing = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = null)
        val createDto = createPropertyDto(id = 2000L, name = "Create Response")
        val refreshedDto = createPropertyDto(id = 2000L, name = "Refreshed Response")

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns existing
        coEvery { api.createProjectProperty(1000L, any()) } returns PropertyResourceResponse(createDto)
        coEvery { api.getProperty(2000L) } returns PropertyResourceResponse(refreshedDto)
        coEvery { localDataService.getLocations(100L) } returns emptyList()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.CREATE,
            payload = createCreationPayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        handler.handleCreate(operation)

        assertThat(persistedProperties).hasSize(1)
        assertThat(persistedProperties[0].name).isEqualTo("Refreshed Response")
    }

    @Test
    fun `handleCreate falls back to create response when getProperty fails`() = runTest {
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val existing = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = null)
        val createDto = createPropertyDto(id = 2000L, name = "Create Response")

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns existing
        coEvery { api.createProjectProperty(1000L, any()) } returns PropertyResourceResponse(createDto)
        coEvery { api.getProperty(2000L) } throws RuntimeException("network error")
        coEvery { localDataService.getLocations(100L) } returns emptyList()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.CREATE,
            payload = createCreationPayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        assertThat(persistedProperties).hasSize(1)
        assertThat(persistedProperties[0].name).isEqualTo("Create Response")
    }

    // =====================================================================
    // handleUpdate tests
    // =====================================================================

    @Test
    fun `handleUpdate happy path - updates property and persists`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val updatedDto = createPropertyDto(id = 2000L, name = "Updated Name")

        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.updateProperty(2000L, any()) } returns PropertyResourceResponse(updatedDto)
        coEvery { api.getProperty(2000L) } returns PropertyResourceResponse(updatedDto)

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = createUpdatePayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateProperty(2000L, any()) }
        assertThat(persistedProperties).hasSize(1)
    }

    @Test
    fun `handleUpdate returns DROP for invalid payload`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = "garbage payload!!!".toByteArray(Charsets.UTF_8)
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpdate returns DROP when property not found`() = runTest {
        coEvery { localDataService.getProperty(200L) } returns null

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = createUpdatePayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.updateProperty(any(), any()) }
    }

    @Test
    fun `handleUpdate returns SKIP when property has no serverId`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = null)
        coEvery { localDataService.getProperty(200L) } returns property

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = createUpdatePayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.updateProperty(any(), any()) }
    }

    @Test
    fun `handleUpdate on 409 - retry succeeds after fetching fresh data`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val freshDto = createPropertyDto(id = 2000L, updatedAt = "2026-01-31T00:00:00.000000Z")
        val retryDto = createPropertyDto(id = 2000L, name = "Retry Success")

        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.updateProperty(2000L, any()) } throws create409WithUpdatedAt() andThen PropertyResourceResponse(retryDto)
        coEvery { api.getProperty(2000L) } returns PropertyResourceResponse(freshDto)

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = createUpdatePayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // First call throws 409, second call succeeds
        coVerify(exactly = 2) { api.updateProperty(2000L, any()) }
        coVerify { api.getProperty(2000L) }
    }

    @Test
    fun `handleUpdate on double 409 - records conflict`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val freshDto = createPropertyDto(id = 2000L, updatedAt = "2026-01-31T00:00:00.000000Z")

        coEvery { localDataService.getProperty(200L) } returns property
        // Both calls throw 409
        coEvery { api.updateProperty(2000L, any()) } throws create409WithUpdatedAt()
        coEvery { api.getProperty(2000L) } returns PropertyResourceResponse(freshDto)

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = createUpdatePayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify { localDataService.upsertConflict(any()) }
    }

    @Test
    fun `handleUpdate returns DROP on 422 validation error`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.updateProperty(2000L, any()) } throws create422Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = createUpdatePayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpdate on 409 returns SKIP when fresh fetch fails`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.updateProperty(2000L, any()) } throws create409WithUpdatedAt()
        coEvery { api.getProperty(2000L) } throws RuntimeException("network error")

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.UPDATE,
            payload = createUpdatePayloadBytes()
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    // =====================================================================
    // handleDelete tests
    // =====================================================================

    @Test
    fun `handleDelete happy path - soft deletes property`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.deleteProperty(2000L, any()) } returns Unit

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.DELETE
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteProperty(2000L, any()) }
        coVerify {
            localDataService.saveProperty(match { saved ->
                saved.isDeleted && !saved.isDirty && saved.syncStatus == SyncStatus.SYNCED
            })
        }
        coVerify { localDataService.clearProjectPropertyId(200L) }
    }

    @Test
    fun `handleDelete returns DROP when property not found`() = runTest {
        coEvery { localDataService.getProperty(200L) } returns null

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.DELETE
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.deleteProperty(any(), any()) }
    }

    @Test
    fun `handleDelete with no serverId - cascade deletes locally`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = null)
        coEvery { localDataService.getProperty(200L) } returns property

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.DELETE
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deleteProperty(any(), any()) }
        coVerify { localDataService.clearProjectPropertyId(200L) }
        coVerify { localDataService.deleteProperty(200L) }
    }

    @Test
    fun `handleDelete returns SUCCESS on 404 (already deleted on server)`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.deleteProperty(2000L, any()) } throws create404Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.DELETE
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveProperty(match { it.isDeleted })
        }
    }

    @Test
    fun `handleDelete returns SUCCESS on 410 (gone on server)`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.deleteProperty(2000L, any()) } throws create410Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.DELETE
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveProperty(match { it.isDeleted })
        }
    }

    @Test
    fun `handleDelete returns DROP on 422 validation error`() = runTest {
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.deleteProperty(2000L, any()) } throws create422Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "property",
            entityId = 200L,
            operationType = SyncOperationType.DELETE
        )

        val handler = PropertyPushHandler(createContext())
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { localDataService.saveProperty(any<OfflinePropertyEntity>()) }
    }
}
