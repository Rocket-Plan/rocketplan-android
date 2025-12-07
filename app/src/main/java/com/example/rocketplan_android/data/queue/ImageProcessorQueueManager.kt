package com.example.rocketplan_android.data.queue

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.dao.OfflineDao
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.data.model.FileToUpload
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import com.example.rocketplan_android.data.worker.ImageProcessorRetryWorker
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import okio.source
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink

/**
 * Manages sequential processing of image processor assemblies.
 * Ensures only one assembly uploads at a time (matching iOS behavior).
 */
class ImageProcessorQueueManager(
    private val context: Context,
    private val dao: ImageProcessorDao,
    private val offlineDao: OfflineDao,
    private val uploadStore: ImageProcessorUploadStore,
    private val configRepository: ImageProcessingConfigurationRepository,
    private val secureStorage: SecureStorage,
    private val remoteLogger: RemoteLogger?
) {
    companion object {
        private const val TAG = "ImgProcessorQueueMgr"
        private const val MAX_RETRY_ATTEMPTS = 13
        private const val INITIAL_RETRY_TIMEOUT_SECONDS = 10
        // Match iOS cap: exponential backoff up to 30 minutes
        private const val MAX_RETRY_TIMEOUT_SECONDS = 1800
        private const val ONE_OFF_RETRY_WORK_NAME = "image_processor_retry_one_off"
    }

    private val isProcessingQueue = AtomicBoolean(false)
    private val queueMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    /**
     * Recover assemblies left in UPLOADING state after process death.
     * Called on cold start to reset interrupted uploads.
     */
    suspend fun recoverStrandedAssemblies() {
        try {
            Log.d(TAG, "🔄 Recovering stranded assemblies")

            // Find assemblies left in UPLOADING state
            val uploadingAssemblies = dao.getAssembliesByStatus(listOf(AssemblyStatus.UPLOADING.value))

            for (assembly in uploadingAssemblies) {
                // Reset UPLOADING photos back to PENDING for retry
                val photos = dao.getPhotosByAssemblyUuid(assembly.assemblyId)
                val uploadingPhotos = photos.filter { it.status == PhotoStatus.UPLOADING.value }

                for (photo in uploadingPhotos) {
                    val updated = photo.copy(
                        status = PhotoStatus.PENDING.value,
                        lastUpdatedAt = System.currentTimeMillis(),
                        errorMessage = "Reset after process interruption"
                    )
                    dao.updatePhoto(updated)
                }

                // Reset assembly back to CREATED so it can be reprocessed
                updateAssemblyStatus(
                    assembly.assemblyId,
                    AssemblyStatus.CREATED,
                    "Recovered after process death"
                )

                Log.d(TAG, "🔄 Recovered assembly ${assembly.assemblyId} (${uploadingPhotos.size} photos reset)")
            }

            if (uploadingAssemblies.isNotEmpty()) {
                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Recovered stranded assemblies on cold start",
                    metadata = mapOf("count" to uploadingAssemblies.size.toString())
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error recovering stranded assemblies", e)
        }
    }

    /**
     * Process the next queued assembly.
     * Called when:
     * - New assembly is created
     * - Previous assembly completes
     * - App returns to foreground
     */
    fun processNextQueuedAssembly() {
        scope.launch {
            queueMutex.withLock {
                try {
                    Log.d(TAG, "🔍 Checking for next queued assembly (isProcessing=${isProcessingQueue.get()})")

                    // Check if already processing
                    if (isProcessingQueue.get()) {
                        Log.d(TAG, "⏸️ Queue is locked, skipping")
                        return@launch
                    }

                    // Get next created assembly (status is CREATED after backend POST in ImageProcessorRepository)
                    val createdAssemblies = dao.getAssembliesByStatus(listOf(AssemblyStatus.CREATED.value))
                    val nextAssembly = createdAssemblies.firstOrNull()

                    if (nextAssembly == null) {
                        Log.d(TAG, "📭 No created assemblies found")
                        return@launch
                    }

                    // Lock queue
                    isProcessingQueue.set(true)
                    Log.d(TAG, "🔒 Queue locked, processing assembly ${nextAssembly.assemblyId}")

                    remoteLogger?.log(
                        level = LogLevel.INFO,
                        tag = TAG,
                        message = "Queue locked - starting assembly processing",
                        metadata = mapOf(
                            "assembly_id" to nextAssembly.assemblyId,
                            "total_files" to nextAssembly.totalFiles.toString(),
                            "room_id" to (nextAssembly.roomId?.toString() ?: "null")
                        )
                    )

                    // Upload assembly
                    uploadAssembly(nextAssembly)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error processing queue", e)
                    isProcessingQueue.set(false)
                    remoteLogger?.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "Queue processing failed: ${e.message}",
                        metadata = mapOf("error" to (e.message ?: "unknown"))
                    )
                }
            }
        }
    }

    /**
     * Mark currently uploading assemblies as WAITING_FOR_CONNECTIVITY.
     * Called when network connectivity is lost.
     */
    fun pauseForConnectivity() {
        scope.launch {
            try {
                Log.d(TAG, "⏸️ Pausing assemblies due to network loss")

                // Find assemblies currently uploading
                val uploadingAssemblies = dao.getAssembliesByStatus(listOf(AssemblyStatus.UPLOADING.value))

                for (assembly in uploadingAssemblies) {
                    updateAssemblyStatus(
                        assembly.assemblyId,
                        AssemblyStatus.WAITING_FOR_CONNECTIVITY,
                        "Network connectivity lost"
                    )
                    Log.d(TAG, "⏸️ Assembly ${assembly.assemblyId} paused for connectivity")
                }

                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Assemblies paused for connectivity",
                    metadata = mapOf("count" to uploadingAssemblies.size.toString())
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error pausing assemblies for connectivity", e)
            }
        }
    }

    /**
     * Process retry queue - check for failed assemblies ready for retry.
     * Called periodically by RetryWorker.
     */
    fun processRetryQueue(bypassTimeout: Boolean = false) {
        scope.launch {
            try {
                Log.d(TAG, "🔄 Processing retry queue (bypassTimeout=$bypassTimeout)")

                val currentTime = System.currentTimeMillis()
                val retryableAssemblies = dao.getRetryableAssemblies(
                    retryableStatuses = listOf(
                        AssemblyStatus.FAILED.value,
                        AssemblyStatus.WAITING_FOR_CONNECTIVITY.value
                    ),
                    currentTimeMillis = if (bypassTimeout) Long.MAX_VALUE else currentTime
                ).filter { it.failsCount < MAX_RETRY_ATTEMPTS }

                if (retryableAssemblies.isEmpty()) {
                    Log.d(TAG, "✅ No assemblies ready for retry")
                    return@launch
                }

                Log.d(TAG, "📋 Found ${retryableAssemblies.size} assemblies ready for retry")

                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Retry queue processing",
                    metadata = mapOf(
                        "retryable_count" to retryableAssemblies.size.toString(),
                        "bypass_timeout" to bypassTimeout.toString()
                    )
                )

                // Mark assemblies as RETRYING and queue them
                for (assembly in retryableAssemblies) {
                    // Reset failed photos to PENDING so they'll be retried
                    resetFailedPhotosToPending(assembly.assemblyId)

                    updateAssemblyStatus(
                        assembly.assemblyId,
                        AssemblyStatus.RETRYING,
                        null
                    )
                    updateAssemblyStatus(
                        assembly.assemblyId,
                        AssemblyStatus.CREATED, // Back to CREATED so processNextQueuedAssembly() will find it
                        null
                    )
                    Log.d(TAG, "🔄 Queued assembly ${assembly.assemblyId} for retry")
                }

                // Trigger queue processing
                processNextQueuedAssembly()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing retry queue", e)
                remoteLogger?.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Retry queue processing failed: ${e.message}",
                    metadata = mapOf("error" to (e.message ?: "unknown"))
                )
            }
        }
    }

    private suspend fun uploadAssembly(assembly: ImageProcessorAssemblyEntity) {
        try {
            Log.d(TAG, "📸 Uploading assembly ${assembly.assemblyId}")

            // Get upload data
            val uploadData = uploadStore.read(assembly.assemblyId)
            if (uploadData == null) {
                val errorMessage = "Upload data missing - cannot retry without stored credentials"
                Log.e(TAG, "❌ No upload data for assembly ${assembly.assemblyId}")

                // Mark assembly as FAILED (terminal state - no retry possible without upload data)
                updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.FAILED, errorMessage)

                onAssemblyCompleted(assembly.assemblyId, success = false, errorMessage = errorMessage)
                return
            }

            // Assembly is already CREATED (backend POST done in ImageProcessorRepository.createAssembly)
            // Update status to UPLOADING to indicate photo uploads are starting
            updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.UPLOADING, null)

            // Get photos for this assembly
            val photos = dao.getPhotosByAssemblyUuid(assembly.assemblyId)
            val pendingPhotos = photos.filter { it.status == PhotoStatus.PENDING.value }

            if (pendingPhotos.isEmpty()) {
                Log.d(TAG, "✅ All photos already uploaded for assembly ${assembly.assemblyId}")
                checkIfAssemblyComplete(assembly.assemblyId)
                return
            }

            Log.d(TAG, "📤 Uploading ${pendingPhotos.size} photos for assembly ${assembly.assemblyId}")

            // Upload each photo sequentially
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ Assembly upload failed for ${assembly.assemblyId}", e)

            // Mark assembly as FAILED before completing
            updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.FAILED, e.message)

            // Calculate exponential backoff for retry
            val currentAssembly = dao.getAssembly(assembly.assemblyId)
            if (currentAssembly != null) {
                val nextTimeout = calculateNextRetryTimeout(currentAssembly.retryCount)
                val nextRetryAt = System.currentTimeMillis() + (nextTimeout * 1000L)

                val updated = currentAssembly.copy(
                    failsCount = currentAssembly.failsCount + 1,
                    retryCount = currentAssembly.retryCount + 1,
                    nextRetryAt = nextRetryAt,
                    lastTimeout = nextTimeout
                )
                dao.updateAssembly(updated)

                if (updated.failsCount < MAX_RETRY_ATTEMPTS) {
                    scheduleOneOffRetry(nextTimeout.toLong())
                }
            }

            onAssemblyCompleted(assembly.assemblyId, success = false, errorMessage = e.message)
        }
    }

    private suspend fun uploadPhoto(
        assembly: ImageProcessorAssemblyEntity,
        photo: ImageProcessorPhotoEntity,
        processingUrl: String,
        apiKey: String?
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "📤 Uploading photo ${photo.fileName} for assembly ${assembly.assemblyId}")

        val localPath = photo.localFilePath
            ?: throw IllegalStateException("No local file path for photo ${photo.fileName}")
        val mimeType = determineMimeType(localPath)
        val requestBody = buildRequestBody(localPath, mimeType)

        // Build URL with filename query parameter (matching iOS behavior)
        val uploadUrl = "$processingUrl/upload".toHttpUrl()
            .newBuilder()
            .addQueryParameter("filename", photo.fileName)
            .build()

        // Build request with headers (matching iOS)
        val requestBuilder = Request.Builder()
            .url(uploadUrl)
            .addHeader("X-Assembly-Id", assembly.assemblyId)
            .addHeader("Content-Type", mimeType)
            .put(requestBody)

        // Add API key header if available (matching iOS behavior)
        if (apiKey != null) {
            requestBuilder.addHeader("x-api-key", apiKey)
        }

        val request = requestBuilder.build()

        // Update photo status to uploading
        updatePhotoStatus(photo.photoId, PhotoStatus.UPLOADING, null)

        // Execute upload with automatic resource management
        okHttpClient.newCall(request).execute().use { response ->
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
        }
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
                onAssemblyCompleted(assemblyId, success = true, errorMessage = null)

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
                val errorMessage = "$failedCount photos failed to upload"
                updateAssemblyStatus(assemblyId, AssemblyStatus.FAILED, errorMessage)
                Log.e(TAG, "❌ Assembly $assemblyId failed: $errorMessage")

                // Calculate exponential backoff for retry
                val assembly = dao.getAssembly(assemblyId)
                if (assembly != null) {
                    val nextTimeout = calculateNextRetryTimeout(assembly.retryCount)
                    val nextRetryAt = System.currentTimeMillis() + (nextTimeout * 1000L)

                    val updated = assembly.copy(
                        failsCount = assembly.failsCount + 1,
                        retryCount = assembly.retryCount + 1,
                        nextRetryAt = nextRetryAt,
                        lastTimeout = nextTimeout
                    )
                    dao.updateAssembly(updated)

                    if (updated.failsCount < MAX_RETRY_ATTEMPTS) {
                        scheduleOneOffRetry(nextTimeout.toLong())
                    }
                }

                onAssemblyCompleted(assemblyId, success = false, errorMessage = errorMessage)
            }
            else -> {
                // Still uploading
                Log.d(TAG, "⏳ Assembly $assemblyId still uploading: $completedCount/$totalCount")
            }
        }
    }

    private suspend fun onAssemblyCompleted(assemblyId: String, success: Boolean, errorMessage: String?) {
        Log.d(TAG, "🏁 Assembly $assemblyId completed (success=$success)")

        // Only clear upload data on terminal success (keep for retries on failure)
        if (success) {
            withContext(Dispatchers.IO) {
                uploadStore.remove(assemblyId)

                // Delete temp files after successful upload
                val photos = dao.getPhotosByAssemblyUuid(assemblyId)
                photos.forEach { photo ->
                    photo.localFilePath?.let { path ->
                        try {
                            val uri = URI(path)
                            val file = File(uri.path ?: return@let)
                            if (file.exists() && file.delete()) {
                                Log.d(TAG, "🗑️ Deleted temp file: ${file.name}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete temp file: ${photo.fileName}", e)
                        }
                    }
                }
            }
            Log.d(TAG, "🗑️ Upload data and temp files cleaned for assembly $assemblyId")
        } else {
            Log.d(TAG, "💾 Upload data and temp files preserved for retry - assembly $assemblyId")
        }

        // Unlock queue
        isProcessingQueue.set(false)
        Log.d(TAG, "🔓 Queue unlocked")

        remoteLogger?.log(
            level = if (success) LogLevel.INFO else LogLevel.ERROR,
            tag = TAG,
            message = if (success) "Assembly completed successfully" else "Assembly failed: $errorMessage",
            metadata = mapOf(
                "assembly_id" to assemblyId,
                "success" to success.toString(),
                "error" to (errorMessage ?: "none")
            )
        )

        // Process next queued assembly
        processNextQueuedAssembly()
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
            errorMessage = errorMessage,
            isWaitingForConnectivity = (status == AssemblyStatus.WAITING_FOR_CONNECTIVITY)
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

    private fun determineMimeType(localPath: String): String {
        val uri = Uri.parse(localPath)
        val resolved = context.contentResolver.getType(uri)
        if (!resolved.isNullOrBlank()) {
            return resolved
        }
        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
    }

    private fun buildRequestBody(localPath: String, mimeType: String): okhttp3.RequestBody {
        val uri = Uri.parse(localPath)
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            null, "file" -> {
                val file = File(uri.path ?: throw IllegalStateException("Invalid file path"))
                if (!file.exists()) {
                    throw IllegalStateException("Photo file not found: ${file.absolutePath} (may have been deleted)")
                }
                file.asRequestBody(mimeType.toMediaType())
            }
            "content" -> {
                object : okhttp3.RequestBody() {
                    override fun contentType() = mimeType.toMediaTypeOrNull()
                    override fun writeTo(sink: BufferedSink) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            sink.writeAll(input.source())
                        } ?: throw IllegalStateException("Unable to read photo content")
                    }
                }
            }
            else -> throw IllegalStateException("Unsupported URI scheme for photo: ${uri.scheme}")
        }
    }

    private fun calculateNextRetryTimeout(retryCount: Int): Int {
        // Exponential backoff: 10s, 20s, 40s, 80s, ... capped at 30 minutes
        val timeout = INITIAL_RETRY_TIMEOUT_SECONDS * (1 shl retryCount)
        return timeout.coerceAtMost(MAX_RETRY_TIMEOUT_SECONDS)
    }

    /**
     * Schedule a one-off retry work with the computed backoff delay so we don't
     * wait for the 15-minute periodic worker. Replaces any pending one-off to
     * ensure the soonest run wins.
     */
    private fun scheduleOneOffRetry(delaySeconds: Long) {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<ImageProcessorRetryWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                INITIAL_RETRY_TIMEOUT_SECONDS.toLong(),
                TimeUnit.SECONDS
            )
            .build()

        workManager.enqueueUniqueWork(
            ONE_OFF_RETRY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )

        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Scheduled one-off retry",
            metadata = mapOf(
                "delay_seconds" to delaySeconds.toString(),
                "work" to ONE_OFF_RETRY_WORK_NAME
            )
        )
    }

    private suspend fun resetFailedPhotosToPending(assemblyId: String) {
        val photos = dao.getPhotosByAssemblyUuid(assemblyId)
        val failedPhotos = photos.filter { it.status == PhotoStatus.FAILED.value }

        for (photo in failedPhotos) {
            val updated = photo.copy(
                status = PhotoStatus.PENDING.value,
                lastUpdatedAt = System.currentTimeMillis(),
                errorMessage = null
            )
            dao.updatePhoto(updated)
        }

        if (failedPhotos.isNotEmpty()) {
            Log.d(TAG, "🔄 Reset ${failedPhotos.size} failed photos to PENDING for assembly $assemblyId")
        }
    }
}
