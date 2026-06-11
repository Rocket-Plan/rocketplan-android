package com.example.rocketplan_android.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rocketplan_android.data.local.OfflineDatabase
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GetOrphanedMoistureLogsTest {

    private lateinit var db: OfflineDatabase
    private lateinit var dao: OfflineDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, OfflineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.offlineDao()
    }

    @After
    fun tearDown() = db.close()

    private fun moistureLog(
        logId: Long,
        materialId: Long,
        syncStatus: SyncStatus = SyncStatus.PENDING,
        serverId: Long? = null
    ) = OfflineMoistureLogEntity(
        logId = logId,
        uuid = "log-uuid-$logId",
        projectId = 100L,
        roomId = 400L,
        materialId = materialId,
        date = Date(),
        moistureContent = 15.5,
        syncStatus = syncStatus,
        isDirty = true,
        serverId = serverId,
    )

    private fun syncOp(entityId: Long, status: SyncStatus = SyncStatus.PENDING) = OfflineSyncQueueEntity(
        operationId = "op-$entityId",
        entityType = "moisture_log",
        entityId = entityId,
        entityUuid = "log-uuid-$entityId",
        operationType = SyncOperationType.CREATE,
        payload = "{}".toByteArray(),
        priority = SyncPriority.MEDIUM,
        status = status
    )

    @Test
    fun `getOrphanedMoistureLogs returns PENDING logs with no sync op regardless of materialId`() = runTest {
        val orphanedLog = moistureLog(logId = 1L, materialId = -512922L, syncStatus = SyncStatus.PENDING)
        dao.upsertMoistureLogs(listOf(orphanedLog))

        val results = dao.getOrphanedMoistureLogs()

        assertThat(results).hasSize(1)
        assertThat(results[0].logId).isEqualTo(1L)
    }

    @Test
    fun `getOrphanedMoistureLogs excludes logs that already have a PENDING sync op`() = runTest {
        val orphanedLog = moistureLog(logId = 2L, materialId = -512922L, syncStatus = SyncStatus.PENDING)
        dao.upsertMoistureLogs(listOf(orphanedLog))
        dao.upsertSyncOperation(syncOp(entityId = 2L))

        val results = dao.getOrphanedMoistureLogs()

        assertThat(results).isEmpty()
    }

    @Test
    fun `getOrphanedMoistureLogs excludes SYNCED logs`() = runTest {
        val syncedLog = moistureLog(logId = 3L, materialId = -512922L, syncStatus = SyncStatus.SYNCED)
        dao.upsertMoistureLogs(listOf(syncedLog))

        val results = dao.getOrphanedMoistureLogs()

        assertThat(results).isEmpty()
    }

    @Test
    fun `getOrphanedMoistureLogs includes PENDING logs with positive materialId and no sync op`() = runTest {
        val positiveLog = moistureLog(logId = 4L, materialId = 800L, syncStatus = SyncStatus.PENDING)
        dao.upsertMoistureLogs(listOf(positiveLog))

        val results = dao.getOrphanedMoistureLogs()

        assertThat(results).hasSize(1)
        assertThat(results[0].materialId).isEqualTo(800L)
    }

    @Test
    fun `getOrphanedMoistureLogs returns empty when no orphaned logs`() = runTest {
        val results = dao.getOrphanedMoistureLogs()
        assertThat(results).isEmpty()
    }

    @Test
    fun `getOrphanedMoistureLogs excludes logs with FAILED sync op (only PENDING is orphaned)`() = runTest {
        val log = moistureLog(logId = 5L, materialId = -512922L, syncStatus = SyncStatus.PENDING)
        dao.upsertMoistureLogs(listOf(log))
        dao.upsertSyncOperation(syncOp(entityId = 5L, status = SyncStatus.FAILED))

        val results = dao.getOrphanedMoistureLogs()

        assertThat(results).isEmpty()
    }

    @Test
    fun `getOrphanedMoistureLogs includes multiple orphaned logs`() = runTest {
        dao.upsertMoistureLogs(listOf(
            moistureLog(logId = 10L, materialId = -100L, syncStatus = SyncStatus.PENDING),
            moistureLog(logId = 11L, materialId = -200L, syncStatus = SyncStatus.PENDING),
            moistureLog(logId = 12L, materialId = -300L, syncStatus = SyncStatus.PENDING),
        ))

        val results = dao.getOrphanedMoistureLogs()

        assertThat(results).hasSize(3)
    }
}