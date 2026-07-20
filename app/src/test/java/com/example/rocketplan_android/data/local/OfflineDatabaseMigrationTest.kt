package com.example.rocketplan_android.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-BUG-029: verifies MIGRATION_29_30 adds offline_locations.propertyServerId (+ index)
 * and preserves existing rows. The DB uses exportSchema=false so MigrationTestHelper is not
 * available; instead we build the v29 table by hand and apply the real migration object.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OfflineDatabaseMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(29) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Minimal v29 offline_locations (pre-propertyServerId) — only what the migration touches.
                    db.execSQL(
                        "CREATE TABLE offline_locations (" +
                            "locationId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "serverId INTEGER, uuid TEXT NOT NULL, projectId INTEGER NOT NULL, " +
                            "title TEXT NOT NULL, type TEXT NOT NULL, " +
                            "isDirty INTEGER NOT NULL DEFAULT 0, isDeleted INTEGER NOT NULL DEFAULT 0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() = helper.close()

    @Test
    fun `migration 29 to 30 adds propertyServerId column and index, preserving rows`() {
        db.execSQL(
            "INSERT INTO offline_locations (serverId, uuid, projectId, title, type) " +
                "VALUES (5001, 'loc-uuid', 100, 'Living Room', 'level')"
        )

        OfflineDatabase.MIGRATION_29_30.migrate(db)

        // column present
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(offline_locations)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) columns.add(c.getString(nameIdx))
        }
        assertThat(columns).contains("propertyServerId")

        // index present
        val indexes = mutableListOf<String>()
        db.query("PRAGMA index_list(offline_locations)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) indexes.add(c.getString(nameIdx))
        }
        assertThat(indexes).contains("index_offline_locations_propertyServerId")

        // existing row preserved, new column backfills NULL
        db.query("SELECT serverId, propertyServerId FROM offline_locations").use { c ->
            assertThat(c.count).isEqualTo(1)
            c.moveToFirst()
            assertThat(c.getLong(c.getColumnIndex("serverId"))).isEqualTo(5001L)
            assertThat(c.isNull(c.getColumnIndex("propertyServerId"))).isTrue()
        }
    }

    /** RP-FR-019: MIGRATION_30_31 creates the serialized-equipment tables + indexes. */
    @Test
    fun `migration 30 to 31 creates serialized equipment tables and indexes`() {
        OfflineDatabase.MIGRATION_30_31.migrate(db)

        // Both tables exist and are writable with the expected columns.
        db.execSQL(
            "INSERT INTO offline_equipment_assets " +
                "(serverId, uuid, companyId, catalogUuid, name, isStandard, status, " +
                " createdAt, updatedAt, syncStatus) " +
                "VALUES (900, 'asset-uuid', 7, 'cat-uuid', 'Air Mover', 1, 'available', 0, 0, 'SYNCED')"
        )
        db.execSQL(
            "INSERT INTO offline_equipment_placements " +
                "(serverId, uuid, assetId, roomId, isOpen, createdAt, updatedAt, syncStatus) " +
                "VALUES (12, 'placement-uuid', 1, 6, 1, 0, 0, 'PENDING')"
        )

        db.query("SELECT status FROM offline_equipment_assets WHERE serverId = 900").use { c ->
            assertThat(c.count).isEqualTo(1)
            c.moveToFirst()
            assertThat(c.getString(c.getColumnIndex("status"))).isEqualTo("available")
        }
        db.query("SELECT isOpen FROM offline_equipment_placements WHERE serverId = 12").use { c ->
            assertThat(c.count).isEqualTo(1)
            c.moveToFirst()
            assertThat(c.getInt(c.getColumnIndex("isOpen"))).isEqualTo(1)
        }

        val assetIndexes = mutableListOf<String>()
        db.query("PRAGMA index_list(offline_equipment_assets)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) assetIndexes.add(c.getString(nameIdx))
        }
        assertThat(assetIndexes).contains("index_offline_equipment_assets_uuid")
        assertThat(assetIndexes).contains("index_offline_equipment_assets_serverId")

        val placementIndexes = mutableListOf<String>()
        db.query("PRAGMA index_list(offline_equipment_placements)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) placementIndexes.add(c.getString(nameIdx))
        }
        assertThat(placementIndexes).contains("index_offline_equipment_placements_assetId")
    }

    /** RP-BUG-279: MIGRATION_31_32 adds catalogServerId and catalogUuid to offline_equipment.
     * Per the RP-BUG-279 plan, old serverId values were catalog ids and "cannot be trusted as
     * pivot ids." The migration neutralizes them by copying to catalogServerId and nulling serverId.
     */
    @Test
    fun `migration 31 to 32 adds catalogServerId and catalogUuid columns, preserving rows`() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS offline_equipment (
                equipmentId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                serverId INTEGER,
                uuid TEXT NOT NULL,
                projectId INTEGER NOT NULL,
                roomId INTEGER,
                type TEXT NOT NULL,
                brand TEXT,
                model TEXT,
                serialNumber TEXT,
                quantity INTEGER NOT NULL DEFAULT 1,
                status TEXT NOT NULL,
                startDate INTEGER,
                endDate INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                serverUpdatedAt INTEGER,
                lastSyncedAt INTEGER,
                syncStatus TEXT NOT NULL,
                syncVersion INTEGER NOT NULL DEFAULT 0,
                isDirty INTEGER NOT NULL DEFAULT 0,
                isDeleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO offline_equipment " +
                "(serverId, uuid, projectId, type, status, isDirty, isDeleted, syncStatus, " +
                " createdAt, updatedAt, syncVersion, quantity) " +
                "VALUES (6000, 'equip-uuid', 100, 'Dehumidifier', 'active', 0, 0, 'SYNCED', 0, 0, 0, 2)"
        )

        OfflineDatabase.MIGRATION_31_32.migrate(db)

        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(offline_equipment)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) columns.add(c.getString(nameIdx))
        }
        assertThat(columns).contains("catalogServerId")
        assertThat(columns).contains("catalogUuid")

        db.query("SELECT serverId, catalogServerId, catalogUuid, quantity FROM offline_equipment WHERE uuid = 'equip-uuid'").use { c ->
            assertThat(c.count).isEqualTo(1)
            c.moveToFirst()
            assertThat(c.isNull(c.getColumnIndex("serverId"))).isTrue()
            assertThat(c.getLong(c.getColumnIndex("catalogServerId"))).isEqualTo(6000L)
            assertThat(c.isNull(c.getColumnIndex("catalogUuid"))).isTrue()
            assertThat(c.getInt(c.getColumnIndex("quantity"))).isEqualTo(2)
        }
    }
}
