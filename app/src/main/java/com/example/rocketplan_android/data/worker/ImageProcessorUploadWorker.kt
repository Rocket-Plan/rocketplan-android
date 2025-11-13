package com.example.rocketplan_android.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URI

/**
 * Worker that handles image processor photo uploads using simple HTTP uploads (matching iOS).
 * Uploads photos in assemblies with status CREATED or RETRYING.
 */
class ImageProcessorUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ImgProcessorUploadWorker"
        const val WORK_NAME = "image_processor_upload"
    }

    private val app = context.applicationContext as RocketPlanApplication
    private val dao = app.imageProcessorDao
    private val uploadStore = app.imageProcessorUploadStore
    private val remoteLogger = app.remoteLogger
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 ImageProcessorUploadWorker started")

            // Find assemblies ready for upload
            val assemblies = dao.getAssembliesByStatus(
                listOf(
                    AssemblyStatus.CREATED.value,
                    AssemblyStatus.RETRYING.value
                )
            )

            if (assemblies.isEmpty()) {
                Log.d(TAG, "✅ No assemblies to upload")
                return@withContext Result.success()
            }

            Log.d(TAG, "📤 Found ${assemblies.size} assemblies to upload")

            for (assembly in assemblies) {
                try {
                    processAssembly(assembly)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to process assembly ${assembly.assemblyId}", e)
                    updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.FAILED, e.message)
                }
            }

            Log.d(TAG, "✅ ImageProcessorUploadWorker completed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ ImageProcessorUploadWorker failed", e)
            Result.retry()
        }
    }

    private suspend fun processAssembly(assembly: ImageProcessorAssemblyEntity) {
        Log.d(TAG, "📸 Processing assembly ${assembly.assemblyId}")

        // Get upload data
        val uploadData = uploadStore.read(assembly.assemblyId)
        if (uploadData == null) {
            Log.e(TAG, "❌ No upload data found for assembly ${assembly.assemblyId}")
            updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.FAILED, "Upload data missing")
            return
        }

        // Get photos for this assembly
        val photos = dao.getPhotosByAssemblyUuid(assembly.assemblyId)
        val pendingPhotos = photos.filter { it.status == PhotoStatus.PENDING.value }

        if (pendingPhotos.isEmpty()) {
            Log.d(TAG, "✅ All photos uploaded for assembly ${assembly.assemblyId}")
            checkIfAssemblyComplete(assembly.assemblyId)
            return
        }

        // Update assembly status to uploading
        updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.UPLOADING, null)

        // Upload each pending photo
        var successCount = 0
        var failureCount = 0

        for (photo in pendingPhotos) {
            try {
                uploadPhoto(assembly, photo, uploadData.processingUrl, uploadData.apiKey)
                successCount++
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to upload photo ${photo.fileName}", e)
                failureCount++
                updatePhotoStatus(photo.photoId, PhotoStatus.FAILED, e.message)
            }
        }

        Log.d(TAG, "📊 Upload results: $successCount succeeded, $failureCount failed")

        // Check if assembly is complete
        checkIfAssemblyComplete(assembly.assemblyId)
    }

    private suspend fun uploadPhoto(
        assembly: ImageProcessorAssemblyEntity,
        photo: ImageProcessorPhotoEntity,
        processingUrl: String,
        apiKey: String?
    ) {
        Log.d(TAG, "📤 Uploading photo ${photo.fileName} for assembly ${assembly.assemblyId}")

        val localPath = photo.localFilePath ?: throw IllegalStateException("No local file path")
        val uri = URI(localPath)
        val file = File(uri.path)

        if (!file.exists()) {
            throw IllegalStateException("Photo file not found: ${file.absolutePath}")
        }

        // Determine MIME type
        val mimeType = getMimeType(file)

        // Build request body
        val requestBody = file.asRequestBody(mimeType.toMediaType())

        // Build request with headers (matching iOS)
        val uploadUrl = "$processingUrl/upload"
        val requestBuilder = Request.Builder()
            .url(uploadUrl)
            .addHeader("X-Assembly-Id", assembly.assemblyId)
            .addHeader("Content-Type", mimeType)
            .put(requestBody)

        // Add API key header if available
        if (apiKey != null) {
            requestBuilder.addHeader("x-api-key", apiKey)
        }

        val request = requestBuilder.build()

        // Update photo status to uploading
        updatePhotoStatus(photo.photoId, PhotoStatus.UPLOADING, null)

        // Execute upload
        val response = okHttpClient.newCall(request).execute()

        if (response.isSuccessful) {
            Log.d(TAG, "✅ Photo uploaded: ${photo.fileName}")
            updatePhotoStatus(photo.photoId, PhotoStatus.COMPLETED, null)

            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Photo uploaded successfully",
                metadata = mapOf(
                    "assembly_id" to assembly.assemblyId,
                    "photo_id" to photo.photoId,
                    "file_name" to photo.fileName,
                    "file_size" to photo.fileSize.toString()
                )
            )
        } else {
            val errorMessage = "HTTP ${response.code}: ${response.message}"
            Log.e(TAG, "❌ Photo upload failed: $errorMessage")
            throw IllegalStateException(errorMessage)
        }

        response.close()
    }

    private suspend fun checkIfAssemblyComplete(assemblyId: String) {
        val photos = dao.getPhotosByAssemblyUuid(assemblyId)
        val completedCount = photos.count { it.status == PhotoStatus.COMPLETED.value }
        val failedCount = photos.count { it.status == PhotoStatus.FAILED.value }
        val totalCount = photos.size

        Log.d(TAG, "📊 Assembly $assemblyId: $completedCount/$totalCount completed, $failedCount failed")

        when {
            completedCount == totalCount -> {
                // All photos uploaded successfully
                updateAssemblyStatus(assemblyId, AssemblyStatus.PROCESSING, null)
                Log.d(TAG, "✅ Assembly $assemblyId upload complete, now processing")

                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Assembly upload completed",
                    metadata = mapOf(
                        "assembly_id" to assemblyId,
                        "total_photos" to totalCount.toString()
                    )
                )
            }
            failedCount > 0 && (completedCount + failedCount == totalCount) -> {
                // Some photos failed
                updateAssemblyStatus(assemblyId, AssemblyStatus.FAILED, "$failedCount photos failed to upload")
                Log.e(TAG, "❌ Assembly $assemblyId failed: $failedCount photos failed")
            }
            else -> {
                // Still uploading
                Log.d(TAG, "⏳ Assembly $assemblyId still uploading: $completedCount/$totalCount")
            }
        }
    }

    private suspend fun updateAssemblyStatus(
        assemblyId: String,
        status: AssemblyStatus,
        errorMessage: String?
    ) {
        val assembly = dao.getAssembly(assemblyId) ?: return
        val updated = assembly.copy(
            status = status.value,
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = errorMessage
        )
        dao.updateAssembly(updated)
        Log.d(TAG, "📝 Assembly $assemblyId status updated: ${status.value}")
    }

    private suspend fun updatePhotoStatus(
        photoId: String,
        status: PhotoStatus,
        errorMessage: String?
    ) {
        val photo = dao.getPhotoByPhotoId(photoId) ?: return
        val updated = photo.copy(
            status = status.value,
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = errorMessage
        )
        dao.updatePhoto(updated)
    }

    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
    }
}
