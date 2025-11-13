package com.example.rocketplan_android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities that mirror the iOS Core Data assembly tracker so uploads can
 * survive process death, device restarts, and provide granular UI updates.
 */
@Entity(
    tableName = "image_processor_assemblies",
    indices = [
        Index(value = ["assemblyId"], unique = true),
        Index(value = ["roomId"]),
        Index(value = ["projectId"]),
        Index(value = ["status"]),
        Index(value = ["groupUuid"])
    ]
)
data class ImageProcessorAssemblyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val assemblyId: String,
    val roomId: Long?,
    val projectId: Long,
    val groupUuid: String,
    val status: String,
    val totalFiles: Int,
    val bytesReceived: Long,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val errorMessage: String? = null,
    val failsCount: Int = 0,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val lastTimeout: Int = 0,
    val isWaitingForConnectivity: Boolean = false,
    val entityType: String? = null,
    val entityId: Long? = null
)

@Entity(
    tableName = "image_processor_photos",
    foreignKeys = [
        ForeignKey(
            entity = ImageProcessorAssemblyEntity::class,
            parentColumns = ["id"],
            childColumns = ["assemblyLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["photoId"], unique = true),
        Index(value = ["assemblyLocalId"]),
        Index(value = ["assemblyUuid"]),
        Index(value = ["status"]),
        Index(value = ["uploadTaskId"])
    ]
)
data class ImageProcessorPhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val photoId: String,
    @ColumnInfo(name = "assemblyLocalId")
    val assemblyLocalId: Long,
    val assemblyUuid: String,
    val fileName: String,
    val localFilePath: String?,
    val status: String,
    val orderIndex: Int,
    val fileSize: Long,
    val bytesUploaded: Long = 0,
    val uploadTaskId: String? = null,
    val lastUpdatedAt: Long,
    val errorMessage: String? = null
)

enum class AssemblyStatus(val value: String) {
    QUEUED("queued"),
    PENDING("pending"),
    CREATING("creating"),
    CREATED("created"),
    UPLOADING("uploading"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    RETRYING("retrying"),
    WAITING_FOR_CONNECTIVITY("waiting_for_connectivity");

    companion object {
        fun fromValue(value: String): AssemblyStatus? =
            values().firstOrNull { it.value == value }
    }
}

enum class PhotoStatus(val value: String) {
    PENDING("pending"),
    PROCESSING("processing"),
    UPLOADING("uploading"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromValue(value: String): PhotoStatus? =
            values().firstOrNull { it.value == value }
    }
}
