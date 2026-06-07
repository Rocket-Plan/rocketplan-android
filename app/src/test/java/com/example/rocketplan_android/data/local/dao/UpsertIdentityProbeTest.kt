package com.example.rocketplan_android.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rocketplan_android.data.local.OfflineDatabase
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflineTimecardEntity
import com.example.rocketplan_android.data.local.mergePulledRowsByServerId
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
 * Deterministic probe for the RP-BUG-036-family question: when a server pull maps a row to a NEW
 * primary key (PK = server id) and upserts it while a local offline-created row already exists with
 * the SAME serverId but a DIFFERENT primary key, what does Room @Upsert do?
 *
 * The answer depends on whether the uuids match (the backend mints its own uuid via HasUuid unless
 * the client sends one — Note/Equipment/MoistureLog/AtmosphericLog send the client uuid only as
 * `idempotency_key`, a separate column, so the record's `uuid` is server-minted and differs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UpsertIdentityProbeTest {

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

    private fun note(noteId: Long, uuid: String, serverId: Long?) = OfflineNoteEntity(
        noteId = noteId,
        serverId = serverId,
        uuid = uuid,
        projectId = 1L,
        content = "body",
        syncStatus = if (serverId == null) SyncStatus.PENDING else SyncStatus.SYNCED,
    )

    @Test
    fun `pull with DIFFERENT uuid and new PK but same serverId DUPLICATES the row`() = runTest {
        // Local offline-created row, later assigned serverId=500 by push; keeps its LOCAL uuid + negative PK.
        dao.upsertNotes(listOf(note(noteId = -100L, uuid = "local-uuid", serverId = 500L)))
        // Server pull: backend minted its own uuid; mapper produces PK = server id.
        dao.upsertNotes(listOf(note(noteId = 500L, uuid = "server-uuid", serverId = 500L)))

        val rows = dao.getNotesByServerIds(listOf(500L))
        // PROBE RESULT: the two rows have different PKs and different uuids → no conflict → DUPLICATE.
        assertThat(rows).hasSize(2)
    }

    @Test
    fun `pull with SAME uuid and new PK but same serverId does NOT duplicate`() = runTest {
        // If the backend echoed the client uuid, the pulled row would share the uuid (unique index).
        dao.upsertNotes(listOf(note(noteId = -100L, uuid = "shared-uuid", serverId = 500L)))
        dao.upsertNotes(listOf(note(noteId = 500L, uuid = "shared-uuid", serverId = 500L)))

        val rows = dao.getNotesByServerIds(listOf(500L))
        // PROBE RESULT: the unique(uuid) index collapses them — exactly one row survives.
        assertThat(rows).hasSize(1)
    }

    private fun timecard(timecardId: Long, serverId: Long?, uuid: String, isDirty: Boolean = false) =
        OfflineTimecardEntity(
            timecardId = timecardId,
            serverId = serverId,
            uuid = uuid,
            projectId = 100L,
            userId = 5L,
            timeIn = Date(0L),
            companyId = 7L,
            isDirty = isDirty,
            syncStatus = if (serverId == null) SyncStatus.PENDING else SyncStatus.SYNCED,
        )

    @Test
    fun `timecard plain upsert duplicates an offline-created row on pull (RP-BUG-039 baseline)`() = runTest {
        // Offline-created timecard, synced: local negative PK, client uuid, serverId 900.
        dao.upsertTimecards(listOf(timecard(timecardId = -100L, serverId = 900L, uuid = "client-uuid")))
        // Backend returns no timecard uuid → mapper makes a fresh random uuid; pull PK = server id.
        dao.upsertTimecards(listOf(timecard(timecardId = 900L, serverId = 900L, uuid = "server-uuid")))

        // Without reconciliation a plain upsert leaves TWO rows for the same serverId.
        assertThat(dao.getTimecardsByServerIds(listOf(900L))).hasSize(2)
    }

    @Test
    fun `timecard saveTimecards serverId reconcile keeps one row with local identity (RP-BUG-039)`() = runTest {
        // Offline-created timecard, synced: local negative PK, client uuid, serverId 900.
        dao.upsertTimecards(listOf(timecard(timecardId = -100L, serverId = 900L, uuid = "client-uuid")))
        val pulled = listOf(timecard(timecardId = 900L, serverId = 900L, uuid = "server-uuid"))

        // Mirror LocalDataService.saveTimecards(reconcileByServerId = true).
        val existing = dao.getTimecardsByServerIds(listOf(900L)).associateBy { it.serverId }
        val merged = mergePulledRowsByServerId(
            incoming = pulled,
            existingByServerId = existing,
            serverIdOf = { it.serverId },
            isDirty = { it.isDirty },
            adoptLocalIdentity = { server, local -> server.copy(timecardId = local.timecardId, uuid = local.uuid) },
        )
        dao.upsertTimecards(merged)

        val rows = dao.getTimecardsByServerIds(listOf(900L))
        // Exactly one row, updated in place under the local identity — no duplicate.
        assertThat(rows).hasSize(1)
        assertThat(rows.first().timecardId).isEqualTo(-100L)
        assertThat(rows.first().uuid).isEqualTo("client-uuid")
    }
}
