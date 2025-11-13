package com.example.rocketplan_android.realtime

import com.google.gson.annotations.SerializedName

data class PusherEnvelope(
    @SerializedName("imageProcessorUpdate")
    val imageProcessorUpdate: ImageProcessorUpdate? = null
)

data class ImageProcessorUpdate(
    @SerializedName(value = "assemblyId", alternate = ["assembly_id"])
    val assemblyId: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName(value = "roomId", alternate = ["room_id"])
    val roomId: Long? = null,
    @SerializedName(value = "totalFiles", alternate = ["total_files"])
    val totalFiles: Int? = null,
    @SerializedName(value = "completedFiles", alternate = ["completed_files"])
    val completedFiles: Int? = null,
    @SerializedName(value = "failedFiles", alternate = ["failed_files"])
    val failedFiles: Int? = null,
    @SerializedName(value = "bytesReceived", alternate = ["bytes_received"])
    val bytesReceived: Long? = null,
    @SerializedName(value = "lastUpdatedAt", alternate = ["last_updated_at"])
    val lastUpdatedAt: String? = null,
    @SerializedName(value = "errorMessage", alternate = ["error_message"])
    val errorMessage: String? = null,
    @SerializedName("photos")
    val photos: List<PhotoUpdate>? = null
)

data class PhotoUpdate(
    @SerializedName("tusUploadId")
    val tusUploadId: String? = null,
    @SerializedName("tus_upload_id")
    val tusUploadIdSnake: String? = null,
    @SerializedName(value = "fileName", alternate = ["file_name"])
    val fileName: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName(value = "bytesUploaded", alternate = ["bytes_uploaded"])
    val bytesUploaded: Long? = null,
    @SerializedName(value = "errorMessage", alternate = ["error_message"])
    val errorMessage: String? = null
) {
    val uploadTaskId: String?
        get() = when {
            !tusUploadId.isNullOrBlank() -> tusUploadId
            !tusUploadIdSnake.isNullOrBlank() -> tusUploadIdSnake
            else -> null
        }
}
