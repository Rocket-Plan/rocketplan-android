package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.repository.mapper.PendingLocationCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingLocationUpdatePayload
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val gson = Gson()

    private val ctx = PushHandlerTestFixtures.createContext(api, localDataService, remoteLogger)
    private val handler = LocationPushHandler(ctx)

    // ===== Helpers =====

    private fun createCreatePayload(
        localLocationId: Long = 300L,
        locationUuid: String = "location-uuid",
        projectId: Long = 100L,
        propertyLocalId: Long = 200L,
        locationName: String = "First Floor",
        locationTypeId: Long = 1L,
        type: String = "level",
        floorNumber: Int = 1,
        isCommon: Boolean = false,
        isAccessible: Boolean = true,
        isCommercial: Boolean = false,
        idempotencyKey: String? = "idem-key-1"
    ) = PendingLocationCreationPayload(
        localLocationId = localLocationId,
        locationUuid = locationUuid,
        projectId = projectId,
        propertyLocalId = propertyLocalId,
        locationName = locationName,
        locationTypeId = locationTypeId,
        type = type,
        floorNumber = floorNumber,
        isCommon = isCommon,
        isAccessible = isAccessible,
        isCommercial = isCommercial,
        idempotencyKey = idempotencyKey
    )

    private fun createUpdatePayload(
        locationId: Long = 300L,
        locationUuid: String = "location-uuid",
        name: String? = "Updated Floor",
        floorNumber: Int? = 2,
        isAccessible: Boolean? = false,
        lockUpdatedAt: String? = null
    ) = PendingLocationUpdatePayload(
        locationId = locationId,
        locationUuid = locationUuid,
        name = name,
        floorNumber = floorNumber,
        isAccessible = isAccessible,
        lockUpdatedAt = lockUpdatedAt
    )

    private fun createOperation(
        entityType: String = "location",
        entityId: Long = 300L,
        entityUuid: String = "location-uuid",
        operationType: SyncOperationType = SyncOperationType.CREATE,
        payload: ByteArray = ByteArray(0)
    ) = PushHandlerTestFixtures.createSyncOperation(
        entityType = entityType,
        entityId = entityId,
        entityUuid = entityUuid,
        operationType = operationType,
        payload = payload
    )

    private fun createLocationDto(
        id: Long = 3000L,
        uuid: String? = "location-uuid",
        name: String? = "First Floor",
        title: String? = "First Floor",
        type: String? = "level",
        isAccessible: Boolean? = true,
        updatedAt: String? = "2026-01-30T12:00:00.000000Z"
    ) = mockk<LocationDto>(relaxed = true) {
        every { this@mockk.id } returns id
        every { this@mockk.uuid } returns uuid
        every { this@mockk.name } returns name
        every { this@mockk.title } returns title
        every { this@mockk.type } returns type
        every { this@mockk.isAccessible } returns isAccessible
        every { this@mockk.updatedAt } returns updatedAt
        every { this@mockk.projectId } returns null
        every { this@mockk.locationType } returns null
        every { this@mockk.parentLocationId } returns null
        every { this@mockk.createdAt } returns "2026-01-30T12:00:00.000000Z"
    }

    private fun payloadBytes(payload: Any): ByteArray =
        gson.toJson(payload).toByteArray(Charsets.UTF_8)

    // ===== handleCreate Tests =====

    @Test
    fun `handleCreate happy path creates location and saves entity`() = runTest {
        val payload = createCreatePayload()
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val locationDto = createLocationDto()

        val operation = createOperation(payload = payloadBytes(payload))

        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { localDataService.getLocationByUuid("location-uuid") } returns null
        coEvery { localDataService.getLocations(100L) } returns emptyList()
        coEvery { api.createLocation(2000L, any()) } returns locationDto
        coEvery { localDataService.saveLocations(any()) } just runs

        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createLocation(2000L, any()) }
        coVerify { localDataService.saveLocations(match { it.size == 1 && it[0].syncStatus == SyncStatus.SYNCED }) }
    }

    @Test
    fun `handleCreate returns DROP for invalid payload`() = runTest {
        val operation = createOperation(payload = "not valid json {{{".toByteArray(Charsets.UTF_8))

        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createLocation(any(), any()) }
    }

    @Test
    fun `handleCreate falls back to project property chain when property not found by local id`() = runTest {
        val payload = createCreatePayload(propertyLocalId = 999L)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, propertyId = 200L)
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val locationDto = createLocationDto()

        val operation = createOperation(payload = payloadBytes(payload))

        coEvery { localDataService.getProperty(999L) } returns null
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { localDataService.getLocationByUuid("location-uuid") } returns null
        coEvery { localDataService.getLocations(100L) } returns emptyList()
        coEvery { api.createLocation(2000L, any()) } returns locationDto
        coEvery { localDataService.saveLocations(any()) } just runs

        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createLocation(2000L, any()) }
    }

    @Test
    fun `handleCreate returns SUCCESS when location already synced`() = runTest {
        val payload = createCreatePayload()
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val alreadySynced = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid"
        )

        val operation = createOperation(payload = payloadBytes(payload))

        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { localDataService.getLocationByUuid("location-uuid") } returns alreadySynced

        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.createLocation(any(), any()) }
    }

    @Test
    fun `handleCreate returns DROP on 422 validation error`() = runTest {
        val payload = createCreatePayload()
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)

        val operation = createOperation(payload = payloadBytes(payload))

        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { localDataService.getLocationByUuid("location-uuid") } returns null
        coEvery { localDataService.getLocations(100L) } returns emptyList()
        coEvery { api.createLocation(2000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleCreate returns SKIP when property has no serverId`() = runTest {
        val payload = createCreatePayload()
        val propertyNoServerId = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = null)

        val operation = createOperation(payload = payloadBytes(payload))

        coEvery { localDataService.getProperty(200L) } returns propertyNoServerId

        val result = handler.handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createLocation(any(), any()) }
    }

    // ===== handleUpdate Tests =====

    @Test
    fun `handleUpdate happy path updates location and saves entity`() = runTest {
        val payload = createUpdatePayload()
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid"
        )
        val responseDto = createLocationDto(updatedAt = "2026-01-30T13:00:00.000000Z")

        val operation = createOperation(
            operationType = SyncOperationType.UPDATE,
            payload = payloadBytes(payload)
        )

        coEvery { localDataService.getLocationByUuid("location-uuid") } returns location
        coEvery { api.updateLocation(3000L, any()) } returns responseDto
        coEvery { localDataService.saveLocations(any()) } just runs

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateLocation(3000L, any()) }
        coVerify {
            localDataService.saveLocations(match { list ->
                list.size == 1 &&
                    list[0].syncStatus == SyncStatus.SYNCED &&
                    !list[0].isDirty
            })
        }
    }

    @Test
    fun `handleUpdate returns DROP when location not found`() = runTest {
        val payload = createUpdatePayload()
        val operation = createOperation(
            operationType = SyncOperationType.UPDATE,
            payload = payloadBytes(payload)
        )

        coEvery { localDataService.getLocationByUuid("location-uuid") } returns null
        coEvery { localDataService.getLocation(300L) } returns null

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.updateLocation(any(), any()) }
    }

    @Test
    fun `handleUpdate returns SKIP when location has no serverId`() = runTest {
        val payload = createUpdatePayload()
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = null,
            uuid = "location-uuid"
        )

        val operation = createOperation(
            operationType = SyncOperationType.UPDATE,
            payload = payloadBytes(payload)
        )

        coEvery { localDataService.getLocationByUuid("location-uuid") } returns location

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.updateLocation(any(), any()) }
    }

    @Test
    fun `handleUpdate 409 conflict fetches fresh location and retries successfully`() = runTest {
        val payload = createUpdatePayload()
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, propertyId = 200L)
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val freshDto = createLocationDto(
            id = 3000L,
            updatedAt = "2026-01-30T14:00:00.000000Z"
        )
        val retryResponseDto = createLocationDto(updatedAt = "2026-01-30T15:00:00.000000Z")

        val operation = createOperation(
            operationType = SyncOperationType.UPDATE,
            payload = payloadBytes(payload)
        )

        coEvery { localDataService.getLocationByUuid("location-uuid") } returns location
        // First call throws 409
        coEvery { api.updateLocation(3000L, any()) } throws
            PushHandlerTestFixtures.create409WithUpdatedAt() andThen retryResponseDto

        // fetchFreshLocation chain
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.getPropertyLocations(2000L, any()) } returns PaginatedResponse(
            data = listOf(freshDto)
        )
        coEvery { localDataService.saveLocations(any()) } just runs

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // updateLocation called twice: first attempt + retry
        coVerify(exactly = 2) { api.updateLocation(3000L, any()) }
    }

    @Test
    fun `handleUpdate double 409 records conflict and returns CONFLICT_PENDING`() = runTest {
        val payload = createUpdatePayload()
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, propertyId = 200L)
        val property = PushHandlerTestFixtures.createProperty(propertyId = 200L, serverId = 2000L)
        val freshDto = createLocationDto(
            id = 3000L,
            name = "Server Floor Name",
            title = "Server Floor Title",
            isAccessible = true,
            updatedAt = "2026-01-30T14:00:00.000000Z"
        )

        val operation = createOperation(
            operationType = SyncOperationType.UPDATE,
            payload = payloadBytes(payload)
        )

        coEvery { localDataService.getLocationByUuid("location-uuid") } returns location
        // Both first call and retry throw 409
        coEvery { api.updateLocation(3000L, any()) } throws PushHandlerTestFixtures.create409WithUpdatedAt()

        // fetchFreshLocation chain
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { api.getPropertyLocations(2000L, any()) } returns PaginatedResponse(
            data = listOf(freshDto)
        )

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify { localDataService.upsertConflict(match<OfflineConflictResolutionEntity> { conflict ->
            conflict.entityType == "location" &&
                conflict.entityId == 300L &&
                conflict.conflictType == "UPDATE_CONFLICT"
        }) }
    }

    @Test
    fun `handleUpdate returns DROP on 422 validation error`() = runTest {
        val payload = createUpdatePayload()
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid"
        )

        val operation = createOperation(
            operationType = SyncOperationType.UPDATE,
            payload = payloadBytes(payload)
        )

        coEvery { localDataService.getLocationByUuid("location-uuid") } returns location
        coEvery { api.updateLocation(3000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== handleDelete Tests =====

    @Test
    fun `handleDelete happy path deletes on server and cascade deletes locally`() = runTest {
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid"
        )

        val operation = createOperation(
            entityId = 300L,
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getLocation(300L) } returns location
        coEvery { api.deleteLocation(3000L, any()) } just runs
        coEvery { localDataService.cascadeDeleteRoomsByLocation(300L) } returns emptyList()
        coEvery { localDataService.saveLocations(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteLocation(3000L, any()) }
        coVerify { localDataService.cascadeDeleteRoomsByLocation(300L) }
        coVerify {
            localDataService.saveLocations(match { list ->
                list.size == 1 && list[0].isDeleted && list[0].syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete without serverId cascade deletes locally and returns SUCCESS`() = runTest {
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = null,
            uuid = "location-uuid"
        )

        val operation = createOperation(
            entityId = 300L,
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getLocation(300L) } returns location
        coEvery { localDataService.cascadeDeleteRoomsByLocation(300L) } returns emptyList()
        coEvery { localDataService.saveLocations(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deleteLocation(any(), any()) }
        coVerify { localDataService.cascadeDeleteRoomsByLocation(300L) }
    }

    @Test
    fun `handleDelete returns SUCCESS on 404 from server`() = runTest {
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid"
        )

        val operation = createOperation(
            entityId = 300L,
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getLocation(300L) } returns location
        coEvery { api.deleteLocation(3000L, any()) } throws PushHandlerTestFixtures.create404Response()
        coEvery { localDataService.cascadeDeleteRoomsByLocation(300L) } returns emptyList()
        coEvery { localDataService.saveLocations(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { localDataService.cascadeDeleteRoomsByLocation(300L) }
    }

    @Test
    fun `handleDelete returns DROP on 422 validation error`() = runTest {
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "location-uuid"
        )

        val operation = createOperation(
            entityId = 300L,
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getLocation(300L) } returns location
        coEvery { api.deleteLocation(3000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }
}
