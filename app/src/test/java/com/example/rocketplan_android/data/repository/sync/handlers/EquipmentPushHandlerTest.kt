package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
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

    private val equipmentDto = mockk<EquipmentDto>(relaxed = true) {
        every { id } returns 6000L
        every { uuid } returns "equipment-uuid"
        every { roomId } returns 400L
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
    fun `handleUpsert creates equipment when serverId is null`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.createProjectEquipment(1000L, any()) } returns equipmentDto
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createProjectEquipment(1000L, any()) }
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
        coVerify(exactly = 0) { api.createProjectEquipment(any(), any()) }
    }

    @Test
    fun `handleUpsert returns DROP on 422 validation error during create`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = null)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.createProjectEquipment(1000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===== Upsert Update Tests =====

    @Test
    fun `handleUpsert updates equipment when serverId exists`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipment(6000L, any()) } returns equipmentDto
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateEquipment(6000L, any()) }
        coVerify { localDataService.saveEquipment(match { list ->
            list.size == 1 &&
                list[0].syncStatus == SyncStatus.SYNCED &&
                !list[0].isDirty
        }) }
    }

    @Test
    fun `handleUpsert re-creates equipment when update returns 404`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipment(6000L, any()) } throws PushHandlerTestFixtures.create404Response()
        coEvery { api.createProjectEquipment(1000L, any()) } returns equipmentDto
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateEquipment(6000L, any()) }
        coVerify { api.createProjectEquipment(1000L, any()) }
    }

    // NOTE: EquipmentPushHandler has a bug where pushPendingEquipmentUpsert's .onFailure block
    // consumes the error body (errorBody()?.string()) before handle409Conflict can call
    // extractUpdatedAt(). This means 409 conflicts always result in SKIP (will retry later)
    // rather than the intended retry-with-fresh-timestamp behavior. See EquipmentPushHandler:219.

    @Test
    fun `handleUpsert returns SKIP on 409 conflict due to consumed error body`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipment(6000L, any()) } answers {
            throw PushHandlerTestFixtures.create409WithUpdatedAt("2026-01-30T12:00:00.000000Z")
        }

        val result = handler.handleUpsert(operation)

        // Returns SKIP because pushPendingEquipmentUpsert's .onFailure consumes the error body
        // before handle409Conflict can extract updated_at from it
        assertThat(result).isEqualTo(OperationOutcome.SKIP)
    }

    @Test
    fun `handleUpsert returns DROP on 422 validation error during update`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val project = PushHandlerTestFixtures.createProject()
        val room = PushHandlerTestFixtures.createRoom()
        val operation = createOperation()

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateEquipment(6000L, any()) } throws PushHandlerTestFixtures.create422Response()

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
    fun `handleDelete deletes equipment from server successfully`() = runTest {
        val equipment = PushHandlerTestFixtures.createEquipment(serverId = 6000L)
        val operation = createOperation(operationType = SyncOperationType.DELETE)

        coEvery { localDataService.getEquipmentByUuid("equipment-uuid") } returns equipment
        coEvery { api.deleteEquipment(6000L, any()) } returns Response.success(Unit)
        coEvery { localDataService.saveEquipment(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteEquipment(6000L, any()) }
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
        coVerify(exactly = 0) { api.deleteEquipment(any(), any()) }
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
        coEvery { api.deleteEquipment(6000L, any()) } throws PushHandlerTestFixtures.create404Response()
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
        coEvery { api.deleteEquipment(6000L, any()) } throws PushHandlerTestFixtures.create410Response()
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
        coEvery { api.deleteEquipment(6000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }
}
