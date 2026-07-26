package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.model.SingleDataResponse
import com.example.rocketplan_android.data.model.offline.AttachRoomEquipmentRequest
import com.example.rocketplan_android.data.model.offline.CreateEquipmentCatalogRequest
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.EquipmentRoomUpdateRequest
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

    private val pivotDto = mockk<EquipmentDto>(relaxed = true) {
        every { id } returns 7000L
        every { uuid } returns "equipment-uuid"
        every { roomId } returns 400L
        every { equipmentId } returns 6000L
        every { updatedAt } returns "2026-01-30T12:00:00.000000Z"
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
    fun `handleUpsert creates equipment via attach when catalogServerId is known`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.attachRoomEquipment(4000L, any<AttachRoomEquipmentRequest>()) } returns SingleDataResponse(listOf(pivotDto))
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.attachRoomEquipment(4000L, any<AttachRoomEquipmentRequest>()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                list[0].syncStatus == SyncStatus.SYNCED &&
                !list[0].isDirty &&
                !list[0].isDeleted
        }) }
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
        coVerify(exactly = 0) { api.createProjectEquipment(any(), any()) }
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
    fun `handleUpsert returns DROP on 422 validation error during attach`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.attachRoomEquipment(4000L, any<AttachRoomEquipmentRequest>()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== Upsert Update Tests =====

    @Test
    fun `handleUpsert updates equipment via PUT equipment-rooms when serverId exists`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) } returns Response.success(Unit)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                list[0].syncStatus == SyncStatus.SYNCED &&
                !list[0].isDirty
        }) }
    }

    @Test
    fun `handleUpsert returns DROP when update returns 404 (pivot gone, do NOT recreate)`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) } throws PushHandlerTestFixtures.create404Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.RETRY)
    }

    @Test
    fun `handleUpsert records conflict on double-409`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) } returns PushHandlerTestFixtures.create409RetrofitResponse("2026-01-30T12:00:00.000000Z")

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
    }

    @Test
    fun `handleUpsert returns DROP on mode rejection 409`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) } throws PushHandlerTestFixtures.create409ModeRejection()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpsert returns DROP on 422 validation error during update`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) } throws PushHandlerTestFixtures.create422Response()

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
    fun `handleDelete deletes equipment via DELETE equipment-rooms successfully`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(7000L, any()) } returns Response.success(Unit)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteEquipmentRoom(7000L, any()) }
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
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(7000L, any()) } returns PushHandlerTestFixtures.errorResponse(404)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].isDeleted
        }) }
    }

    @Test
    fun `handleDelete succeeds when server returns 410`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(7000L, any()) } returns PushHandlerTestFixtures.errorResponse(410)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 && list[0].isDeleted
        }) }
    }

    @Test
    fun `handleDelete returns DROP on 422 validation error`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(7000L, any()) } returns PushHandlerTestFixtures.errorResponse(422)

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== RP-FR-004: unknown errors map to RETRY =====

    @Test
    fun `handleUpsert returns RETRY on unknown error`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) } throws RuntimeException("boom")

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.RETRY)
    }

    @Test
    fun `handleDelete returns RETRY on unknown error`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipmentRoom(7000L, any()) } throws RuntimeException("boom")

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.RETRY)
    }

    @Test
    fun `handleUpsert propagates CancellationException`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 7000L, catalogServerId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipmentRoom(7000L, any<EquipmentRoomUpdateRequest>()) } throws kotlinx.coroutines.CancellationException("cancel")

        var caught: Throwable? = null
        try {
            handler.handleUpsert(operation)
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = e
        }

        assertThat(caught).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
    }

    // ===== Create flow: mint catalog then attach =====

    @Test
    fun `handleUpsert mints catalog then attaches when catalogServerId is unknown`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, catalogServerId = null, type = "Custom Tool")
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()
        val catalogDto = mockk<EquipmentDto>(relaxed = true) {
            every { id } returns 6000L
            every { type } returns "Custom Tool"
        }

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { localDataService.getProjectEquipmentByType(100L, "Custom Tool") } returns null
        coEvery { api.createProjectEquipment(1000L, any<CreateEquipmentCatalogRequest>()) } returns SingleDataResponse(catalogDto)
        coEvery { api.attachRoomEquipment(4000L, any<AttachRoomEquipmentRequest>()) } returns SingleDataResponse(listOf(pivotDto))
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createProjectEquipment(1000L, any<CreateEquipmentCatalogRequest>()) }
        coVerify { api.attachRoomEquipment(4000L, any<AttachRoomEquipmentRequest>()) }
    }

    @Test
    fun `handleUpsert falls back to getProjectEquipment on 422 and attaches with found catalog id`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null, catalogServerId = null, type = "Air Mover")
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()
        val existingCatalogItem = EquipmentDto(
            id = 0L,
            uuid = null,
            projectId = 1000L,
            roomId = null,
            type = "Air Mover",
            brand = null,
            model = null,
            serialNumber = null,
            quantity = null,
            status = null,
            startDate = null,
            endDate = null,
            createdAt = null,
            updatedAt = null,
            equipmentId = 6000L
        )

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { localDataService.getProjectEquipmentByType(100L, "Air Mover") } returns null
        coEvery { api.createProjectEquipment(1000L, any<CreateEquipmentCatalogRequest>()) } throws PushHandlerTestFixtures.create422Response()
        coEvery { api.getProjectEquipment(1000L) } returns PaginatedResponse(
            data = listOf(existingCatalogItem),
            links = null,
            meta = null
        )
        coEvery { api.attachRoomEquipment(4000L, any<AttachRoomEquipmentRequest>()) } returns SingleDataResponse(listOf(pivotDto))
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.getProjectEquipment(1000L) }
        coVerify { api.attachRoomEquipment(4000L, any<AttachRoomEquipmentRequest>()) }
    }
}
