package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MoistureLogPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)

    private lateinit var handler: MoistureLogPushHandler

    // Reusable fixtures
    private val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
    private val room = PushHandlerTestFixtures.createRoom(roomId = 400L, serverId = 4000L, projectId = 100L)
    private val material = PushHandlerTestFixtures.createMaterial(materialId = 800L, serverId = 8000L)

    private fun createMoistureLogDto(
        id: Long = 7000L,
        uuid: String = "moisture-log-uuid",
        projectId: Long = 100L,
        roomId: Long = 400L,
        materialId: Long = 8000L
    ) = MoistureLogDto(
        id = id,
        uuid = uuid,
        projectId = projectId,
        roomId = roomId,
        materialId = materialId,
        damageMaterial = null,
        date = "2026-01-30T12:00:00.000000Z",
        moistureContent = 15.5,
        reading = 15.5,
        removed = false,
        location = null,
        depth = null,
        photoUrl = null,
        photoLocalPath = null,
        photoUploadStatus = null,
        photo = null,
        createdAt = "2026-01-30T12:00:00.000000Z",
        updatedAt = "2026-01-30T12:00:00.000000Z"
    )

    @Before
    fun setUp() {
        val ctx = PushHandlerTestFixtures.createContext(
            api = api,
            localDataService = localDataService,
            remoteLogger = remoteLogger
        )
        handler = MoistureLogPushHandler(ctx)

        // Default stubs for dependency resolution
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { localDataService.getMaterial(800L) } returns material
        coEvery { localDataService.saveMoistureLogs(any()) } just runs
        coEvery { localDataService.upsertConflict(any()) } just runs
    }

    // ===================================================================
    // handleUpsert - Create (serverId == null)
    // ===================================================================

    @Test
    fun `handleUpsert creates new log when serverId is null`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )
        val dto = createMoistureLogDto(id = 7000L, uuid = log.uuid)

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.createMoistureLog(4000L, 8000L, any()) } returns dto

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createMoistureLog(4000L, 8000L, any()) }
        coVerify(exactly = 0) { api.updateMoistureLog(any(), any()) }
        coVerify {
            localDataService.saveMoistureLogs(match { logs ->
                logs.size == 1 &&
                    logs[0].syncStatus == SyncStatus.SYNCED &&
                    !logs[0].isDirty &&
                    !logs[0].isDeleted
            })
        }
    }

    @Test
    fun `handleUpsert returns DROP when log not found`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = "nonexistent-uuid"
        )

        coEvery { localDataService.getMoistureLogByUuid("nonexistent-uuid") } returns null

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createMoistureLog(any(), any(), any()) }
        coVerify(exactly = 0) { api.updateMoistureLog(any(), any()) }
    }

    @Test
    fun `handleUpsert returns DROP when log is deleted`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null, isDeleted = true)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createMoistureLog(any(), any(), any()) }
    }

    @Test
    fun `handleUpsert returns SKIP when project has no serverId`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { localDataService.getProject(100L) } returns project.copy(serverId = null)

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createMoistureLog(any(), any(), any()) }
    }

    @Test
    fun `handleUpsert returns SKIP when room has no serverId`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { localDataService.getRoom(400L) } returns room.copy(serverId = null)

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createMoistureLog(any(), any(), any()) }
    }

    @Test
    fun `handleUpsert returns SKIP when material has no serverId and ensureMaterialSynced fails`() = runTest {
        val materialNoServerId = material.copy(serverId = null)
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { localDataService.getMaterial(800L) } returns materialNoServerId
        coEvery { api.createProjectDamageMaterial(1000L, any()) } throws RuntimeException("API error")

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify { api.createProjectDamageMaterial(1000L, any()) }
        coVerify(exactly = 0) { api.createMoistureLog(any(), any(), any()) }
    }

    @Test
    fun `handleUpsert syncs material when material has no serverId and then creates log`() = runTest {
        val materialNoServerId = material.copy(serverId = null)
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        val createdMaterialDto = DamageMaterialDto(
            id = 9000L,
            uuid = materialNoServerId.uuid,
            projectId = 1000L,
            roomId = 4000L,
            damageTypeId = 1L,
            title = materialNoServerId.name,
            description = null,
            severity = null,
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T12:00:00.000000Z"
        )
        val dto = createMoistureLogDto(id = 7000L, uuid = log.uuid)

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { localDataService.getMaterial(800L) } returns materialNoServerId
        coEvery { api.createProjectDamageMaterial(1000L, any()) } returns com.example.rocketplan_android.data.model.SingleResourceResponse(createdMaterialDto)
        coEvery { localDataService.saveMaterials(any()) } just runs
        // After material sync, the handler uses the returned serverId (9000L)
        coEvery { api.createMoistureLog(4000L, 9000L, any()) } returns dto

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createProjectDamageMaterial(1000L, any()) }
        coVerify { localDataService.saveMaterials(any()) }
        coVerify { api.createMoistureLog(4000L, 9000L, any()) }
    }

    @Test
    fun `handleUpsert returns DROP on 422 validation error during create`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.createMoistureLog(4000L, 8000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===================================================================
    // handleUpsert - Update (serverId != null)
    // ===================================================================

    @Test
    fun `handleUpsert updates existing log when serverId is present`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )
        val dto = createMoistureLogDto(id = 7000L, uuid = log.uuid)

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.updateMoistureLog(7000L, any()) } returns dto

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateMoistureLog(7000L, any()) }
        coVerify(exactly = 0) { api.createMoistureLog(any(), any(), any()) }
        coVerify {
            localDataService.saveMoistureLogs(match { logs ->
                logs.size == 1 &&
                    logs[0].syncStatus == SyncStatus.SYNCED &&
                    !logs[0].isDirty
            })
        }
    }

    @Test
    fun `handleUpsert re-creates on 404 when update returns not found`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )
        val dto = createMoistureLogDto(id = 7500L, uuid = log.uuid)

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.updateMoistureLog(7000L, any()) } throws PushHandlerTestFixtures.create404Response()
        coEvery { api.createMoistureLog(4000L, 8000L, any()) } returns dto

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateMoistureLog(7000L, any()) }
        coVerify { api.createMoistureLog(4000L, 8000L, any()) }
    }

    @Test
    fun `handleUpsert returns DROP on 422 validation error during update`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.updateMoistureLog(7000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===================================================================
    // handleUpsert - 409 Conflict Handling
    // ===================================================================

    @Test
    fun `handleUpsert retries on 409 conflict and succeeds`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )
        val freshUpdatedAt = "2026-01-31T10:00:00.000000Z"
        val dto = createMoistureLogDto(id = 7000L, uuid = log.uuid)

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.updateMoistureLog(7000L, any()) } throws
            PushHandlerTestFixtures.create409WithUpdatedAt(freshUpdatedAt) andThen dto

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // First call triggers 409, second call (retry) succeeds
        coVerify(exactly = 2) { api.updateMoistureLog(7000L, any()) }
        coVerify { localDataService.saveMoistureLogs(any()) }
    }

    @Test
    fun `handleUpsert returns CONFLICT_PENDING on double 409`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )
        val freshUpdatedAt = "2026-01-31T10:00:00.000000Z"

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        // Both the initial call and the retry throw 409
        coEvery { api.updateMoistureLog(7000L, any()) } throws
            PushHandlerTestFixtures.create409WithUpdatedAt(freshUpdatedAt)

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify(exactly = 2) { api.updateMoistureLog(7000L, any()) }
        coVerify { localDataService.upsertConflict(any()) }
    }

    @Test
    fun `handleUpsert records conflict entity with correct fields on double 409`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityId = log.logId,
            entityUuid = log.uuid
        )
        val freshUpdatedAt = "2026-01-31T10:00:00.000000Z"

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.updateMoistureLog(7000L, any()) } throws
            PushHandlerTestFixtures.create409WithUpdatedAt(freshUpdatedAt)

        val conflictSlot = slot<OfflineConflictResolutionEntity>()
        coEvery { localDataService.upsertConflict(capture(conflictSlot)) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        val conflict = conflictSlot.captured
        assertThat(conflict.entityType).isEqualTo("moisture_log")
        assertThat(conflict.entityId).isEqualTo(log.logId)
        assertThat(conflict.entityUuid).isEqualTo(log.uuid)
        assertThat(conflict.conflictType).isEqualTo("UPDATE_CONFLICT")
        assertThat(conflict.originalOperationId).isEqualTo(operation.operationId)

        // Verify local version contains moisture content info
        val localVersionJson = String(conflict.localVersion, Charsets.UTF_8)
        assertThat(localVersionJson).contains("moistureContent")
        assertThat(localVersionJson).contains("materialId")

        // Verify remote version contains the fresh updatedAt
        val remoteVersionJson = String(conflict.remoteVersion, Charsets.UTF_8)
        assertThat(remoteVersionJson).contains(freshUpdatedAt)
    }

    @Test
    fun `handleUpsert returns SKIP on 409 when updatedAt cannot be extracted`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.updateMoistureLog(7000L, any()) } throws
            PushHandlerTestFixtures.create409WithoutUpdatedAt()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        // Only the initial call; no retry since we couldn't extract updatedAt
        coVerify(exactly = 1) { api.updateMoistureLog(7000L, any()) }
    }

    @Test
    fun `handleUpsert returns DROP on 422 during 409 retry`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )
        val freshUpdatedAt = "2026-01-31T10:00:00.000000Z"

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        // First call: 409, retry call: 422
        var callCount = 0
        coEvery { api.updateMoistureLog(7000L, any()) } answers {
            callCount++
            if (callCount == 1) {
                throw PushHandlerTestFixtures.create409WithUpdatedAt(freshUpdatedAt)
            } else {
                throw PushHandlerTestFixtures.create422Response()
            }
        }

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===================================================================
    // handleDelete - Happy path
    // ===================================================================

    @Test
    fun `handleDelete succeeds when server confirms deletion`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.deleteMoistureLog(7000L, any()) } returns Response.success(Unit)

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteMoistureLog(7000L, any()) }
        coVerify {
            localDataService.saveMoistureLogs(match { logs ->
                logs.size == 1 &&
                    logs[0].isDeleted &&
                    !logs[0].isDirty &&
                    logs[0].syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete returns DROP when log not found`() = runTest {
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = "nonexistent-uuid"
        )

        coEvery { localDataService.getMoistureLogByUuid("nonexistent-uuid") } returns null

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.deleteMoistureLog(any(), any()) }
    }

    @Test
    fun `handleDelete marks deleted locally and returns SUCCESS when no serverId`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // Should NOT call the API since there's no serverId
        coVerify(exactly = 0) { api.deleteMoistureLog(any(), any()) }
        coVerify {
            localDataService.saveMoistureLogs(match { logs ->
                logs.size == 1 &&
                    logs[0].isDeleted &&
                    !logs[0].isDirty &&
                    logs[0].syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete succeeds when server returns 404`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.deleteMoistureLog(7000L, any()) } throws PushHandlerTestFixtures.create404Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveMoistureLogs(match { logs ->
                logs.size == 1 && logs[0].isDeleted
            })
        }
    }

    @Test
    fun `handleDelete succeeds when server returns 410`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.deleteMoistureLog(7000L, any()) } throws PushHandlerTestFixtures.create410Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveMoistureLogs(match { logs ->
                logs.size == 1 && logs[0].isDeleted
            })
        }
    }

    @Test
    fun `handleDelete returns DROP on 422 validation error`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.deleteMoistureLog(7000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    // ===================================================================
    // ensureMaterialSynced - 409 fallback to existing material
    // ===================================================================

    @Test
    fun `handleUpsert resolves material via getProjectDamageMaterials on 409 during material create`() = runTest {
        val materialNoServerId = material.copy(serverId = null)
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        val existingMaterialDto = DamageMaterialDto(
            id = 9500L,
            uuid = "existing-mat-uuid",
            projectId = 1000L,
            roomId = 4000L,
            damageTypeId = 1L,
            title = materialNoServerId.name,
            description = null,
            severity = null,
            createdAt = "2026-01-30T12:00:00.000000Z",
            updatedAt = "2026-01-30T12:00:00.000000Z"
        )
        val paginatedResponse = PaginatedResponse(data = listOf(existingMaterialDto))
        val dto = createMoistureLogDto(id = 7000L, uuid = log.uuid)

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { localDataService.getMaterial(800L) } returns materialNoServerId
        coEvery { api.createProjectDamageMaterial(1000L, any()) } throws
            PushHandlerTestFixtures.create409WithUpdatedAt()
        coEvery { api.getProjectDamageMaterials(1000L) } returns paginatedResponse
        coEvery { localDataService.saveMaterials(any()) } just runs
        coEvery { api.createMoistureLog(4000L, 9500L, any()) } returns dto

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.getProjectDamageMaterials(1000L) }
        coVerify { api.createMoistureLog(4000L, 9500L, any()) }
    }

    // ===================================================================
    // Edge cases
    // ===================================================================

    @Test
    fun `handleUpsert propagates unexpected exceptions`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = null)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.createMoistureLog(4000L, 8000L, any()) } throws
            RuntimeException("Unexpected network error")

        var thrown: Exception? = null
        try {
            handler.handleUpsert(operation)
        } catch (e: Exception) {
            thrown = e
        }

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        assertThat(thrown!!.message).isEqualTo("Unexpected network error")
    }

    @Test
    fun `handleDelete propagates unexpected exceptions`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(serverId = 7000L)
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.deleteMoistureLog(7000L, any()) } throws
            RuntimeException("Unexpected network error")

        var thrown: Exception? = null
        try {
            handler.handleDelete(operation)
        } catch (e: Exception) {
            thrown = e
        }

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        assertThat(thrown!!.message).isEqualTo("Unexpected network error")
    }

    @Test
    fun `handleUpsert preserves local ids in saved entity after create`() = runTest {
        val log = PushHandlerTestFixtures.createMoistureLog(
            logId = 42L,
            serverId = null,
            uuid = "custom-uuid",
            projectId = 100L,
            roomId = 400L,
            materialId = 800L
        )
        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "moisture_log",
            entityUuid = log.uuid
        )
        val dto = createMoistureLogDto(id = 7000L, uuid = "custom-uuid")

        coEvery { localDataService.getMoistureLogByUuid(log.uuid) } returns log
        coEvery { api.createMoistureLog(4000L, 8000L, any()) } returns dto

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveMoistureLogs(match { logs ->
                val saved = logs[0]
                saved.logId == 42L &&
                    saved.uuid == "custom-uuid" &&
                    saved.projectId == 100L &&
                    saved.roomId == 400L &&
                    saved.materialId == 800L
            })
        }
    }
}
