package com.example.rocketplan_android.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rocketplan_android.data.local.OfflineDatabase
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * RP-BUG-048: getMaterialByNameInRoom drives RocketDry's reuse-instead-of-duplicate behavior. A material
 * is "in the room" if it has a non-deleted moisture log there. The lookup must be room-scoped,
 * case-insensitive, and prefer a synced (serverId>0) canonical row — so readings stop spawning duplicate
 * local materials that collapse to one server id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MaterialByNameInRoomDaoTest {

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

    private fun material(materialId: Long, serverId: Long?, name: String) = OfflineMaterialEntity(
        materialId = materialId,
        serverId = serverId,
        uuid = "mat-$materialId",
        projectId = 100L,
        name = name,
        syncStatus = if (serverId != null) SyncStatus.SYNCED else SyncStatus.PENDING,
    )

    private fun log(logId: Long, roomId: Long, materialId: Long, isDeleted: Boolean = false) =
        OfflineMoistureLogEntity(
            logId = logId,
            uuid = "log-$logId",
            projectId = 100L,
            roomId = roomId,
            materialId = materialId,
            date = Date(0),
            moistureContent = 8.0,
            isDeleted = isDeleted,
        )

    @Test
    fun `returns the canonical synced material when multiple share the name in the room`() = runTest {
        // Two "Concrete" rows in room 400: one synced (serverId>0), one local-only.
        dao.upsertMaterials(listOf(material(-10L, serverId = null, name = "Concrete"), material(50L, serverId = 512922L, name = "Concrete")))
        dao.upsertMoistureLogs(listOf(log(-1L, roomId = 400L, materialId = -10L), log(-2L, roomId = 400L, materialId = 50L)))

        val result = dao.getMaterialByNameInRoom(400L, "Concrete")

        assertThat(result).isNotNull()
        assertThat(result!!.materialId).isEqualTo(50L) // the serverId>0 canonical row, not the local dup
    }

    @Test
    fun `is case-insensitive`() = runTest {
        dao.upsertMaterials(listOf(material(50L, serverId = 1L, name = "Concrete")))
        dao.upsertMoistureLogs(listOf(log(-1L, roomId = 400L, materialId = 50L)))

        assertThat(dao.getMaterialByNameInRoom(400L, "concrete")?.materialId).isEqualTo(50L)
    }

    @Test
    fun `is room-scoped — a material in another room is not returned`() = runTest {
        dao.upsertMaterials(listOf(material(50L, serverId = 1L, name = "Concrete")))
        dao.upsertMoistureLogs(listOf(log(-1L, roomId = 999L, materialId = 50L))) // different room

        assertThat(dao.getMaterialByNameInRoom(400L, "Concrete")).isNull()
    }

    @Test
    fun `returns null when no material of that name is in the room`() = runTest {
        dao.upsertMaterials(listOf(material(50L, serverId = 1L, name = "Drywall")))
        dao.upsertMoistureLogs(listOf(log(-1L, roomId = 400L, materialId = 50L)))

        assertThat(dao.getMaterialByNameInRoom(400L, "Concrete")).isNull()
    }

    @Test
    fun `excludes materials whose only log in the room is deleted`() = runTest {
        dao.upsertMaterials(listOf(material(50L, serverId = 1L, name = "Concrete")))
        dao.upsertMoistureLogs(listOf(log(-1L, roomId = 400L, materialId = 50L, isDeleted = true)))

        assertThat(dao.getMaterialByNameInRoom(400L, "Concrete")).isNull()
    }

    // RP-BUG-048 collapse: dedupe local materials that share one serverId.

    @Test
    fun `getDuplicateServerIdMaterials returns only rows sharing a serverId`() = runTest {
        dao.upsertMaterials(listOf(
            material(-3L, serverId = 512922L, name = "Concrete"),
            material(-2L, serverId = 512922L, name = "Concrete"),
            material(-1L, serverId = 512922L, name = "Concrete"),
            material(50L, serverId = 999L, name = "Drywall"), // unique serverId — must NOT be returned
        ))

        val dupes = dao.getDuplicateServerIdMaterials()

        assertThat(dupes.map { it.materialId }).containsExactly(-3L, -2L, -1L)
        assertThat(dupes.map { it.materialId }).doesNotContain(50L)
    }

    @Test
    fun `collapse re-points all readings to one keeper and removes the duplicate material rows`() = runTest {
        // 3 phantom "Concrete" rows for one server material, each with a reading; plus an unrelated material.
        dao.upsertMaterials(listOf(
            material(-3L, serverId = 512922L, name = "Concrete"),
            material(-2L, serverId = 512922L, name = "Concrete"),
            material(-1L, serverId = 512922L, name = "Concrete"),
            material(50L, serverId = 999L, name = "Drywall"),
        ))
        dao.upsertMoistureLogs(listOf(
            log(-10L, roomId = 6804L, materialId = -3L),
            log(-11L, roomId = 6804L, materialId = -2L),
            log(-12L, roomId = 6804L, materialId = -1L),
            log(-20L, roomId = 6804L, materialId = 50L), // unrelated
        ))

        // Replicate the collapse: keeper = lowest materialId per serverId; re-point extras; delete them.
        val keeper = dao.getDuplicateServerIdMaterials()
            .groupBy { it.serverId }.values.first().minByOrNull { it.materialId }!!
        val extras = dao.getDuplicateServerIdMaterials().filter { it.materialId != keeper.materialId }
        extras.forEach { dao.migrateMoistureLogMaterialIds(it.materialId, keeper.materialId) }
        dao.deleteMaterialsByIds(extras.map { it.materialId })

        // One "Concrete" row left (the keeper, -3), and all three of its readings point to it.
        assertThat(dao.getDuplicateServerIdMaterials()).isEmpty()
        assertThat(keeper.materialId).isEqualTo(-3L)
        assertThat(dao.getMaterial(-3L)).isNotNull()
        assertThat(dao.getMaterial(-2L)).isNull()
        assertThat(dao.getMaterial(-1L)).isNull()
        val keeperLogs = dao.observeMoistureLogsForRoom(6804L).first().filter { it.materialId == -3L }
        assertThat(keeperLogs.map { it.logId }).containsExactly(-10L, -11L, -12L)
        // The unrelated material + its reading are untouched.
        assertThat(dao.getMaterial(50L)).isNotNull()
    }
}
