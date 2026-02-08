package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import com.example.rocketplan_android.data.repository.mapper.PendingRoomCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomUpdatePayload
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

@OptIn(ExperimentalCoroutinesApi::class)
class RoomPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val ctx = PushHandlerTestFixtures.createContext(
        api = api,
        localDataService = localDataService
    )

    private fun handler(isNetworkAvailable: () -> Boolean = { true }) =
        RoomPushHandler(ctx, isNetworkAvailable)

    // ===== Helper: build a create-room sync operation from a PendingRoomCreationPayload =====

    private fun createPayloadOperation(
        payload: PendingRoomCreationPayload
    ) = PushHandlerTestFixtures.createSyncOperation(
        entityType = "room",
        entityId = payload.localRoomId,
        entityUuid = payload.roomUuid ?: "",
        operationType = SyncOperationType.CREATE,
        payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
    )

    private fun updatePayloadOperation(
        payload: PendingRoomUpdatePayload
    ) = PushHandlerTestFixtures.createSyncOperation(
        entityType = "room",
        entityId = payload.roomId,
        entityUuid = payload.roomUuid ?: "",
        operationType = SyncOperationType.UPDATE,
        payload = gson.toJson(payload).toByteArray(Charsets.UTF_8)
    )

    // ===== Default payload factories =====

    private fun defaultCreationPayload(
        localRoomId: Long = 400L,
        roomUuid: String? = "room-uuid",
        projectId: Long = 100L,
        roomName: String = "Living Room",
        roomTypeId: Long = 1L,
        roomTypeName: String? = "Standard",
        isSource: Boolean = false,
        isExterior: Boolean = false,
        levelServerId: Long? = 3000L,
        locationServerId: Long? = 3001L,
        levelUuid: String = "level-uuid",
        locationUuid: String = "location-uuid",
        idempotencyKey: String? = "idem-key-1"
    ) = PendingRoomCreationPayload(
        localRoomId = localRoomId,
        roomUuid = roomUuid,
        projectId = projectId,
        roomName = roomName,
        roomTypeId = roomTypeId,
        roomTypeName = roomTypeName,
        isSource = isSource,
        isExterior = isExterior,
        levelServerId = levelServerId,
        locationServerId = locationServerId,
        levelUuid = levelUuid,
        locationUuid = locationUuid,
        idempotencyKey = idempotencyKey
    )

    private fun defaultUpdatePayload(
        roomId: Long = 400L,
        roomUuid: String? = "room-uuid",
        projectId: Long = 100L,
        locationId: Long? = 3001L,
        isSource: Boolean = true,
        levelId: Long? = 3000L,
        roomTypeId: Long? = 1L,
        lockUpdatedAt: String? = null
    ) = PendingRoomUpdatePayload(
        roomId = roomId,
        roomUuid = roomUuid,
        projectId = projectId,
        locationId = locationId,
        isSource = isSource,
        levelId = levelId,
        roomTypeId = roomTypeId,
        lockUpdatedAt = lockUpdatedAt
    )

    // ===== Stub the common happy-path dependencies =====

    private fun stubCreateHappyPath() {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L,
            propertyId = 200L
        )
        val property = PushHandlerTestFixtures.createProperty(
            propertyId = 200L,
            serverId = 2000L
        )
        val level = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "level-uuid",
            type = "level"
        )
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 301L,
            serverId = 3001L,
            uuid = "location-uuid",
            type = "location"
        )
        val existingRoom = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = null,
            uuid = "room-uuid",
            projectId = 100L,
            locationId = 301L
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { localDataService.getLocationByUuid("level-uuid") } returns level
        coEvery { localDataService.getLocationByUuid("location-uuid") } returns location
        coEvery { api.getProjectDetail(1000L, any()) } returns mockk(relaxed = true)
        coEvery { api.getPropertyRoomTypes(2000L, any()) } returns PaginatedResponse(
            data = listOf(RoomTypeDto(id = 1L, name = "Standard", type = "internal", isStandard = true))
        )
        coEvery { localDataService.getRoom(400L) } returns existingRoom
        coEvery { localDataService.getRoomByServerId(any()) } returns null
        coEvery { localDataService.getRoomByUuid("room-uuid") } returns existingRoom

        val roomJson = gson.toJsonTree(
            mapOf(
                "data" to mapOf(
                    "id" to 4000L,
                    "uuid" to "room-uuid",
                    "name" to "Living Room",
                    "project_id" to 100L,
                    "location_id" to 3001L,
                    "room_type" to mapOf("id" to 1L, "name" to "Standard"),
                    "updated_at" to "2026-01-30T12:00:00.000000Z",
                    "created_at" to "2026-01-30T12:00:00.000000Z"
                )
            )
        )
        coEvery { api.createRoom(any(), any()) } returns roomJson
    }

    // ==========================================================================
    // CREATE tests
    // ==========================================================================

    @Test
    fun `handleCreate happy path returns SUCCESS and saves room`() = runTest {
        stubCreateHappyPath()

        val payload = defaultCreationPayload()
        val operation = createPayloadOperation(payload)

        val result = handler().handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createRoom(3001L, any()) }
        coVerify { localDataService.saveRooms(match { rooms ->
            rooms.size == 1 &&
                rooms[0].serverId == 4000L &&
                rooms[0].syncStatus == SyncStatus.SYNCED &&
                !rooms[0].isDirty
        }) }
    }

    @Test
    fun `handleCreate with invalid payload returns DROP`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 1L,
            operationType = SyncOperationType.CREATE,
            payload = "not-valid-json!!!".toByteArray(Charsets.UTF_8)
        )

        val result = handler().handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleCreate when project missing returns SKIP`() = runTest {
        coEvery { localDataService.getProject(100L) } returns null

        val payload = defaultCreationPayload()
        val operation = createPayloadOperation(payload)

        val result = handler().handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleCreate when project has no serverId returns SKIP`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = null,
            propertyId = 200L
        )
        coEvery { localDataService.getProject(100L) } returns project

        val payload = defaultCreationPayload()
        val operation = createPayloadOperation(payload)

        val result = handler().handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleCreate when no network returns SKIP`() = runTest {
        // Set up enough state so we get past the property/level/location checks
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L,
            propertyId = 200L
        )
        val property = PushHandlerTestFixtures.createProperty(
            propertyId = 200L,
            serverId = 2000L
        )
        val level = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            uuid = "level-uuid",
            type = "level"
        )
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 301L,
            serverId = 3001L,
            uuid = "location-uuid",
            type = "location"
        )

        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns property
        coEvery { localDataService.getLocationByUuid("level-uuid") } returns level
        coEvery { localDataService.getLocationByUuid("location-uuid") } returns location

        val payload = defaultCreationPayload()
        val operation = createPayloadOperation(payload)

        val result = handler(isNetworkAvailable = { false }).handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleCreate when property not found returns SKIP`() = runTest {
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            serverId = 1000L,
            propertyId = 200L
        )
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns null

        val payload = defaultCreationPayload()
        val operation = createPayloadOperation(payload)

        val result = handler().handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleCreate with 422 validation error returns DROP`() = runTest {
        stubCreateHappyPath()
        coEvery { api.createRoom(any(), any()) } throws PushHandlerTestFixtures.create422Response()

        val payload = defaultCreationPayload()
        val operation = createPayloadOperation(payload)

        val result = handler().handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleCreate with 404 on location marks deleted and returns DROP`() = runTest {
        stubCreateHappyPath()

        // The handler checks errorBody for "Location" text. We need a 404 with "Location" in body.
        val locationNotFound = retrofit2.HttpException(
            retrofit2.Response.error<Any>(
                404,
                """{"error":"Location not found"}""".toResponseBody("application/json".toMediaType())
            )
        )
        coEvery { api.createRoom(any(), any()) } throws locationNotFound

        val existingRoom = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = null,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoom(400L) } returns existingRoom

        val payload = defaultCreationPayload()
        val operation = createPayloadOperation(payload)

        val result = handler().handleCreate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        // Verify location was marked deleted
        coVerify { localDataService.markLocationsDeleted(listOf(3001L)) }
        // Verify room was marked deleted
        coVerify { localDataService.saveRooms(match { rooms ->
            rooms.any { it.isDeleted && it.roomId == 400L }
        }) }
    }

    // ==========================================================================
    // UPDATE tests
    // ==========================================================================

    @Test
    fun `handleUpdate happy path returns SUCCESS and saves room`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoomByUuid("room-uuid") } returns room

        val responseDto = RoomDto(
            id = 4000L,
            uuid = "room-uuid",
            projectId = 100L,
            locationId = 3001L,
            name = "Living Room",
            title = "Living Room",
            typeOccurrence = null,
            roomType = RoomTypeDto(id = 1L, name = "Standard", type = "internal", isStandard = true),
            level = null,
            squareFootage = null,
            isAccessible = true,
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T13:00:00.000000Z"
        )
        coEvery { api.updateRoom(4000L, any()) } returns responseDto

        val payload = defaultUpdatePayload()
        val operation = updatePayloadOperation(payload)

        val result = handler().handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateRoom(4000L, any()) }
        coVerify { localDataService.saveRooms(match { rooms ->
            rooms.size == 1 &&
                rooms[0].syncStatus == SyncStatus.SYNCED &&
                !rooms[0].isDirty
        }) }
    }

    @Test
    fun `handleUpdate when room not found returns DROP`() = runTest {
        coEvery { localDataService.getRoomByUuid("room-uuid") } returns null
        coEvery { localDataService.getRoom(400L) } returns null

        val payload = defaultUpdatePayload()
        val operation = updatePayloadOperation(payload)

        val result = handler().handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpdate when room has no serverId returns SKIP`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = null,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoomByUuid("room-uuid") } returns room

        val payload = defaultUpdatePayload()
        val operation = updatePayloadOperation(payload)

        val result = handler().handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleUpdate with 409 conflict then retry succeeds returns SUCCESS`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoomByUuid("room-uuid") } returns room

        val conflict409 = PushHandlerTestFixtures.create409WithUpdatedAt()

        val freshRoomDto = RoomDto(
            id = 4000L,
            uuid = "room-uuid",
            projectId = 100L,
            locationId = 3001L,
            name = "Living Room",
            title = "Living Room",
            typeOccurrence = null,
            roomType = RoomTypeDto(id = 1L, name = "Standard", type = "internal", isStandard = true),
            level = null,
            squareFootage = null,
            isAccessible = true,
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T14:00:00.000000Z"
        )
        val retryResponseDto = freshRoomDto.copy(updatedAt = "2026-01-30T15:00:00.000000Z")

        // First call throws 409, second call (retry) succeeds
        coEvery { api.updateRoom(4000L, any()) } throws conflict409 andThen retryResponseDto
        coEvery { api.getRoomDetail(4000L) } returns freshRoomDto

        val payload = defaultUpdatePayload()
        val operation = updatePayloadOperation(payload)

        val result = handler().handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.getRoomDetail(4000L) }
        // updateRoom called twice: initial + retry
        coVerify(exactly = 2) { api.updateRoom(4000L, any()) }
    }

    @Test
    fun `handleUpdate with double 409 conflict returns CONFLICT_PENDING`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoomByUuid("room-uuid") } returns room

        val conflict409 = PushHandlerTestFixtures.create409WithUpdatedAt()

        val freshRoomDto = RoomDto(
            id = 4000L,
            uuid = "room-uuid",
            projectId = 100L,
            locationId = 3001L,
            name = "Living Room",
            title = "Living Room",
            typeOccurrence = null,
            roomType = RoomTypeDto(id = 1L, name = "Standard", type = "internal", isStandard = true),
            level = null,
            squareFootage = null,
            isAccessible = true,
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T14:00:00.000000Z"
        )

        // Both calls throw 409
        coEvery { api.updateRoom(4000L, any()) } throws conflict409
        coEvery { api.getRoomDetail(4000L) } returns freshRoomDto

        val payload = defaultUpdatePayload()
        val operation = updatePayloadOperation(payload)

        val result = handler().handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        // Verify conflict was recorded
        coVerify { localDataService.upsertConflict(match<OfflineConflictResolutionEntity> { conflict ->
            conflict.entityType == "room" &&
                conflict.entityId == 400L &&
                conflict.conflictType == "UPDATE_CONFLICT"
        }) }
    }

    @Test
    fun `handleUpdate with 422 validation error returns DROP`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoomByUuid("room-uuid") } returns room
        coEvery { api.updateRoom(4000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val payload = defaultUpdatePayload()
        val operation = updatePayloadOperation(payload)

        val result = handler().handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpdate with invalid payload returns DROP`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 1L,
            operationType = SyncOperationType.UPDATE,
            payload = "garbage-payload{{{{".toByteArray(Charsets.UTF_8)
        )

        val result = handler().handleUpdate(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ==========================================================================
    // DELETE tests
    // ==========================================================================

    @Test
    fun `handleDelete happy path returns SUCCESS and marks room deleted`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.deleteRoom(4000L, any()) } returns Unit

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 400L,
            entityUuid = "room-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler().handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteRoom(4000L, any()) }
        coVerify { localDataService.saveRooms(match { rooms ->
            rooms.size == 1 &&
                rooms[0].isDeleted &&
                rooms[0].syncStatus == SyncStatus.SYNCED &&
                !rooms[0].isDirty
        }) }
    }

    @Test
    fun `handleDelete when room has no serverId returns SUCCESS without calling API`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = null,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoom(400L) } returns room

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 400L,
            entityUuid = "room-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler().handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // Should NOT call deleteRoom on API since there is no server ID
        coVerify(exactly = 0) { api.deleteRoom(any(), any()) }
    }

    @Test
    fun `handleDelete when room not found returns DROP`() = runTest {
        coEvery { localDataService.getRoom(400L) } returns null

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 400L,
            entityUuid = "room-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler().handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleDelete with 404 returns SUCCESS (already gone on server)`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.deleteRoom(4000L, any()) } throws PushHandlerTestFixtures.create404Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 400L,
            entityUuid = "room-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler().handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // Should still mark locally as deleted
        coVerify { localDataService.saveRooms(match { rooms ->
            rooms.any { it.isDeleted }
        }) }
    }

    @Test
    fun `handleDelete with 410 returns SUCCESS (already gone on server)`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.deleteRoom(4000L, any()) } throws PushHandlerTestFixtures.create410Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 400L,
            entityUuid = "room-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler().handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
    }

    @Test
    fun `handleDelete with 422 returns DROP`() = runTest {
        val room = PushHandlerTestFixtures.createRoom(
            roomId = 400L,
            serverId = 4000L,
            uuid = "room-uuid"
        )
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.deleteRoom(4000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "room",
            entityId = 400L,
            entityUuid = "room-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler().handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

}
