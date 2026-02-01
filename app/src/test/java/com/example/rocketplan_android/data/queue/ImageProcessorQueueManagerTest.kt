package com.example.rocketplan_android.data.queue

import android.content.Context
import com.example.rocketplan_android.data.api.ImageProcessorApi
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.dao.OfflineDao
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.data.storage.StoredUploadData
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
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
class ImageProcessorQueueManagerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val context: Context = mockk(relaxed = true)
    private val dao: ImageProcessorDao = mockk(relaxed = true)
    private val offlineDao: OfflineDao = mockk(relaxed = true)
    private val uploadStore: ImageProcessorUploadStore = mockk(relaxed = true)
    private val api: ImageProcessorApi = mockk(relaxed = true)
    private val configRepository: ImageProcessingConfigurationRepository = mockk(relaxed = true)
    private val secureStorage: SecureStorage = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)

    private fun createQueueManager() = ImageProcessorQueueManager(
        context = context,
        dao = dao,
        offlineDao = offlineDao,
        uploadStore = uploadStore,
        api = api,
        configRepository = configRepository,
        secureStorage = secureStorage,
        remoteLogger = remoteLogger,
        realtimeManager = null
    )

    private fun createWaitingAssembly(
        assemblyId: String = "test-assembly-123",
        entityType: String = "AtmosphericLog",
        entityUuid: String = "test-log-uuid",
        entityId: Long? = null
    ) = ImageProcessorAssemblyEntity(
        id = 1L,
        assemblyId = assemblyId,
        projectId = 100L,
        roomId = null,
        groupUuid = "test-group-uuid",
        status = AssemblyStatus.WAITING_FOR_ENTITY.value,
        totalFiles = 1,
        bytesReceived = 1024,
        createdAt = System.currentTimeMillis(),
        lastUpdatedAt = System.currentTimeMillis(),
        entityType = entityType,
        entityId = entityId,
        entityUuid = entityUuid
    )

    private fun createStoredUploadData(
        entityType: String = "AtmosphericLog",
        entityUuid: String = "test-log-uuid",
        entityId: Long? = null
    ) = StoredUploadData(
        processingUrl = "https://example.com/upload",
        apiKey = "test-api-key",
        templateId = "atmospheric_log",
        projectId = 100L,
        roomId = null,
        groupUuid = "test-group-uuid",
        userId = 1L,
        albums = emptyMap(),
        order = emptyList(),
        notes = emptyMap(),
        entityType = entityType,
        entityId = entityId,
        entityUuid = entityUuid
    )

    @Test
    fun `promoteWaitingAssembly updates assembly and upload data with entityId`() = runTest {
        val assemblyId = "test-assembly-123"
        val entityUuid = "test-log-uuid"
        val entityId = 999L

        val waitingAssembly = createWaitingAssembly(
            assemblyId = assemblyId,
            entityUuid = entityUuid
        )
        val storedData = createStoredUploadData(entityUuid = entityUuid)

        coEvery { dao.getAssembliesByEntityUuid(entityUuid) } returns listOf(waitingAssembly)
        coEvery { uploadStore.read(assemblyId) } returns storedData
        coEvery { dao.getAssembly(assemblyId) } returns waitingAssembly
        coEvery { dao.updateAssembly(any()) } just runs
        every { uploadStore.write(any(), any()) } just runs

        val queueManager = createQueueManager()
        queueManager.promoteWaitingAssembly(
            entityType = "AtmosphericLog",
            entityUuid = entityUuid,
            entityId = entityId
        )

        // Verify assembly was updated (at least twice: once for entityId, once for status)
        coVerify(atLeast = 2) { dao.updateAssembly(any()) }

        // Verify assembly was updated with correct entityId
        coVerify {
            dao.updateAssembly(match { it.entityId == entityId })
        }

        // Verify upload data was updated with entityId
        coVerify {
            uploadStore.write(assemblyId, match { it.entityId == entityId })
        }
    }

    @Test
    fun `promoteWaitingAssembly ignores assemblies with different entityType`() = runTest {
        val entityUuid = "test-log-uuid"
        val entityId = 999L

        // Assembly has different entityType
        val waitingAssembly = createWaitingAssembly(
            entityUuid = entityUuid,
            entityType = "different_type"
        )

        coEvery { dao.getAssembliesByEntityUuid(entityUuid) } returns listOf(waitingAssembly)

        val queueManager = createQueueManager()
        queueManager.promoteWaitingAssembly(
            entityType = "AtmosphericLog",
            entityUuid = entityUuid,
            entityId = entityId
        )

        // Should not update since entityType doesn't match
        coVerify(exactly = 0) { dao.updateAssembly(any()) }
    }

    @Test
    fun `promoteWaitingAssembly ignores assemblies not in WAITING_FOR_ENTITY status`() = runTest {
        val entityUuid = "test-log-uuid"
        val entityId = 999L

        // Assembly is already QUEUED, not WAITING_FOR_ENTITY
        val queuedAssembly = createWaitingAssembly(entityUuid = entityUuid).copy(
            status = AssemblyStatus.QUEUED.value
        )

        coEvery { dao.getAssembliesByEntityUuid(entityUuid) } returns listOf(queuedAssembly)

        val queueManager = createQueueManager()
        queueManager.promoteWaitingAssembly(
            entityType = "AtmosphericLog",
            entityUuid = entityUuid,
            entityId = entityId
        )

        // Should not update since status is not WAITING_FOR_ENTITY
        coVerify(exactly = 0) { dao.updateAssembly(any()) }
    }

    @Test
    fun `promoteWaitingAssembly handles no matching assemblies gracefully`() = runTest {
        val entityUuid = "nonexistent-uuid"
        val entityId = 999L

        coEvery { dao.getAssembliesByEntityUuid(entityUuid) } returns emptyList()

        val queueManager = createQueueManager()

        // Should not throw, just log and return
        queueManager.promoteWaitingAssembly(
            entityType = "AtmosphericLog",
            entityUuid = entityUuid,
            entityId = entityId
        )

        coVerify(exactly = 0) { dao.updateAssembly(any()) }
    }

    @Test
    fun `registerWaitingAssemblies promotes WAITING_FOR_ENTITY when entity has serverId`() = runTest {
        val assemblyId = "test-assembly-123"
        val entityUuid = "test-log-uuid"
        val entityServerId = 999L

        val waitingAssembly = createWaitingAssembly(
            assemblyId = assemblyId,
            entityUuid = entityUuid
        )
        val storedData = createStoredUploadData(entityUuid = entityUuid)

        val atmosphericLog = mockk<OfflineAtmosphericLogEntity> {
            every { serverId } returns entityServerId
        }

        coEvery {
            dao.getAssembliesByStatus(listOf(
                AssemblyStatus.WAITING_FOR_ROOM.value,
                AssemblyStatus.WAITING_FOR_ENTITY.value
            ))
        } returns listOf(waitingAssembly)

        coEvery { offlineDao.getAtmosphericLogByUuid(entityUuid) } returns atmosphericLog
        coEvery { uploadStore.read(assemblyId) } returns storedData
        coEvery { dao.getAssembly(assemblyId) } returns waitingAssembly
        coEvery { dao.updateAssembly(any()) } just runs
        every { uploadStore.write(any(), any()) } just runs
        coEvery { dao.getAssembliesByStatus(listOf(AssemblyStatus.UPLOADING.value)) } returns emptyList()

        val queueManager = createQueueManager()

        // Trigger recovery which calls registerWaitingAssemblies
        queueManager.recoverStrandedAssemblies()

        // Verify the assembly was updated with serverId
        coVerify { dao.updateAssembly(match { it.entityId == entityServerId }) }
    }

    @Test
    fun `registerWaitingAssemblies skips WAITING_FOR_ENTITY when entity has no serverId`() = runTest {
        val assemblyId = "test-assembly-123"
        val entityUuid = "test-log-uuid"

        val waitingAssembly = createWaitingAssembly(
            assemblyId = assemblyId,
            entityUuid = entityUuid
        )

        val atmosphericLog = mockk<OfflineAtmosphericLogEntity> {
            every { serverId } returns null // No serverId yet
        }

        coEvery {
            dao.getAssembliesByStatus(listOf(
                AssemblyStatus.WAITING_FOR_ROOM.value,
                AssemblyStatus.WAITING_FOR_ENTITY.value
            ))
        } returns listOf(waitingAssembly)

        coEvery { offlineDao.getAtmosphericLogByUuid(entityUuid) } returns atmosphericLog
        coEvery { dao.getAssembliesByStatus(listOf(AssemblyStatus.UPLOADING.value)) } returns emptyList()

        val queueManager = createQueueManager()
        queueManager.recoverStrandedAssemblies()

        // Should not update entityId since entity has no serverId
        coVerify(exactly = 0) {
            dao.updateAssembly(match { it.entityId != null })
        }
    }
}
