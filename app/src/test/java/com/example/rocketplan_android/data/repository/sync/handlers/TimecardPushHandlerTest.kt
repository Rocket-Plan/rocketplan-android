package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.model.offline.TimecardDto
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimecardPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val gson = Gson()

    private val ctx = PushHandlerTestFixtures.createContext(
        api = api,
        localDataService = localDataService,
        remoteLogger = remoteLogger
    )
    private val handler = TimecardPushHandler(ctx)

    // ===== Helpers =====

    private fun createTimecardDto(
        id: Long = 10000L,
        uuid: String = "timecard-uuid",
        userId: Long = 1L,
        projectId: Long = 100L,
        companyId: Long = 1L
    ) = TimecardDto(
        id = id,
        uuid = uuid,
        userId = userId,
        projectId = projectId,
        companyId = companyId,
        timecardTypeId = 1,
        timeIn = "2026-01-30T08:00:00.000000Z",
        timeOut = "2026-01-30T17:00:00.000000Z",
        elapsed = 32400L,
        notes = null,
        createdAt = "2026-01-30T08:00:00.000000Z",
        updatedAt = "2026-01-30T12:00:00.000000Z"
    )

    private fun createOperation(
        entityType: String = "timecard",
        entityId: Long = 1000L,
        entityUuid: String = "timecard-uuid",
        operationType: SyncOperationType = SyncOperationType.UPDATE,
        payload: ByteArray = ByteArray(0)
    ) = PushHandlerTestFixtures.createSyncOperation(
        entityType = entityType,
        entityId = entityId,
        entityUuid = entityUuid,
        operationType = operationType,
        payload = payload
    )

    // ===== handleUpsert - Create Tests (serverId == null) =====

    @Test
    fun `handleUpsert create happy path creates timecard on server and saves locally`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = null,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")
        val responseDto = createTimecardDto()

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createTimecard(1000L, any()) } returns responseDto
        coEvery { localDataService.saveTimecard(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createTimecard(1000L, any()) }
        coVerify {
            localDataService.saveTimecard(match { saved ->
                saved.serverId == 10000L &&
                    saved.uuid == "timecard-uuid" &&
                    saved.syncStatus == SyncStatus.SYNCED &&
                    !saved.isDirty &&
                    !saved.isDeleted
            })
        }
    }

    @Test
    fun `handleUpsert create returns SKIP when project has no serverId`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = null,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = null)
        val operation = createOperation(entityUuid = "timecard-uuid")

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createTimecard(any(), any()) }
    }

    @Test
    fun `handleUpsert returns DROP when timecard not found locally`() = runTest {
        val operation = createOperation(entityUuid = "missing-uuid")

        coEvery { localDataService.getTimecardByUuid("missing-uuid") } returns null

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createTimecard(any(), any()) }
        coVerify(exactly = 0) { api.updateTimecard(any(), any()) }
    }

    @Test
    fun `handleUpsert returns DROP when timecard is deleted`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            uuid = "timecard-uuid",
            isDeleted = true
        )
        val operation = createOperation(entityUuid = "timecard-uuid")

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createTimecard(any(), any()) }
        coVerify(exactly = 0) { api.updateTimecard(any(), any()) }
    }

    @Test
    fun `handleUpsert create returns DROP on 422 validation error`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = null,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createTimecard(1000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { localDataService.saveTimecard(any()) }
    }

    // ===== handleUpsert - Update Tests (serverId != null) =====

    @Test
    fun `handleUpsert update happy path updates timecard on server and saves locally`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")
        val responseDto = createTimecardDto()

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateTimecard(10000L, any()) } returns responseDto
        coEvery { localDataService.saveTimecard(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateTimecard(10000L, any()) }
        coVerify {
            localDataService.saveTimecard(match { saved ->
                saved.syncStatus == SyncStatus.SYNCED &&
                    !saved.isDirty &&
                    !saved.isDeleted
            })
        }
    }

    @Test
    fun `handleUpsert update 404 re-creates timecard on server`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")
        val responseDto = createTimecardDto(id = 20000L)

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateTimecard(10000L, any()) } throws PushHandlerTestFixtures.create404Response()
        coEvery { api.createTimecard(1000L, any()) } returns responseDto
        coEvery { localDataService.saveTimecard(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateTimecard(10000L, any()) }
        coVerify { api.createTimecard(1000L, any()) }
        coVerify {
            localDataService.saveTimecard(match { saved ->
                saved.serverId == 20000L &&
                    saved.syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleUpsert update 409 extracts updatedAt and retries successfully`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")
        val responseDto = createTimecardDto()

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        // First call throws 409, second call succeeds
        coEvery { api.updateTimecard(10000L, any()) } throws
            PushHandlerTestFixtures.create409WithUpdatedAt("2026-01-30T14:00:00.000000Z") andThen responseDto
        coEvery { localDataService.saveTimecard(any()) } just runs

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        // updateTimecard called twice: original + retry
        coVerify(exactly = 2) { api.updateTimecard(10000L, any()) }
        coVerify {
            localDataService.saveTimecard(match { saved ->
                saved.syncStatus == SyncStatus.SYNCED && !saved.isDirty
            })
        }
    }

    @Test
    fun `handleUpsert update 409 without updatedAt in body returns SKIP`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateTimecard(10000L, any()) } throws
            PushHandlerTestFixtures.create409WithoutUpdatedAt()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        // Only one attempt - no retry since we couldn't extract updatedAt
        coVerify(exactly = 1) { api.updateTimecard(10000L, any()) }
        coVerify(exactly = 0) { localDataService.saveTimecard(any()) }
    }

    @Test
    fun `handleUpsert update double 409 records conflict and returns SKIP`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        // Both first call and retry throw 409
        coEvery { api.updateTimecard(10000L, any()) } throws
            PushHandlerTestFixtures.create409WithUpdatedAt("2026-01-30T14:00:00.000000Z")
        coEvery { localDataService.upsertConflict(any()) } just runs

        val result = handler.handleUpsert(operation)

        // TimecardPushHandler returns SKIP (not CONFLICT_PENDING) for double-409
        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        // Verify conflict was recorded via localDataService.upsertConflict directly
        coVerify {
            localDataService.upsertConflict(match<OfflineConflictResolutionEntity> { conflict ->
                conflict.entityType == "timecard" &&
                    conflict.entityId == 1000L &&
                    conflict.entityUuid == "timecard-uuid" &&
                    conflict.conflictType == "UPDATE_CONFLICT"
            })
        }
        // Should not save the timecard since we returned null (SKIP)
        coVerify(exactly = 0) { localDataService.saveTimecard(any()) }
    }

    @Test
    fun `handleUpsert update returns DROP on 422 validation error`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid",
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val operation = createOperation(entityUuid = "timecard-uuid")

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateTimecard(10000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { localDataService.saveTimecard(any()) }
    }

    // ===== handleDelete Tests =====

    @Test
    fun `handleDelete happy path deletes on server and marks locally deleted`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid"
        )
        val operation = createOperation(
            entityUuid = "timecard-uuid",
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { api.deleteTimecard(10000L, any()) } returns mockk()
        coEvery { localDataService.saveTimecard(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteTimecard(10000L, any()) }
        coVerify {
            localDataService.saveTimecard(match { saved ->
                saved.isDeleted &&
                    !saved.isDirty &&
                    saved.syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete without serverId marks locally deleted without calling server`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = null,
            uuid = "timecard-uuid"
        )
        val operation = createOperation(
            entityUuid = "timecard-uuid",
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { localDataService.saveTimecard(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deleteTimecard(any(), any()) }
        coVerify {
            localDataService.saveTimecard(match { saved ->
                saved.isDeleted &&
                    !saved.isDirty &&
                    saved.syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete returns SUCCESS on 404 from server`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid"
        )
        val operation = createOperation(
            entityUuid = "timecard-uuid",
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { api.deleteTimecard(10000L, any()) } throws PushHandlerTestFixtures.create404Response()
        coEvery { localDataService.saveTimecard(any()) } just runs

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveTimecard(match { saved ->
                saved.isDeleted &&
                    saved.syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete returns DROP on 422 validation error`() = runTest {
        val timecard = PushHandlerTestFixtures.createTimecard(
            serverId = 10000L,
            uuid = "timecard-uuid"
        )
        val operation = createOperation(
            entityUuid = "timecard-uuid",
            operationType = SyncOperationType.DELETE
        )

        coEvery { localDataService.getTimecardByUuid("timecard-uuid") } returns timecard
        coEvery { api.deleteTimecard(10000L, any()) } throws PushHandlerTestFixtures.create422Response()

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }
}
