package com.example.rocketplan_android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.rocketplan_android.data.local.dao.OfflineDao
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineCompanyEntity
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity

@Database(
    entities = [
        OfflineCompanyEntity::class,
        OfflineUserEntity::class,
        OfflinePropertyEntity::class,
        OfflineProjectEntity::class,
        OfflineLocationEntity::class,
        OfflineRoomEntity::class,
        OfflineAtmosphericLogEntity::class,
        OfflineAlbumEntity::class,
        OfflineAlbumPhotoEntity::class,
        OfflinePhotoEntity::class,
        OfflineEquipmentEntity::class,
        OfflineMaterialEntity::class,
        OfflineMoistureLogEntity::class,
        OfflineNoteEntity::class,
        OfflineDamageEntity::class,
        OfflineWorkScopeEntity::class,
        OfflineSyncQueueEntity::class,
        OfflineConflictResolutionEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(OfflineTypeConverters::class)
abstract class OfflineDatabase : RoomDatabase() {

    abstract fun offlineDao(): OfflineDao

    companion object {
        private const val DATABASE_NAME = "rocketplan_offline.db"

        @Volatile
        private var instance: OfflineDatabase? = null

        fun getInstance(context: Context): OfflineDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context): OfflineDatabase =
            Room.databaseBuilder(context, OfflineDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
