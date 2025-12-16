package com.example.rocketplan_android.data.local.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity

/**
 * Wrapper for image processor assemblies that includes human-readable project and room names.
 */
data class ImageProcessorAssemblyWithDetails(
    @Embedded val assembly: ImageProcessorAssemblyEntity,
    @ColumnInfo(name = "projectName") val projectName: String?,
    @ColumnInfo(name = "roomName") val roomName: String?,
    @ColumnInfo(name = "uploadedCount") val uploadedCount: Int = 0,
    @ColumnInfo(name = "failedCount") val failedCount: Int = 0,
    @ColumnInfo(name = "bytesUploaded") val bytesUploaded: Long = 0
)
