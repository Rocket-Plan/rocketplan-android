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
}
