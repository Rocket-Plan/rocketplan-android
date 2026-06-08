package com.example.rocketplan_android.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rocketplan_android.data.local.OfflineDatabase
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.google.common.truth.Truth.assertThat
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
}
