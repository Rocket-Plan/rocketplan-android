package com.example.rocketplan_android.data.model

import android.net.Uri
import com.google.gson.annotations.SerializedName

data class ImageProcessorAssemblyRequest(
    @SerializedName("assembly_id")
    val assemblyId: String,
    @SerializedName("total_files")
    val totalFiles: Int,
    @SerializedName("room_id")
    val roomId: Long?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("group_uuid")
    val groupUuid: String,
    @SerializedName("bytes_received")
    val bytesReceived: Long,
    @SerializedName("photo_names")
    val photoNames: List<String>,
    @SerializedName("albums")
    val albums: Map<String, List<String>> = emptyMap(),
    @SerializedName("ir_photos")
    val irPhotos: List<Map<String, IRPhotoData>> = emptyList(),
    @SerializedName("order")
    val order: List<String> = emptyList(),
    @SerializedName("notes")
    val notes: Map<String, List<String>> = emptyMap(),
    @SerializedName("entity_type")
    val entityType: String? = null,
    @SerializedName("entity_id")
    val entityId: Long? = null
)

data class IRPhotoData(
    @SerializedName("thermal_file_name")
    val thermalFileName: String,
    @SerializedName("visual_file_name")
    val visualFileName: String
)

data class ImageProcessorAssemblyResponse(
    val success: Boolean,
    val message: String?,
    val errors: Map<String, List<String>>? = null,
    @SerializedName("processing_url")
    val processingUrl: String?
)

data class FileToUpload(
    val uri: Uri,
    val filename: String,
    val deleteOnCompletion: Boolean = false
)

/**
 * Status payload returned by the image processor completion endpoints.
 * Handles both plain resource responses and { data: { ... } } envelopes.
 */
data class ImageProcessorStatusResponse(
    val data: ImageProcessorStatusPayload? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("completed_files")
    val completedFiles: Int? = null,
    @SerializedName("remaining_files")
    val remainingFiles: Int? = null,
    @SerializedName("is_complete")
    val isComplete: Boolean? = null
) {
    fun toSnapshot(): ImageProcessorStatusSnapshot? {
        val payload = data ?: ImageProcessorStatusPayload(
            status = status,
            completedFiles = completedFiles,
            remainingFiles = remainingFiles,
            isComplete = isComplete
        )
        if (payload.isEmpty()) return null
        return ImageProcessorStatusSnapshot(
            status = payload.status,
            completedFiles = payload.completedFiles,
            remainingFiles = payload.remainingFiles,
            isComplete = payload.isComplete
        )
    }
}

data class ImageProcessorStatusPayload(
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("completed_files")
    val completedFiles: Int? = null,
    @SerializedName("remaining_files")
    val remainingFiles: Int? = null,
    @SerializedName("is_complete")
    val isComplete: Boolean? = null
) {
    fun isEmpty(): Boolean =
        status == null && completedFiles == null && remainingFiles == null && isComplete == null
}

data class ImageProcessorStatusSnapshot(
    val status: String?,
    val completedFiles: Int?,
    val remainingFiles: Int?,
    val isComplete: Boolean?
)
