package com.example.rocketplan_android.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.SyncStatus
import java.util.Date

@Entity(
    tableName = "offline_companies",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["serverId"], unique = false)
    ]
)
data class OfflineCompanyEntity(
    @PrimaryKey(autoGenerate = true)
    val companyId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val name: String,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null
)

@Entity(
    tableName = "offline_users",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["email"], unique = true),
        Index(value = ["serverId"], unique = false)
    ]
)
data class OfflineUserEntity(
    @PrimaryKey(autoGenerate = true)
    val userId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val role: String? = null,
    val companyId: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null
)

@Entity(
    tableName = "offline_properties",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["serverId"], unique = false)
    ]
)
data class OfflinePropertyEntity(
    @PrimaryKey(autoGenerate = true)
    val propertyId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val address: String,
    val city: String? = null,
    val state: String? = null,
    val zipCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null
)

@Entity(
    tableName = "offline_projects",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["serverId"], unique = false),
        Index(value = ["syncStatus"]),
        Index(value = ["isDirty"])
    ]
)
data class OfflineProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val projectId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val title: String,
    val projectNumber: String? = null,
    val status: String,
    val propertyType: String? = null,
    val companyId: Long? = null,
    val propertyId: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null
)

@Entity(
    tableName = "offline_locations",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["parentLocationId"]),
        Index(value = ["serverId"])
    ]
)
data class OfflineLocationEntity(
    @PrimaryKey(autoGenerate = true)
    val locationId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val title: String,
    val type: String,
    val parentLocationId: Long? = null,
    val isAccessible: Boolean = true,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null
)

@Entity(
    tableName = "offline_rooms",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["locationId"]),
        Index(value = ["serverId"]),
        Index(value = ["syncStatus"]),
        Index(value = ["isDirty"])
    ]
)
data class OfflineRoomEntity(
    @PrimaryKey(autoGenerate = true)
    val roomId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val locationId: Long? = null,
    val title: String,
    val roomType: String? = null,
    val level: String? = null,
    val squareFootage: Double? = null,
    val isAccessible: Boolean = true,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null
)

@Entity(
    tableName = "offline_atmospheric_logs",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["roomId"]),
        Index(value = ["date"]),
        Index(value = ["serverId"]),
        Index(value = ["syncStatus"]),
        Index(value = ["isDirty"])
    ]
)
data class OfflineAtmosphericLogEntity(
    @PrimaryKey(autoGenerate = true)
    val logId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long? = null,
    val date: Date,
    val relativeHumidity: Double,
    val temperature: Double,
    val dewPoint: Double? = null,
    val gpp: Double? = null,
    val pressure: Double? = null,
    val windSpeed: Double? = null,
    val isExternal: Boolean = false,
    val isInlet: Boolean = false,
    val inletId: Long? = null,
    val outletId: Long? = null,
    val photoUrl: String? = null,
    val photoLocalPath: String? = null,
    val photoUploadStatus: String = "none",
    val photoAssemblyId: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "offline_photos",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["roomId"]),
        Index(value = ["assemblyId"]),
        Index(value = ["uploadStatus"]),
        Index(value = ["syncStatus"]),
        Index(value = ["isDirty"])
    ]
)
data class OfflinePhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val photoId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long? = null,
    val logId: Long? = null,
    val moistureLogId: Long? = null,
    val albumId: Long? = null,
    val fileName: String,
    val localPath: String,
    val remoteUrl: String? = null,
    val thumbnailUrl: String? = null,
    val uploadStatus: String = "pending",
    val assemblyId: String? = null,
    val tusUploadId: String? = null,
    val fileSize: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String,
    val capturedAt: Date? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "offline_equipment",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["roomId"]),
        Index(value = ["serverId"]),
        Index(value = ["syncStatus"])
    ]
)
data class OfflineEquipmentEntity(
    @PrimaryKey(autoGenerate = true)
    val equipmentId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long? = null,
    val type: String,
    val brand: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val quantity: Int = 1,
    val status: String,
    val startDate: Date? = null,
    val endDate: Date? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "offline_materials",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["serverId"], unique = false)
    ]
)
data class OfflineMaterialEntity(
    @PrimaryKey(autoGenerate = true)
    val materialId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val name: String,
    val description: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null
)

@Entity(
    tableName = "offline_moisture_logs",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["roomId"]),
        Index(value = ["materialId"]),
        Index(value = ["date"]),
        Index(value = ["serverId"]),
        Index(value = ["syncStatus"])
    ]
)
data class OfflineMoistureLogEntity(
    @PrimaryKey(autoGenerate = true)
    val logId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long,
    val materialId: Long,
    val date: Date,
    val moistureContent: Double,
    val location: String? = null,
    val depth: String? = null,
    val photoUrl: String? = null,
    val photoLocalPath: String? = null,
    val photoUploadStatus: String = "none",
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "offline_notes",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["roomId"]),
        Index(value = ["serverId"])
    ]
)
data class OfflineNoteEntity(
    @PrimaryKey(autoGenerate = true)
    val noteId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long? = null,
    val userId: Long? = null,
    val content: String,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "offline_damages",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["roomId"]),
        Index(value = ["serverId"])
    ]
)
data class OfflineDamageEntity(
    @PrimaryKey(autoGenerate = true)
    val damageId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long? = null,
    val title: String,
    val description: String? = null,
    val severity: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "offline_work_scopes",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["projectId"]),
        Index(value = ["roomId"]),
        Index(value = ["serverId"])
    ]
)
data class OfflineWorkScopeEntity(
    @PrimaryKey(autoGenerate = true)
    val workScopeId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long? = null,
    val name: String,
    val description: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val lastSyncedAt: Date? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)

@Entity(
    tableName = "offline_sync_queue",
    indices = [
        Index(value = ["entityType"]),
        Index(value = ["entityId"]),
        Index(value = ["status"]),
        Index(value = ["priority"]),
        Index(value = ["createdAt"]),
        Index(value = ["scheduledAt"])
    ]
)
data class OfflineSyncQueueEntity(
    @PrimaryKey
    val operationId: String,
    val entityType: String,
    val entityId: Long,
    val entityUuid: String,
    val operationType: SyncOperationType = SyncOperationType.UPDATE,
    val payload: ByteArray,
    val priority: SyncPriority = SyncPriority.MEDIUM,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val createdAt: Date = Date(),
    val scheduledAt: Date? = null,
    val lastAttemptAt: Date? = null,
    val completedAt: Date? = null,
    val status: SyncStatus = SyncStatus.PENDING,
    val errorMessage: String? = null
)

@Entity(
    tableName = "offline_conflicts",
    indices = [
        Index(value = ["entityType"]),
        Index(value = ["entityUuid"]),
        Index(value = ["detectedAt"]),
        Index(value = ["resolvedAt"])
    ]
)
data class OfflineConflictResolutionEntity(
    @PrimaryKey
    val conflictId: String,
    val entityType: String,
    val entityId: Long,
    val entityUuid: String,
    val localVersion: ByteArray,
    val remoteVersion: ByteArray,
    val conflictType: String,
    val detectedAt: Date = Date(),
    val resolvedAt: Date? = null,
    val resolution: String? = null,
    val resolvedBy: String? = null,
    val notes: String? = null
)
