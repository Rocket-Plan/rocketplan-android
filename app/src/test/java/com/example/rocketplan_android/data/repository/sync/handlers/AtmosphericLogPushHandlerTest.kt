package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.repository.ImageProcessorRepository
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
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
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class AtmosphericLogPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val queueManager: ImageProcessorQueueManager = mockk(relaxed = true)
    private val imageProcessorRepository: ImageProcessorRepository = mockk(relaxed = true)
    private val gson = Gson()

    private fun createContext() = PushHandlerContext(
        api = api,
        localDataService = localDataService,
        gson = gson,
        remoteLogger = remoteLogger,
        syncProjectEssentials = { mockk() },
        persistProperty = { _, _, _, _, _ -> mockk() },
        imageProcessorQueueManagerProvider = { queueManager },
        imageProcessorRepositoryProvider = { imageProcessorRepository }
    )

    private fun createAtmosphericLog(
        logId: Long = 1L,
        uuid: String = "test-log-uuid",
        serverId: Long? = null,
        projectId: Long = 100L,
        roomId: Long? = null,
        photoLocalPath: String? = null,
        photoAssemblyId: String? = null,
        photoUploadStatus: String = "none"
    ) = OfflineAtmosphericLogEntity(
        logId = logId,
        uuid = uuid,
        serverId = serverId,
        projectId = projectId,
        roomId = roomId,
        date = Date(),
        temperature = 72.0,
        relativeHumidity = 45.0,
        dewPoint = null,
        gpp = null,
        pressure = 29.92,
        windSpeed = 5.0,
        isExternal = true,
        isInlet = false,
        inletId = null,
        photoUrl = null,
        photoLocalPath = photoLocalPath,
        photoUploadStatus = photoUploadStatus,
        photoAssemblyId = photoAssemblyId,
        createdAt = Date(),
        updatedAt = Date(),
        syncStatus = SyncStatus.PENDING,
        isDirty = true,
        isDeleted = false,
        lastSyncedAt = null
    )

    private fun createProject(projectId: Long = 100L, serverId: Long = 1000L) = OfflineProjectEntity(
        projectId = projectId,
        serverId = serverId,
        companyId = 1L,
        uuid = "project-uuid",
        title = "Test Project",
        status = "active",
        createdAt = Date(),
        updatedAt = Date(),
        syncStatus = SyncStatus.SYNCED,
        isDirty = false
    )

    private fun createSyncOperation(
        entityId: Long = 1L,
        entityUuid: String = "test-log-uuid"
    ) = OfflineSyncQueueEntity(
        operationId = "op-${System.currentTimeMillis()}",
        entityType = "atmospheric_log",
        entityId = entityId,
        entityUuid = entityUuid,
        operationType = SyncOperationType.UPDATE,
        payload = ByteArray(0),
        priority = SyncPriority.MEDIUM,
        createdAt = Date()
    )

    private fun createAtmosphericLogDto(
        id: Long,
        uuid: String,
        projectId: Long = 100L
    ) = AtmosphericLogDto(
        id = id,
        uuid = uuid,
        projectId = projectId,
        roomId = null,
        date = "2025-01-30T12:00:00.000000Z",
        relativeHumidity = 45.0,
        temperature = 72.0,
        dewPoint = null,
        gpp = null,
        pressure = 29.92,
        windSpeed = 5.0,
        isExternal = true,
        isInlet = false,
        inletId = null,
        outletId = null,
        photoUrl = null,
        photoLocalPath = null,
        photoUploadStatus = null,
        photoAssemblyId = null,
        createdAt = "2025-01-30T12:00:00.000000Z",
        updatedAt = "2025-01-30T12:00:00.000000Z"
    )

    @Test
    fun `handleUpsert promotes waiting assembly when log gets serverId`() = runTest {
        val logUuid = "test-log-uuid"
        val assemblyId = "test-assembly-123"
        val newServerId = 999L

        val log = createAtmosphericLog(
            uuid = logUuid,
            serverId = null, // No serverId yet
            photoLocalPath = "/path/to/photo.jpg",
            photoAssemblyId = assemblyId,
            photoUploadStatus = "queued"
        )

        val project = createProject()
        val operation = createSyncOperation(entityUuid = logUuid)
        val responseDto = createAtmosphericLogDto(id = newServerId, uuid = logUuid)

        coEvery { localDataService.getAtmosphericLogByUuid(logUuid) } returns log
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createProjectAtmosphericLog(any(), any()) } returns responseDto
        coEvery { localDataService.saveAtmosphericLogs(any()) } just runs
        coEvery { queueManager.promoteWaitingAssembly(any(), any(), any()) } just runs

        val handler = AtmosphericLogPushHandler(createContext())
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)

        // Verify promoteWaitingAssembly was called with correct parameters
        coVerify {
            queueManager.promoteWaitingAssembly(
                entityType = "atmospheric_log",
                entityUuid = logUuid,
                entityId = newServerId
            )
        }

        // Verify the log was saved with updated photoUploadStatus
        coVerify {
            localDataService.saveAtmosphericLogs(match { logs ->
                logs.any { it.photoUploadStatus == "uploading" }
            })
        }
    }

    @Test
    fun `handleUpsert does not promote when log has no photoAssemblyId`() = runTest {
        val logUuid = "test-log-uuid"
        val newServerId = 999L

        val log = createAtmosphericLog(
            uuid = logUuid,
            serverId = null,
            photoLocalPath = null, // No photo
            photoAssemblyId = null, // No assembly
            photoUploadStatus = "none"
        )

        val project = createProject()
        val operation = createSyncOperation(entityUuid = logUuid)
        val responseDto = createAtmosphericLogDto(id = newServerId, uuid = logUuid)

        coEvery { localDataService.getAtmosphericLogByUuid(logUuid) } returns log
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createProjectAtmosphericLog(any(), any()) } returns responseDto
        coEvery { localDataService.saveAtmosphericLogs(any()) } just runs

        val handler = AtmosphericLogPushHandler(createContext())
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)

        // Should NOT call promoteWaitingAssembly since there's no assembly
        coVerify(exactly = 0) {
            queueManager.promoteWaitingAssembly(any(), any(), any())
        }
    }

    @Test
    fun `handleUpsert promotes assembly on update when serverId exists`() = runTest {
        val logUuid = "test-log-uuid"
        val assemblyId = "test-assembly-123"
        val existingServerId = 888L

        // Log already has a serverId (update case)
        val log = createAtmosphericLog(
            uuid = logUuid,
            serverId = existingServerId,
            photoLocalPath = "/path/to/photo.jpg",
            photoAssemblyId = assemblyId,
            photoUploadStatus = "queued"
        )

        val project = createProject()
        val operation = createSyncOperation(entityUuid = logUuid)
        val responseDto = createAtmosphericLogDto(id = existingServerId, uuid = logUuid)

        coEvery { localDataService.getAtmosphericLogByUuid(logUuid) } returns log
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateAtmosphericLog(existingServerId, any()) } returns responseDto
        coEvery { localDataService.saveAtmosphericLogs(any()) } just runs

        val handler = AtmosphericLogPushHandler(createContext())
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)

        // Should call promote since we have serverId and assemblyId
        coVerify {
            queueManager.promoteWaitingAssembly(
                entityType = "atmospheric_log",
                entityUuid = logUuid,
                entityId = existingServerId
            )
        }
    }
}
