package com.example.rocketplan_android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.rocketplan_android.BuildConfig
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.dao.OfflineDao
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineCompanyEntity
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageCauseEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogLevelEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogPropertyTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogRoomTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomPhotoSnapshotEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeCatalogItemEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity

@Database(
    entities = [
        OfflineCompanyEntity::class,
        OfflineUserEntity::class,
        OfflinePropertyEntity::class,
        OfflineProjectEntity::class,
        OfflineLocationEntity::class,
        OfflineRoomEntity::class,
        OfflineRoomTypeEntity::class,
        OfflineCatalogPropertyTypeEntity::class,
        OfflineCatalogLevelEntity::class,
        OfflineCatalogRoomTypeEntity::class,
        OfflineWorkScopeCatalogItemEntity::class,
        OfflineAtmosphericLogEntity::class,
        OfflineAlbumEntity::class,
        OfflineAlbumPhotoEntity::class,
        OfflinePhotoEntity::class,
        OfflineEquipmentEntity::class,
        OfflineMaterialEntity::class,
        OfflineMoistureLogEntity::class,
        OfflineNoteEntity::class,
        OfflineDamageEntity::class,
        OfflineDamageTypeEntity::class,
        OfflineDamageCauseEntity::class,
        OfflineWorkScopeEntity::class,
        OfflineSyncQueueEntity::class,
        OfflineConflictResolutionEntity::class,
        OfflineRoomPhotoSnapshotEntity::class,
        ImageProcessorAssemblyEntity::class,
        ImageProcessorPhotoEntity::class
    ],
    version = 15,
    exportSchema = false
)
@TypeConverters(OfflineTypeConverters::class)
abstract class OfflineDatabase : RoomDatabase() {

    abstract fun offlineDao(): OfflineDao
    abstract fun imageProcessorDao(): ImageProcessorDao

    companion object {
        private const val DATABASE_NAME = "rocketplan_offline.db"

        @Volatile
        private var instance: OfflineDatabase? = null

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE offline_rooms ADD COLUMN roomTypeId INTEGER")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN tabName TEXT")
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN category TEXT")
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN codePart1 TEXT")
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN codePart2 TEXT")
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN unit TEXT")
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN rate REAL")
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN quantity REAL")
                database.execSQL("ALTER TABLE offline_work_scopes ADD COLUMN lineTotal REAL")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_catalog_property_types (
                        propertyTypeId INTEGER NOT NULL,
                        name TEXT,
                        sortOrder INTEGER,
                        updatedAt TEXT,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(propertyTypeId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_offline_catalog_property_types_sortOrder
                    ON offline_catalog_property_types(sortOrder)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_catalog_levels (
                        levelId INTEGER NOT NULL,
                        name TEXT,
                        type TEXT,
                        isDefault INTEGER,
                        isStandard INTEGER,
                        propertyTypeIds TEXT NOT NULL,
                        updatedAt TEXT,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(levelId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_offline_catalog_levels_name
                    ON offline_catalog_levels(name)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_offline_catalog_levels_type
                    ON offline_catalog_levels(type)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_catalog_room_types (
                        roomTypeId INTEGER NOT NULL,
                        name TEXT,
                        type TEXT,
                        isStandard INTEGER,
                        isDefault INTEGER,
                        levelIds TEXT NOT NULL,
                        propertyTypeIds TEXT NOT NULL,
                        updatedAt TEXT,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(roomTypeId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_offline_catalog_room_types_name
                    ON offline_catalog_room_types(name)
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_offline_catalog_room_types_type
                    ON offline_catalog_room_types(type)
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): OfflineDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context): OfflineDatabase =
            Room.databaseBuilder(context, OfflineDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_14_15)
                .apply {
                    // Only allow destructive migrations in debug builds to avoid data loss in prod.
                    if (BuildConfig.DEBUG) {
                        fallbackToDestructiveMigration()
                    }
                }
                .build()
    }
}
