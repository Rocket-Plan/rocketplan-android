package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.model.SingleDataResponse
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
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
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class EquipmentPushHandlerTest {

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
    private val handler = EquipmentPushHandler(ctx)

    private val equipmentDto = mockk<com.example.rocketplan_android.data.model.offline.EquipmentDto>(relaxed = true) {
        every { id } returns 6000L
        every { uuid } returns "equipment-uuid"
        every { roomId } returns 400L
        every { updatedAt } returns "2026-01-30T12:00:00.000000Z"
        every { equipmentId } returns 7000L
    }

    private fun createOperation(
        entityUuid: String = "equipment-uuid",
        operationType: SyncOperationType = SyncOperationType.UPDATE
    ) = PushHandlerTestFixtures.createSyncOperation(
        entityType = "equipment",
        entityId = 600L,
        entityUuid = entityUuid,
        operationType = operationType
    )

    // ===== Upsert Create Tests =====

    @Test
    fun `handleUpsert attaches equipment when serverId is null with catalog found by name`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, catalogServerId = 7000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.attachRoomEquipment(4000L, any()) } returns SingleDataResponse(listOf(equipmentDto))
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.attachRoomEquipment(4000L, any()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                // R1: the attached row must keep the LOCAL room PK (400), not the server room id (4000).
                list[0].roomId == 400L &&
                list[0].syncStatus == SyncStatus.SYNCED &&
                !list[0].isDirty &&
                !list[0].isDeleted
        }) }
    }

    @Test
    fun `handleUpsert creates catalog then attaches when catalogServerId is null and name not found`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, catalogServerId = null)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.getProjectEquipment(1000L) } returns com.example.rocketplan_android.data.model.offline.PaginatedResponse(data = emptyList())
        coEvery { api.createProjectEquipment(1000L, any()) } returns SingleDataResponse(equipmentDto)
        coEvery { api.attachRoomEquipment(4000L, any()) } returns SingleDataResponse(listOf(equipmentDto))
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createProjectEquipment(1000L, any()) }
        coVerify { api.attachRoomEquipment(4000L, any()) }
    }

    @Test
    fun `handleUpsert returns SKIP when project has no serverId`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null)
        val project = PushHandlerTestFixtures.createProject(serverId = null)
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.attachRoomEquipment(any(), any()) }
    }

    @Test
    fun `handleUpsert returns SKIP when room exists but has no serverId`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, roomId = 400L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom(serverId = null)
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.attachRoomEquipment(any(), any()) }
    }

    @Test
    fun `handleUpsert returns DROP on 422 validation error during catalog create`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, catalogServerId = null)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.getProjectEquipment(1000L) } returns com.example.rocketplan_android.data.model.offline.PaginatedResponse(data = emptyList())
        coEvery { api.createProjectEquipment(1000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== Upsert Update Tests =====

    @Test
    fun `handleUpsert updates equipment pivot when serverId exists`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(6000L, any()) } returns retrofit2.Response.success(Unit)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateEquipmentRoom(6000L, any()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                list[0].syncStatus == SyncStatus.SYNCED &&
                !list[0].isDirty
        }) }
    }

    @Test
    fun `handleUpsert does NOT recreate when pivot update returns 404`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(6000L, any()) } throws PushHandlerTestFixtures.create404Response()
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.attachRoomEquipment(any(), any()) }
    }

    @Test
    fun `handleUpsert records conflict on double-409`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(6000L, any()) } answers {
            throw PushHandlerTestFixtures.create409WithUpdatedAt("2026-01-30T12:00:00.000000Z")
        }

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
    }

    @Test
    fun `handleUpsert returns DROP on 422 validation error during pivot update`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(6000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpsert returns DROP when equipment not found`() = runTest {
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns null

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpsert returns DROP when equipment is deleted`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(isDeleted = true)
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== Delete Tests =====

    @Test
    fun `handleDelete deletes equipment pivot from server successfully`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(6000L, any()) } returns Response.success(Unit)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteEquipmentRoom(6000L, any()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                list[0].isDeleted &&
                !list[0].isDirty &&
                list[0].syncStatus == SyncStatus.SYNCED
        }) }
    }

    @Test
    fun `handleDelete marks local-only equipment as deleted when serverId is null`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deleteEquipmentRoom(any(), any()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                list[0].isDeleted &&
                !list[0].isDirty &&
                list[0].syncStatus == SyncStatus.SYNCED
        }) }
    }

    @Test
    fun `handleDelete succeeds when server returns 404`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(6000L, any()) } returns PushHandlerTestFixtures.errorResponse(404)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].isDeleted
        }) }
    }

    @Test
    fun `handleDelete succeeds when server returns 410`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(6000L, any()) } returns PushHandlerTestFixtures.errorResponse(410)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].isDeleted
        }) }
    }

    @Test
    fun `handleDelete returns DROP on 422 validation error`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(6000L, any()) } returns PushHandlerTestFixtures.errorResponse(422)

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== RP-FR-004: unknown errors map to RETRY; cancellation still propagates =====

    @Test
    fun `handleUpsert returns RETRY on unknown error`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(6000L, any()) } throws RuntimeException("boom")

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.RETRY)
    }

    @Test
    fun `handleDelete returns RETRY on unknown error`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(6000L, any()) } throws RuntimeException("boom")

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.RETRY)
    }

    @Test
    fun `handleUpsert propagates CancellationException`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(6000L, any()) } throws kotlinx.coroutines.CancellationException("cancel")

        var caught: Throwable? = null
        try {
            handler.handleUpsert(operation)
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = e
        }

        assertThat(caught).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    // ===== Move Tests =====

    @Test
    fun `handleMove succeeds for full move with single returned pivot`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L)
        val operation = PushHandlerTestFixtures.createEquipmentMoveOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 4001L
        )

        val sourcePivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6000L
            every { uuid } returns "equipment-uuid"
            every { roomId } returns 4001L
            every { quantity } returns 1
        }
        val moveResponse = SingleDataResponse(listOf(sourcePivotDto))

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        // F3: server room id 4001 maps to local room PK 401; the moved row must store the LOCAL id.
        coEvery { localDataService.getRoomByServerId(4001L) } returns
            PushHandlerTestFixtures.createRoom(roomId = 401L, serverId = 4001L)
        coEvery { api.moveEquipmentRoom(6000L, any()) } returns moveResponse
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleMove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.moveEquipmentRoom(6000L, any()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                list[0].roomId == 401L &&
                list[0].syncStatus == SyncStatus.SYNCED &&
                !list[0].isDirty
        }) }
    }

    @Test
    fun `handleMove succeeds for partial move with source and dest pivots returned`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L, quantity = 5)
        val operation = PushHandlerTestFixtures.createEquipmentMoveOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 4001L,
            quantity = 2
        )

        val sourcePivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6000L
            every { uuid } returns "equipment-uuid"
            every { roomId } returns 400L
            every { quantity } returns 3
        }
        val destPivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6001L
            every { uuid } returns "dest-pivot-uuid"
            every { roomId } returns 4001L
            every { quantity } returns 2
        }
        val moveResponse = SingleDataResponse(listOf(sourcePivotDto, destPivotDto))

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        // F3: server room id 4001 maps to local room PK 401; the dest placement stores the LOCAL id.
        coEvery { localDataService.getRoomByServerId(4001L) } returns
            PushHandlerTestFixtures.createRoom(roomId = 401L, serverId = 4001L)
        coEvery { api.moveEquipmentRoom(6000L, any()) } returns moveResponse
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleMove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // Partial move saves source (updated qty, unchanged local room) and dest entity in the local dest room
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].roomId == 400L && list[0].quantity == 3
        }) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].roomId == 401L && list[0].quantity == 2
        }) }
    }

    @Test
    fun `handleMove returns SKIP when pivot has no serverId`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null)
        val operation = PushHandlerTestFixtures.createEquipmentMoveOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = null
        )

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment

        val result = handler.handleMove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.moveEquipmentRoom(any(), any()) }
    }

    @Test
    fun `handleMove returns SKIP when dest room has negative serverId`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L)
        val operation = PushHandlerTestFixtures.createEquipmentMoveOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomUuid = "dest-room-uuid"
        )
        val destRoom = PushHandlerTestFixtures.createRoom(serverId = -1L)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid("dest-room-uuid") } returns destRoom

        val result = handler.handleMove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleMove returns SKIP when pivot is missing on server`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L)
        val operation = PushHandlerTestFixtures.createEquipmentMoveOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 4001L
        )

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        coEvery { api.moveEquipmentRoom(6000L, any()) } throws PushHandlerTestFixtures.create404Response()

        val result = handler.handleMove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleMove records conflict on double-409`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L)
        val operation = PushHandlerTestFixtures.createEquipmentMoveOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 4001L,
            idempotencyKey = "move-idem-key"
        )

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        coEvery { api.moveEquipmentRoom(6000L, any()) } answers {
            throw PushHandlerTestFixtures.create409WithUpdatedAt("2026-01-30T12:00:00.000000Z")
        }
        coEvery { ctx.recordConflict(any()) } just runs

        val result = handler.handleMove(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify(exactly = 2) { api.moveEquipmentRoom(6000L, any()) }
    }

    @Test
    fun `handleMove preserves idempotency key on retry`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L)
        val operation = PushHandlerTestFixtures.createEquipmentMoveOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 4001L,
            idempotencyKey = "move-idem-key"
        )

        val sourcePivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6000L
            every { uuid } returns "equipment-uuid"
            every { roomId } returns 4001L
            every { quantity } returns 1
        }
        val moveResponse = SingleDataResponse(listOf(sourcePivotDto))

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        coEvery { api.moveEquipmentRoom(6000L, any()) } returns moveResponse
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleMove(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.moveEquipmentRoom(6000L, match { it.idempotencyKey == "move-idem-key" }) }
    }

    // ===== Transfer Tests =====

    @Test
    fun `handleTransfer succeeds for transfer with source and dest pivots returned`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L, quantity = 5)
        val operation = PushHandlerTestFixtures.createEquipmentTransferOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 5001L,
            quantity = 2
        )

        val sourcePivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6000L
            every { uuid } returns "equipment-uuid"
            every { roomId } returns 400L
            every { quantity } returns 3
        }
        val destPivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6001L
            every { uuid } returns "dest-pivot-uuid"
            every { roomId } returns 5001L
            every { quantity } returns 2
        }
        val transferResponse = SingleDataResponse(listOf(sourcePivotDto, destPivotDto))

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        // F3: server room id 5001 maps to local room PK 501; the dest placement stores the LOCAL id.
        coEvery { localDataService.getRoomByServerId(5001L) } returns
            PushHandlerTestFixtures.createRoom(roomId = 501L, serverId = 5001L)
        coEvery { api.transferEquipmentRoom(6000L, any()) } returns transferResponse
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleTransfer(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // Partial transfer saves source (updated qty, unchanged local room) and dest entity in the local dest room
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].roomId == 400L && list[0].quantity == 3
        }) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].roomId == 501L && list[0].quantity == 2
        }) }
    }

    @Test
    fun `handleTransfer full transfer tombstones source when server returns only dest pivot`() = runTest {
        // R2: a full transfer removes the source pivot server-side, so the response contains ONLY the
        // dest pivot (sourcePivot == null). The local source row must still be tombstoned, or it lingers
        // as a ghost in the source room.
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L, quantity = 2)
        val operation = PushHandlerTestFixtures.createEquipmentTransferOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 5001L,
            quantity = 2
        )

        val destPivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6001L
            every { uuid } returns "dest-pivot-uuid"
            every { roomId } returns 5001L
            every { quantity } returns 2
        }
        // Full transfer: server returns the dest pivot only — no source pivot.
        val transferResponse = SingleDataResponse(listOf(destPivotDto))

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        coEvery { localDataService.getRoomByServerId(5001L) } returns
            PushHandlerTestFixtures.createRoom(roomId = 501L, serverId = 5001L)
        coEvery { api.transferEquipmentRoom(6000L, any()) } returns transferResponse
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleTransfer(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // Source row tombstoned...
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].uuid == "equipment-uuid" && list[0].isDeleted
        }) }
        // ...and the dest placement is saved in the LOCAL dest room (501), not the server room id (5001).
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].uuid == "dest-pivot-uuid" && list[0].roomId == 501L && !list[0].isDeleted
        }) }
    }

    @Test
    fun `handleTransfer returns SKIP when pivot has no serverId`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null)
        val operation = PushHandlerTestFixtures.createEquipmentTransferOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = null
        )

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment

        val result = handler.handleTransfer(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.transferEquipmentRoom(any(), any()) }
    }

    @Test
    fun `handleTransfer returns SKIP when pivot is missing on server`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L)
        val operation = PushHandlerTestFixtures.createEquipmentTransferOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 5001L
        )

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        coEvery { api.transferEquipmentRoom(6000L, any()) } throws PushHandlerTestFixtures.create404Response()

        val result = handler.handleTransfer(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleTransfer records conflict on double-409`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L)
        val operation = PushHandlerTestFixtures.createEquipmentTransferOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 5001L,
            idempotencyKey = "transfer-idem-key"
        )

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        coEvery { api.transferEquipmentRoom(6000L, any()) } answers {
            throw PushHandlerTestFixtures.create409WithUpdatedAt("2026-01-30T12:00:00.000000Z")
        }
        coEvery { ctx.recordConflict(any()) } just runs

        val result = handler.handleTransfer(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify(exactly = 2) { api.transferEquipmentRoom(6000L, any()) }
    }

    @Test
    fun `handleTransfer preserves idempotency key on retry`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L, roomId = 400L, quantity = 5)
        val operation = PushHandlerTestFixtures.createEquipmentTransferOperation(
            entityUuid = "equipment-uuid",
            pivotServerId = 6000L,
            toRoomId = 5001L,
            quantity = 2,
            idempotencyKey = "transfer-idem-key"
        )

        val sourcePivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6000L
            every { uuid } returns "equipment-uuid"
            every { roomId } returns 400L
            every { quantity } returns 3
        }
        val destPivotDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6001L
            every { uuid } returns "dest-pivot-uuid"
            every { roomId } returns 5001L
            every { quantity } returns 2
        }
        val transferResponse = SingleDataResponse(listOf(sourcePivotDto, destPivotDto))

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getRoomByUuid(any()) } returns null
        coEvery { api.transferEquipmentRoom(6000L, any()) } returns transferResponse
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleTransfer(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.transferEquipmentRoom(6000L, match { it.idempotencyKey == "transfer-idem-key" }) }
    }
}
