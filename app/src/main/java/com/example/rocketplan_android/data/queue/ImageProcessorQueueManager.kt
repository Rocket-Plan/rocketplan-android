package com.example.rocketplan_android.data.queue

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.rocketplan_android.data.api.ImageProcessorApi
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.dao.OfflineDao
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.data.model.ImageProcessorAssemblyRequest
import com.example.rocketplan_android.data.model.ImageProcessorStatusSnapshot
import com.example.rocketplan_android.data.repository.ImageProcessingConfigurationRepository
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.StoredUploadData
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.realtime.ImageProcessorRealtimeManager
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
    private val api: ImageProcessorApi,
    private val configRepository: ImageProcessingConfigurationRepository,
    private val secureStorage: SecureStorage,
    private val remoteLogger: RemoteLogger?,
    private val realtimeManager: ImageProcessorRealtimeManager? = null
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
     * Callback invoked when an assembly completes successfully.
     * Used to trigger photo sync for the room after upload.
     */
    var onAssemblyUploadCompleted: (suspend (projectId: Long, roomId: Long) -> Unit)? = null

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

            val promoted = registerWaitingAssemblies()
            if (promoted) {
                Log.d(TAG, "🚚 Promoted waiting assemblies during recovery")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error recovering stranded assemblies", e)
        }
    }

    private suspend fun registerWaitingAssemblies(): Boolean {
        val waitingAssemblies = dao.getAssembliesByStatus(listOf(AssemblyStatus.WAITING_FOR_ROOM.value))
        if (waitingAssemblies.isEmpty()) return false

        var promoted = false

        for (assembly in waitingAssemblies) {
            val projectServerId = offlineDao.getProject(assembly.projectId)?.serverId
            if (projectServerId == null) {
                Log.d(TAG, "⏳ Assembly ${assembly.assemblyId} waiting for project sync")
                updateAssemblyStatus(
                    assembly.assemblyId,
                    AssemblyStatus.WAITING_FOR_ROOM,
                    "Project is not synced yet"
                )
                continue
            }

            val roomServerId = resolveServerRoomId(assembly.roomId)
            if (assembly.roomId != null && roomServerId == null) {
                Log.d(TAG, "⏳ Assembly ${assembly.assemblyId} waiting for room sync (roomId=${assembly.roomId})")
                updateAssemblyStatus(
                    assembly.assemblyId,
                    AssemblyStatus.WAITING_FOR_ROOM,
                    "Room is not synced yet"
                )
                continue
            }

            if (roomServerId != null && assembly.roomId != roomServerId) {
                updateAssemblyRoomId(assembly.assemblyId, roomServerId)
            }

            val uploadData = uploadStore.read(assembly.assemblyId)
            if (uploadData == null) {
                Log.e(TAG, "❌ Upload data missing for waiting assembly ${assembly.assemblyId}")
                updateAssemblyStatus(
                    assembly.assemblyId,
                    AssemblyStatus.WAITING_FOR_ROOM,
                    "Upload data missing for retry"
                )
                continue
            }

            val resolvedUploadData = ensureUploadConfig(assembly.assemblyId, uploadData)
            if (resolvedUploadData == null) {
                updateAssemblyStatus(
                    assembly.assemblyId,
                    AssemblyStatus.WAITING_FOR_ROOM,
                    "Image processor config unavailable"
                )
                continue
            }

            val photos = dao.getPhotosByAssemblyUuid(assembly.assemblyId)
            val request = ImageProcessorAssemblyRequest(
                assemblyId = assembly.assemblyId,
                totalFiles = assembly.totalFiles,
                roomId = roomServerId,
                projectId = projectServerId,
                groupUuid = assembly.groupUuid,
                bytesReceived = assembly.bytesReceived,
                photoNames = photos.map { it.fileName },
                albums = resolvedUploadData.albums,
                irPhotos = resolvedUploadData.irPhotos ?: emptyList(),
                order = resolvedUploadData.order,
                notes = resolvedUploadData.notes,
                entityType = resolvedUploadData.entityType,
                entityId = resolvedUploadData.entityId
            )

            updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.CREATING, null)

            val response = runCatching {
                if (roomServerId != null) {
                    api.createRoomAssembly(roomServerId, request)
                } else {
                    api.createEntityAssembly(request)
                }
            }.getOrElse { error ->
                Log.e(TAG, "❌ Failed to register waiting assembly ${assembly.assemblyId}", error)
                remoteLogger?.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Failed to register waiting assembly",
                    metadata = mapOf(
                        "assembly_id" to assembly.assemblyId,
                        "reason" to (error.message ?: "unknown")
                    )
                )
                null
            } ?: continue

            val body = response.body()
            if (response.isSuccessful && body?.success == true) {
                updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.CREATED, null)
                realtimeManager?.trackAssembly(assembly.assemblyId)
                Log.d(
                    TAG,
                    "✅ Registered waiting assembly ${assembly.assemblyId} (roomId=${roomServerId ?: "none"})"
                )
                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Registered waiting assembly after room sync",
                    metadata = mapOf(
                        "assembly_id" to assembly.assemblyId,
                        "project_id_server" to projectServerId.toString(),
                        "room_id_server" to (roomServerId?.toString() ?: "null")
                    )
                )
                promoted = true
            } else {
                val errorMessage = body?.message ?: response.errorBody()?.string() ?: "Unknown error"
                updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.WAITING_FOR_ROOM, errorMessage)
                Log.w(
                    TAG,
                    "⚠️ Waiting assembly ${assembly.assemblyId} still pending: $errorMessage (code=${response.code()})"
                )
            }
        }

        return promoted
    }

    private suspend fun ensureUploadConfig(
        assemblyId: String,
        uploadData: StoredUploadData
    ): StoredUploadData? {
        if (uploadData.processingUrl.isNotBlank()) return uploadData

        val config = configRepository.getConfiguration().getOrElse { error ->
            Log.w(TAG, "WARN: Missing image processor config for assembly $assemblyId", error)
            remoteLogger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Missing image processor config for assembly",
                metadata = mapOf(
                    "assembly_id" to assemblyId,
                    "reason" to (error.message ?: "unknown")
                )
            )
            return null
        }

        val processingUrl = config.url.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "WARN: Image processor config has empty URL for assembly $assemblyId")
                remoteLogger?.log(
                    level = LogLevel.WARN,
                    tag = TAG,
                    message = "Image processor config missing URL",
                    metadata = mapOf("assembly_id" to assemblyId)
                )
                return null
            }

        val updated = uploadData.copy(
            processingUrl = processingUrl,
            apiKey = config.apiKey
        )
        uploadStore.write(assemblyId, updated)
        return updated
    }

    /**
     * Retry creating a QUEUED assembly on the server.
     * Called when an assembly's initial creation failed due to network issues.
     * Returns true if creation succeeded, false if it failed (assembly stays QUEUED).
     */
    private suspend fun retryAssemblyCreation(
        assembly: ImageProcessorAssemblyEntity,
        uploadData: StoredUploadData,
        photos: List<ImageProcessorPhotoEntity>
    ): Boolean {
        Log.d(TAG, "🔄 Retrying assembly creation for ${assembly.assemblyId}")

        val projectServerId = offlineDao.getProject(assembly.projectId)?.serverId
        if (projectServerId == null) {
            Log.w(TAG, "⏳ Project not synced for assembly ${assembly.assemblyId}")
            return false
        }

        val roomServerId = assembly.roomId?.let { resolveServerRoomId(it) }
        if (assembly.roomId != null && roomServerId == null) {
            Log.w(TAG, "⏳ Room not synced for assembly ${assembly.assemblyId}")
            return false
        }

        val request = ImageProcessorAssemblyRequest(
            assemblyId = assembly.assemblyId,
            totalFiles = assembly.totalFiles,
            roomId = roomServerId,
            projectId = projectServerId,
            groupUuid = assembly.groupUuid,
            bytesReceived = assembly.bytesReceived,
            photoNames = photos.map { it.fileName },
            albums = uploadData.albums,
            irPhotos = uploadData.irPhotos ?: emptyList(),
            order = uploadData.order,
            notes = uploadData.notes,
            entityType = uploadData.entityType,
            entityId = uploadData.entityId
        )

        updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.CREATING, null)

        val response = runCatching {
            if (roomServerId != null) {
                api.createRoomAssembly(roomServerId, request)
            } else {
                api.createEntityAssembly(request)
            }
        }.getOrElse { error ->
            val isNetworkError = error is java.net.UnknownHostException ||
                error is java.net.SocketTimeoutException ||
                error is java.net.ConnectException ||
                (error is java.io.IOException && error.message?.contains("network", ignoreCase = true) == true)

            if (isNetworkError) {
                // Keep as QUEUED for retry when network returns
                Log.d(TAG, "⏸️ Assembly ${assembly.assemblyId} creation failed (no network), keeping QUEUED")
                updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.QUEUED, "Network unavailable")
            } else {
                Log.e(TAG, "❌ Assembly ${assembly.assemblyId} creation failed", error)
                updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.FAILED, error.message)
            }
            remoteLogger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Assembly creation retry failed",
                metadata = mapOf(
                    "assembly_id" to assembly.assemblyId,
                    "is_network_error" to isNetworkError.toString(),
                    "error" to (error.message ?: "unknown")
                )
            )
            return false
        }

        val body = response.body()
        if (response.isSuccessful && body?.success == true) {
            updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.CREATED, null)
            realtimeManager?.trackAssembly(assembly.assemblyId)
            Log.d(TAG, "✅ Assembly ${assembly.assemblyId} creation retry succeeded")
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Assembly creation retry succeeded",
                metadata = mapOf(
                    "assembly_id" to assembly.assemblyId,
                    "project_id_server" to projectServerId.toString(),
                    "room_id_server" to (roomServerId?.toString() ?: "null")
                )
            )
            return true
        } else {
            val errorMessage = body?.message ?: response.errorBody()?.string() ?: "Unknown error"
            // Non-network error - mark as FAILED
            updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.FAILED, errorMessage)
            Log.w(TAG, "❌ Assembly ${assembly.assemblyId} creation retry failed: $errorMessage")
            remoteLogger?.log(
                level = LogLevel.ERROR,
                tag = TAG,
                message = "Assembly creation retry failed with server error",
                metadata = mapOf(
                    "assembly_id" to assembly.assemblyId,
                    "error" to errorMessage,
                    "code" to response.code().toString()
                )
            )
            return false
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

                    val promoted = registerWaitingAssemblies()
                    if (promoted) {
                        Log.d(TAG, "🚚 Promoted waiting assemblies after room sync")
                    }

                    // Get next assembly ready for upload:
                    // - CREATED: backend POST succeeded, ready for photo upload
                    // - QUEUED: backend POST failed due to network, needs retry
                    val createdAssemblies = dao.getAssembliesByStatus(listOf(
                        AssemblyStatus.CREATED.value,
                        AssemblyStatus.QUEUED.value
                    ))
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

                val promoted = registerWaitingAssemblies()
                if (promoted) {
                    Log.d(TAG, "🚚 Promoted waiting assemblies during retry processing")
                    processNextQueuedAssembly()
                }

                val currentTime = System.currentTimeMillis()
                val retryableAssemblies = dao.getRetryableAssemblies(
                    retryableStatuses = listOf(
                        AssemblyStatus.FAILED.value,
                        AssemblyStatus.WAITING_FOR_CONNECTIVITY.value,
                        AssemblyStatus.QUEUED.value  // QUEUED = network failed during creation
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

    suspend fun retryAssembly(assemblyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val assembly = dao.getAssembly(assemblyId)
            ?: return@withContext Result.failure(IllegalArgumentException("Assembly not found"))

        val status = AssemblyStatus.fromValue(assembly.status)
        if (status == AssemblyStatus.WAITING_FOR_ROOM) {
            val promoted = registerWaitingAssemblies()
            val refreshed = dao.getAssembly(assemblyId)
            return@withContext if (promoted && refreshed?.status == AssemblyStatus.CREATED.value) {
                processNextQueuedAssembly()
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Room or project is not synced yet; please sync and retry"))
            }
        }
        val retryableStatuses = setOf(
            AssemblyStatus.FAILED,
            AssemblyStatus.WAITING_FOR_CONNECTIVITY,
            AssemblyStatus.CANCELLED
        )

        if (status !in retryableStatuses) {
            val message = "Assembly is already in progress or complete"
            Log.d(TAG, "ℹ️ Manual retry skipped for $assemblyId (status=${assembly.status})")
            return@withContext Result.failure(IllegalStateException(message))
        }

        val uploadData = uploadStore.read(assemblyId)
        if (uploadData == null) {
            val message = "Upload data missing - cannot retry this assembly"
            updateAssemblyStatus(assemblyId, AssemblyStatus.FAILED, message)
            return@withContext Result.failure(IllegalStateException(message))
        }

        resetFailedPhotosToPending(assemblyId)

        val resetAssembly = assembly.copy(
            failsCount = 0,
            retryCount = 0,
            nextRetryAt = null,
            lastTimeout = 0,
            errorMessage = null,
            isWaitingForConnectivity = false
        )
        dao.updateAssembly(resetAssembly)

        updateAssemblyStatus(assemblyId, AssemblyStatus.RETRYING, null)
        updateAssemblyStatus(assemblyId, AssemblyStatus.CREATED, null)

        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Manual retry requested",
            metadata = mapOf(
                "assembly_id" to assemblyId,
                "status" to assembly.status
            )
        )

        processNextQueuedAssembly()
        Result.success(Unit)
    }

    suspend fun reconcileAssemblyStatus(assemblyId: String): Result<AssemblyStatus?> =
        reconcileAssemblyStatusInternal(assemblyId, source = "manual")

    fun reconcileProcessingAssemblies(source: String = "foreground") {
        scope.launch {
            runCatching { reconcileProcessingAssembliesInternal(source) }
                .onFailure { error ->
                    Log.e(TAG, "❌ Failed to reconcile processing assemblies (source=$source)", error)
                    remoteLogger?.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "Failed to reconcile processing assemblies",
                        metadata = mapOf(
                            "source" to source,
                            "error" to (error.message ?: "unknown")
                        )
                    )
                }
        }
    }

    private suspend fun reconcileProcessingAssembliesInternal(source: String) {
        val assemblies = dao.getAssembliesByStatus(listOf(AssemblyStatus.PROCESSING.value))
        if (assemblies.isEmpty()) {
            Log.d(TAG, "✅ No processing assemblies to reconcile (source=$source)")
            return
        }

        Log.d(TAG, "🔎 Reconciling ${assemblies.size} processing assemblies (source=$source)")
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Reconciling processing assemblies",
            metadata = mapOf(
                "source" to source,
                "count" to assemblies.size.toString()
            )
        )

        assemblies.forEach { reconcileAssemblyStatusInternal(it.assemblyId, source) }
    }

    private suspend fun reconcileAssemblyStatusInternal(
        assemblyId: String,
        source: String
    ): Result<AssemblyStatus?> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔎 Reconciling assembly $assemblyId (source=$source)")
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Assembly reconciliation started",
            metadata = mapOf(
                "assembly_id" to assemblyId,
                "source" to source
            )
        )

        runCatching {
            val assembly = dao.getAssembly(assemblyId)
                ?: throw IllegalArgumentException("Assembly not found")
            val photos = dao.getPhotosByAssemblyUuid(assemblyId)
            val snapshot = fetchBackendStatus(assembly)
                ?: throw IllegalStateException("No status from backend")

            val completedCount = snapshot.completedFiles ?: 0
            val isComplete = snapshot.isComplete == true ||
                (assembly.totalFiles > 0 && completedCount >= assembly.totalFiles)

            val mappedStatus = snapshot.status?.let { AssemblyStatus.fromValue(it) }

            if (isComplete) {
                val now = System.currentTimeMillis()
                photos.forEach { photo ->
                    if (photo.status != PhotoStatus.COMPLETED.value) {
                        val updated = photo.copy(
                            status = PhotoStatus.COMPLETED.value,
                            lastUpdatedAt = now,
                            errorMessage = null
                        )
                        dao.updatePhoto(updated)
                    }
                }

                updateAssemblyStatus(assemblyId, AssemblyStatus.COMPLETED, null)

                Log.d(TAG, "✅ Assembly $assemblyId reconciled as complete (source=$source)")
                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "Assembly reconciliation marked assembly complete",
                    metadata = mapOf(
                        "assembly_id" to assemblyId,
                        "source" to source,
                        "server_status" to (snapshot.status ?: "unknown"),
                        "completed_files" to completedCount.toString(),
                        "total_files" to assembly.totalFiles.toString()
                    )
                )

                onAssemblyCompleted(assemblyId, success = true, errorMessage = null)
                return@runCatching AssemblyStatus.COMPLETED
            }

            val targetStatus = mappedStatus ?: AssemblyStatus.PROCESSING
            updateAssemblyStatus(assemblyId, targetStatus, assembly.errorMessage)

            Log.d(TAG, "ℹ️ Assembly $assemblyId reconciliation finished (source=$source, status=${targetStatus.value})")
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Assembly reconciliation checked assembly status",
                metadata = mapOf(
                    "assembly_id" to assemblyId,
                    "source" to source,
                    "server_status" to (snapshot.status ?: "unknown"),
                    "completed_files" to completedCount.toString(),
                    "remaining_files" to (snapshot.remainingFiles ?: -1).toString(),
                    "is_complete" to (snapshot.isComplete ?: false).toString()
                )
            )

            targetStatus
        }.onFailure { error ->
            Log.e(TAG, "❌ Assembly reconciliation failed for $assemblyId (source=$source)", error)
            remoteLogger?.log(
                level = LogLevel.ERROR,
                tag = TAG,
                message = "Assembly reconciliation failed",
                metadata = mapOf(
                    "assembly_id" to assemblyId,
                    "source" to source,
                    "error" to (error.message ?: "unknown")
                )
            )
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

            val resolvedUploadData = ensureUploadConfig(assembly.assemblyId, uploadData)
            if (resolvedUploadData == null) {
                val errorMessage = "Image processor config unavailable"
                updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.WAITING_FOR_CONNECTIVITY, errorMessage)
                onAssemblyCompleted(assembly.assemblyId, success = false, errorMessage = errorMessage)
                return
            }

            // Get photos for this assembly
            val photos = dao.getPhotosByAssemblyUuid(assembly.assemblyId)

            Log.d(
                TAG,
                "🌐 Upload target: ${resolvedUploadData.processingUrl} (apiKey=${resolvedUploadData.apiKey ?: "missing"})"
            )

            if (resolvedUploadData.apiKey.isNullOrBlank()) {
                Log.e(TAG, "❌ Missing image processor API key; uploads will omit x-api-key header")
                remoteLogger?.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Missing API key for image processor upload",
                    metadata = mapOf(
                        "assembly_id" to assembly.assemblyId,
                        "processing_url" to resolvedUploadData.processingUrl
                    )
                )
            }

            // If assembly is QUEUED, the backend POST failed - retry it now
            if (assembly.status == AssemblyStatus.QUEUED.value) {
                val retryResult = retryAssemblyCreation(assembly, resolvedUploadData, photos)
                if (!retryResult) {
                    // Retry failed, keep as QUEUED for next attempt
                    Log.d(TAG, "⏸️ Assembly ${assembly.assemblyId} creation retry failed, will retry later")
                    onAssemblyCompleted(assembly.assemblyId, success = false, errorMessage = "Assembly creation failed")
                    return
                }
            }

            // If the backend already marks this assembly complete, skip re-uploads
            if (reconcileWithBackendStatus(assembly, photos)) {
                return
            }

            // Assembly is now CREATED (backend POST done) - proceed with photo uploads
            updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.UPLOADING, null)

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
                    uploadPhoto(assembly, photo, resolvedUploadData.processingUrl, resolvedUploadData.apiKey)
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
        val uploadUrl = processingUrl.toHttpUrl()
            .newBuilder()
            .addQueryParameter("filename", photo.fileName)
            .build()

        // Build request with headers (matching iOS)
        val requestBuilder = Request.Builder()
            .url(uploadUrl)
            .addHeader("X-Assembly-Id", assembly.assemblyId)
            .addHeader("Content-Type", mimeType)
            .post(requestBody)

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

    private suspend fun reconcileWithBackendStatus(
        assembly: ImageProcessorAssemblyEntity,
        photos: List<ImageProcessorPhotoEntity>
    ): Boolean {
        val status = fetchBackendStatus(assembly) ?: return false
        val completedCount = status.completedFiles ?: 0
        val isComplete = status.isComplete == true ||
            (assembly.totalFiles > 0 && completedCount >= assembly.totalFiles)

        if (!isComplete) return false

        val now = System.currentTimeMillis()
        photos.forEach { photo ->
            if (photo.status != PhotoStatus.COMPLETED.value) {
                val updated = photo.copy(
                    status = PhotoStatus.COMPLETED.value,
                    lastUpdatedAt = now,
                    errorMessage = null
                )
                dao.updatePhoto(updated)
            }
        }

        updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.COMPLETED, null)

        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Assembly already complete on backend, skipping uploads",
            metadata = mapOf(
                "assembly_id" to assembly.assemblyId,
                "completed_files" to completedCount.toString(),
                "total_files" to assembly.totalFiles.toString(),
                "status" to (status.status ?: "unknown")
            )
        )

        onAssemblyCompleted(assembly.assemblyId, success = true, errorMessage = null)
        return true
    }

    private suspend fun fetchBackendStatus(
        assembly: ImageProcessorAssemblyEntity
    ): ImageProcessorStatusSnapshot? {
        return runCatching {
            val serverRoomId = resolveServerRoomId(assembly.roomId)

            val response = when {
                serverRoomId != null -> api.getRoomAssemblyStatus(serverRoomId, assembly.assemblyId)
                else -> api.getAssemblyStatus(assembly.assemblyId)
            }

            if (!response.isSuccessful) {
                remoteLogger?.log(
                    level = LogLevel.WARN,
                    tag = TAG,
                    message = "Failed to fetch assembly status",
                    metadata = mapOf(
                        "assembly_id" to assembly.assemblyId,
                        "code" to response.code().toString()
                    )
                )
                return@runCatching null
            }

            response.body()?.toSnapshot()
        }.getOrElse { error ->
            remoteLogger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Error fetching assembly status",
                metadata = mapOf(
                    "assembly_id" to assembly.assemblyId,
                    "error" to (error.message ?: "unknown")
                )
            )
            null
        }
    }

    private suspend fun resolveServerRoomId(roomId: Long?): Long? {
        if (roomId == null) return null
        val localRoom = offlineDao.getRoom(roomId)
        return localRoom?.serverId?.takeIf { it > 0 } ?: roomId.takeIf { it > 0 }
    }

    private suspend fun updateAssemblyRoomId(assemblyId: String, roomServerId: Long) {
        val existing = dao.getAssembly(assemblyId) ?: return
        if (existing.roomId == roomServerId) return
        val updated = existing.copy(
            roomId = roomServerId,
            lastUpdatedAt = System.currentTimeMillis()
        )
        dao.updateAssembly(updated)
    }

    private suspend fun checkIfAssemblyComplete(assemblyId: String) {
        val photos = dao.getPhotosByAssemblyUuid(assemblyId)
        val completedCount = photos.count { it.status == PhotoStatus.COMPLETED.value }
        val failedCount = photos.count { it.status == PhotoStatus.FAILED.value }
        val totalCount = photos.size

        Log.d(TAG, "📊 Assembly $assemblyId: $completedCount/$totalCount completed, $failedCount failed")

        val currentAssembly = dao.getAssembly(assemblyId)
        val alreadyCompleted = currentAssembly?.status == AssemblyStatus.COMPLETED.value

        when {
            completedCount == totalCount -> {
                // All photos uploaded successfully
                if (alreadyCompleted) {
                    Log.d(TAG, "✅ Assembly $assemblyId upload complete, already marked completed by Pusher")
                } else {
                    updateAssemblyStatus(assemblyId, AssemblyStatus.PROCESSING, null)
                    Log.d(TAG, "✅ Assembly $assemblyId upload complete, now processing")
                }
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
            // Get assembly info before cleanup for photo sync trigger
            val assembly = dao.getAssembly(assemblyId)
            val projectId = assembly?.projectId
            val roomId = assembly?.roomId

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

            // Trigger photo sync for the room to update UI
            if (projectId != null && roomId != null) {
                Log.d(TAG, "🔄 Triggering photo sync for room $roomId (project $projectId)")
                try {
                    onAssemblyUploadCompleted?.invoke(projectId, roomId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to trigger photo sync callback", e)
                }
            }
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

    private fun maskApiKey(apiKey: String?): String =
        when {
            apiKey.isNullOrBlank() -> "missing"
            apiKey.length <= 4 -> apiKey
            else -> "****${apiKey.takeLast(4)}"
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
