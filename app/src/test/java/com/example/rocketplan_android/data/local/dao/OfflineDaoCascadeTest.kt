package com.example.rocketplan_android.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rocketplan_android.data.local.OfflineDatabase
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-BUG-029 / RP-FR-003: exercises the cascade + by-serverIds SQL against a real
 * in-memory Room database (the logic in LocalDataService has no JVM seam, so the DAO
 * queries are tested directly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OfflineDaoCascadeTest {

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

    private fun location(
        localId: Long,
        serverId: Long?,
        propertyServerId: Long?,
        isDirty: Boolean,
    ) = OfflineLocationEntity(
        locationId = localId,
        serverId = serverId,
        uuid = "loc-$localId",
        projectId = 100L,
        propertyServerId = propertyServerId,
        title = "Loc $localId",
        type = "level",
        isDirty = isDirty,
        syncStatus = if (isDirty) SyncStatus.PENDING else SyncStatus.SYNCED,
    )

    private fun room(
        localId: Long,
        locationId: Long,
        serverId: Long?,
        isDirty: Boolean,
    ) = OfflineRoomEntity(
        roomId = localId,
        serverId = serverId,
        uuid = "room-$localId",
        projectId = 100L,
        locationId = locationId,
        title = "Room $localId",
        isDirty = isDirty,
        syncStatus = if (isDirty) SyncStatus.PENDING else SyncStatus.SYNCED,
    )

    @Test
    fun `getCleanSyncedLocationLocalIds returns only clean synced rows under the property`() = runTest {
        dao.upsertLocations(
            listOf(
                location(1001, serverId = 5001, propertyServerId = 900, isDirty = false), // eligible
                location(1002, serverId = 5002, propertyServerId = 900, isDirty = true),  // dirty
                location(1003, serverId = null, propertyServerId = 900, isDirty = false), // local-only
                location(1004, serverId = 5004, propertyServerId = 901, isDirty = false), // other property
            )
        )

        val ids = dao.getCleanSyncedLocationLocalIdsForProperties(listOf(900L))

        assertThat(ids).containsExactly(1001L)
    }

    @Test
    fun `cascade deletes clean synced children and preserves dirty, local-only, and other-property rows`() = runTest {
        dao.upsertLocations(
            listOf(
                location(1001, serverId = 5001, propertyServerId = 900, isDirty = false),
                location(1002, serverId = 5002, propertyServerId = 900, isDirty = true),
                location(1004, serverId = 5004, propertyServerId = 901, isDirty = false),
            )
        )
        dao.upsertRooms(
            listOf(
                room(2001, locationId = 1001, serverId = 6001, isDirty = false), // clean under L1 → delete
                room(2002, locationId = 1001, serverId = 6002, isDirty = true),  // dirty under L1 → preserve
                room(2003, locationId = 1001, serverId = null, isDirty = false), // local-only → preserve
                room(2004, locationId = 1004, serverId = 6004, isDirty = false), // other property → preserve
            )
        )

        // mirror LocalDataService.cascadePropertyDeletion ordering
        val localLocationIds = dao.getCleanSyncedLocationLocalIdsForProperties(listOf(900L))
        dao.markRoomsDeletedCleanByLocalLocationIds(localLocationIds)
        dao.markLocationsDeletedByLocalIds(localLocationIds)

        // locations (getLocation filters isDeleted=0, so a deleted row reads back as null)
        assertThat(dao.getLocation(1001)).isNull()              // clean synced → deleted
        assertThat(dao.getLocation(1002)!!.isDeleted).isFalse() // dirty → preserved
        assertThat(dao.getLocation(1004)!!.isDeleted).isFalse() // other property → preserved
        // rooms
        assertThat(dao.getRoom(2001)!!.isDeleted).isTrue()      // clean under deleted location → deleted
        assertThat(dao.getRoom(2002)!!.isDeleted).isFalse()     // dirty → preserved
        assertThat(dao.getRoom(2003)!!.isDeleted).isFalse()     // local-only → preserved
        assertThat(dao.getRoom(2004)!!.isDeleted).isFalse()     // other property → preserved
    }

    @Test
    fun `getLocationsByServerIds returns existing rows for the dirty-merge lookup`() = runTest {
        dao.upsertLocations(
            listOf(
                location(1001, serverId = 5001, propertyServerId = 900, isDirty = true),
                location(1002, serverId = 5002, propertyServerId = 900, isDirty = false),
            )
        )

        val rows = dao.getLocationsByServerIds(listOf(5001L, 5002L))

        assertThat(rows.map { it.serverId }).containsExactly(5001L, 5002L)
        assertThat(rows.first { it.serverId == 5001L }.isDirty).isTrue()
    }
}
