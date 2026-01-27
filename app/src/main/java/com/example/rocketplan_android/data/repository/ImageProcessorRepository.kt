package com.example.rocketplan_android.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.rocketplan_android.data.api.ImageProcessorApi
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.dao.OfflineDao
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.data.local.model.ImageProcessorAssemblyWithDetails
import com.example.rocketplan_android.data.model.FileToUpload
import com.example.rocketplan_android.data.model.IRPhotoData
import com.example.rocketplan_android.data.model.ImageProcessorAssemblyRequest
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.StoredUploadData
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.realtime.ImageProcessorRealtimeManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import com.example.rocketplan_android.util.UuidUtils
import java.io.FileOutputStream
import java.util.Locale

class ImageProcessorRepository(
    private val context: Context,
    private val api: ImageProcessorApi,
    private val dao: ImageProcessorDao,
    private val offlineDao: OfflineDao,
    private val uploadStore: ImageProcessorUploadStore,
    private val configurationRepository: ImageProcessingConfigurationRepository,
    private val secureStorage: SecureStorage,
    private val remoteLogger: RemoteLogger?,
    private val realtimeManager: ImageProcessorRealtimeManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "ImageProcessorRepository"
        private const val MAX_PHOTO_UPLOAD_BYTES = 6L * 1024L * 1024L
        private const val JPEG_QUALITY_START = 95
        private const val JPEG_QUALITY_MIN = 45
        private const val JPEG_QUALITY_STEP = 5
        private const val BITMAP_SCALE_FACTOR = 0.85f
        private const val MAX_COMPRESSION_PASSES = 20
    }

    suspend fun createAssembly(
        roomId: Long?,
        projectId: Long,
        filesToUpload: List<FileToUpload>,
        templateId: String,
        groupUuid: String = UuidUtils.generateUuidV7(),
        albums: Map<String, List<String>> = emptyMap(),
        irPhotos: List<Map<String, IRPhotoData>> = emptyList(),
        order: List<String> = emptyList(),
        notes: Map<String, List<String>> = emptyMap(),
        entityType: String? = null,
        entityId: Long? = null
    ): Result<String> = withContext(ioDispatcher) {
        if (filesToUpload.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No files to upload"))
        }

        val preparedFiles = ensureFilesWithinUploadLimit(filesToUpload).getOrElse { error ->
            return@withContext Result.failure(error)
        }

        val project = offlineDao.getProject(projectId)
            ?: return@withContext logFailure(
                message = "Project not found for image processor assembly",
                metadata = mapOf("project_id_local" to projectId.toString())
            )
        val projectServerId = project.serverId
        val waitingForProjectSync = projectServerId == null

        val roomServerId = if (roomId != null) {
            val room = offlineDao.getRoom(roomId)
                ?: return@withContext logFailure(
                    message = "Room not found for image processor assembly",
                    metadata = mapOf(
                        "room_id_local" to roomId.toString(),
                        "project_id_local" to projectId.toString()
                    )
                )
            room.serverId?.takeIf { it > 0 }
        } else {
            null
        }
        // Don't block on room sync - photos can upload to project level and be associated later
        val waitingForRoomSync = false // Previously: roomId != null && roomServerId == null
        val waitingForSync = waitingForProjectSync // Room sync no longer blocks
        val resolvedRoomId = roomServerId ?: roomId

        val config = if (waitingForSync) {
            configurationRepository.getCachedConfiguration()
        } else {
            configurationRepository.getConfiguration()
                .getOrElse { error ->
                    remoteLogger?.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "Failed to load image processing configuration",
                        metadata = mapOf("reason" to (error.message ?: "unknown"))
                    )
                    return@withContext Result.failure(error)
                }
        }
        val processingUrl = config?.url?.takeIf { it.isNotBlank() } ?: ""
        val apiKey = config?.apiKey
        if (processingUrl.isBlank() && waitingForSync) {
            remoteLogger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Image processor config unavailable; deferring assembly until sync",
                metadata = mapOf(
                    "project_id_local" to projectId.toString(),
                    "room_id_local" to (roomId?.toString() ?: "null"),
                    "waiting_for_project" to waitingForProjectSync.toString(),
                    "waiting_for_room" to waitingForRoomSync.toString()
                )
            )
        }

        val assemblyId = UuidUtils.generateUuidV7().replace("-", "")
        val now = System.currentTimeMillis()
        val totalBytes = preparedFiles.sumOf { getFileSize(it.uri) }
        val status = if (waitingForSync) {
            AssemblyStatus.WAITING_FOR_ROOM.value
        } else {
            AssemblyStatus.QUEUED.value
        }

        val assemblyEntity = ImageProcessorAssemblyEntity(
            assemblyId = assemblyId,
            roomId = resolvedRoomId,
            projectId = projectId,
            groupUuid = groupUuid,
            status = status,
            totalFiles = preparedFiles.size,
            bytesReceived = totalBytes,
            createdAt = now,
            lastUpdatedAt = now,
            entityType = entityType,
            entityId = entityId
        )

        val localId = dao.insertAssembly(assemblyEntity)

        val photoEntities = preparedFiles.mapIndexed { index, file ->
            ImageProcessorPhotoEntity(
                photoId = UuidUtils.generateUuidV7(),
                assemblyLocalId = localId,
                assemblyUuid = assemblyId,
                fileName = file.filename,
                localFilePath = file.uri.toString(),
                status = PhotoStatus.PENDING.value,
                orderIndex = index,
                fileSize = getFileSize(file.uri),
                lastUpdatedAt = now
            )
        }
        dao.insertPhotos(photoEntities)

        val userId = secureStorage.getUserIdSync() ?: -1L

        uploadStore.write(
            assemblyId,
            StoredUploadData(
                processingUrl = processingUrl,
                apiKey = apiKey,
                templateId = templateId,
                projectId = projectId,
                roomId = resolvedRoomId,
                groupUuid = groupUuid,
                userId = userId,
                albums = albums,
                order = order,
                notes = notes,
                entityType = entityType,
                entityId = entityId,
                irPhotos = irPhotos
            )
        )

        if (waitingForSync) {
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Assembly deferred until sync completes",
                metadata = mapOf(
                    "assembly_id" to assemblyId,
                    "project_id_local" to projectId.toString(),
                    "room_id_local" to (roomId?.toString() ?: "null"),
                    "waiting_for_project" to waitingForProjectSync.toString(),
                    "waiting_for_room" to waitingForRoomSync.toString()
                )
            )
            return@withContext Result.success(assemblyId)
        }

        realtimeManager?.trackAssembly(assemblyId)

        updateAssemblyStatus(assemblyId, AssemblyStatus.CREATING)

        val request = ImageProcessorAssemblyRequest(
            assemblyId = assemblyId,
            totalFiles = preparedFiles.size,
            roomId = roomServerId,
            projectId = projectServerId,
            groupUuid = groupUuid,
            bytesReceived = totalBytes,
            photoNames = preparedFiles.map { it.filename },
            albums = albums,
            irPhotos = irPhotos,
            order = order,
            notes = notes,
            entityType = entityType,
            entityId = entityId
        )

        val apiResult = runCatching {
            val response = if (roomServerId != null) {
                api.createRoomAssembly(roomServerId, request)
            } else {
                api.createEntityAssembly(request)
            }

            val body = response.body()
            val telemetryData = mutableMapOf(
                "project_id_server" to projectServerId.toString(),
                "project_id_local" to projectId.toString(),
                "total_files" to preparedFiles.size.toString(),
                "bytes" to totalBytes.toString()
            )
            roomId?.let { telemetryData["room_id_local"] = it.toString() }
            telemetryData["room_id_server"] = roomServerId?.toString() ?: "entity"

            if (response.isSuccessful && body?.success == true) {
                updateAssemblyStatus(assemblyId, AssemblyStatus.CREATED)
                logLifecycle(
                    event = "assembly_created",
                    assemblyId = assemblyId,
                    metadata = telemetryData
                )
                assemblyId
            } else {
                val errorMessage = body?.message ?: response.errorBody()?.string() ?: "Unknown error"
                updateAssemblyStatus(assemblyId, AssemblyStatus.FAILED, errorMessage)
                logLifecycle(
                    event = "assembly_failed",
                    assemblyId = assemblyId,
                    metadata = telemetryData + mapOf(
                        "reason" to errorMessage,
                        "code" to response.code().toString()
                    )
                )
                throw IllegalStateException(errorMessage)
            }
        }

        return@withContext apiResult.fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                val errorMessage = error.message ?: "Network error during assembly creation"
                val isNetworkError = error is java.net.UnknownHostException ||
                    error is java.net.SocketTimeoutException ||
                    error is java.net.ConnectException ||
                    error is java.io.IOException && error.message?.contains("network", ignoreCase = true) == true

                if (isNetworkError) {
                    // Keep as QUEUED for retry when network returns - return success
                    updateAssemblyStatus(assemblyId, AssemblyStatus.QUEUED)
                    logLifecycle(
                        event = "assembly_deferred_offline",
                        assemblyId = assemblyId,
                        metadata = mapOf(
                            "error" to errorMessage,
                            "error_type" to error::class.simpleName.toString()
                        )
                    )
                    Result.success(assemblyId)
                } else {
                    updateAssemblyStatus(assemblyId, AssemblyStatus.FAILED, errorMessage)
                    logLifecycle(
                        event = "assembly_network_error",
                        assemblyId = assemblyId,
                        metadata = mapOf(
                            "error" to errorMessage,
                            "error_type" to error::class.simpleName.toString()
                        )
                    )
                    Result.failure(error)
                }
            }
        )
    }

    suspend fun updateAssemblyStatus(
        assemblyId: String,
        status: AssemblyStatus,
        errorMessage: String? = null
    ) = withContext(ioDispatcher) {
        val entity = dao.getAssembly(assemblyId) ?: return@withContext
        val updated = entity.copy(
            status = status.value,
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = errorMessage
        )
        dao.updateAssembly(updated)
    }

    fun observeAssembliesByRoom(roomId: Long): Flow<List<ImageProcessorAssemblyEntity>> =
        dao.observeAssembliesByRoom(roomId)

    fun observeAllAssemblies(): Flow<List<ImageProcessorAssemblyEntity>> =
        dao.observeAllAssemblies()

    fun observeAllAssembliesWithDetails(): Flow<List<ImageProcessorAssemblyWithDetails>> =
        dao.observeAllAssembliesWithDetails()

    fun observePhotosByAssemblyLocalId(assemblyLocalId: Long): Flow<List<ImageProcessorPhotoEntity>> =
        dao.observePhotosByAssemblyLocalId(assemblyLocalId)

    suspend fun deleteAssembly(assemblyId: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            dao.getAssembly(assemblyId)
                ?: throw IllegalStateException("Assembly not found")

            val photos = dao.getPhotosByAssemblyUuid(assemblyId)

            dao.deleteAssembly(assemblyId)
            uploadStore.remove(assemblyId)
            deleteLocalFiles(photos)

            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Deleted local image processor assembly",
                metadata = mapOf("assembly_id" to assemblyId)
            )
            Unit
        }
    }

    suspend fun deleteAllAssemblies(): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val assemblies = dao.getAllAssemblies()
            if (assemblies.isEmpty()) {
                uploadStore.clear()
                return@runCatching 0
            }

            val allPhotos = assemblies.flatMap { assembly ->
                dao.getPhotosByAssemblyUuid(assembly.assemblyId)
            }

            dao.deleteAllAssemblies()
            uploadStore.clear()
            deleteLocalFiles(allPhotos)

            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Deleted all local image processor assemblies",
                metadata = mapOf("count" to assemblies.size.toString())
            )

            assemblies.size
        }
    }

    suspend fun cleanupOldAssemblies(daysOld: Int = 7) = withContext(ioDispatcher) {
        val cutoff = System.currentTimeMillis() - daysOld * 24 * 60 * 60 * 1000L
        dao.deleteAssembliesByStatusOlderThan(
            status = AssemblyStatus.COMPLETED.value,
            cutoffMillis = cutoff
        )
    }

    suspend fun restoreStoredUploadData(assemblyId: String): StoredUploadData? =
        withContext(ioDispatcher) { uploadStore.read(assemblyId) }

    suspend fun removeStoredUploadData(assemblyId: String) = withContext(ioDispatcher) {
        uploadStore.remove(assemblyId)
    }

    suspend fun getPhotosForAssembly(assemblyUuid: String): List<ImageProcessorPhotoEntity> =
        withContext(ioDispatcher) {
            dao.getPhotosByAssemblyUuid(assemblyUuid)
        }

    private fun logFailure(
        message: String,
        metadata: Map<String, String> = emptyMap()
    ): Result<String> {
        remoteLogger?.log(
            level = LogLevel.ERROR,
            tag = TAG,
            message = message,
            metadata = metadata
        )
        return Result.failure(IllegalStateException(message))
    }

    private fun ensureFilesWithinUploadLimit(files: List<FileToUpload>): Result<List<FileToUpload>> {
        val processed = mutableListOf<FileToUpload>()
        val resizedFiles = mutableListOf<FileToUpload>()

        for (file in files) {
            val currentSize = getFileSize(file.uri)
            if (currentSize <= MAX_PHOTO_UPLOAD_BYTES) {
                processed += file
                continue
            }

            val resized = resizePhoto(file)
            if (resized == null) {
                cleanupTempFiles(resizedFiles)
                val message = "Unable to resize ${file.filename} under 6MB"
                remoteLogger?.log(
                    level = LogLevel.WARN,
                    tag = TAG,
                    message = message,
                    metadata = mapOf(
                        "filename" to file.filename,
                        "size_bytes" to currentSize.toString()
                    )
                )
                return Result.failure(IllegalStateException(message))
            }

            resizedFiles += resized
            val resizedSize = getFileSize(resized.uri)
            if (resizedSize > MAX_PHOTO_UPLOAD_BYTES) {
                cleanupTempFiles(resizedFiles)
                val message = "Unable to reduce ${file.filename} below 6MB after resizing"
                remoteLogger?.log(
                    level = LogLevel.WARN,
                    tag = TAG,
                    message = message,
                    metadata = mapOf(
                        "filename" to file.filename,
                        "size_bytes" to resizedSize.toString()
                    )
                )
                return Result.failure(IllegalStateException(message))
            }

            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Resized photo for image processor upload",
                metadata = mapOf(
                    "filename" to file.filename,
                    "original_bytes" to currentSize.toString(),
                    "resized_bytes" to resizedSize.toString()
                )
            )

            processed += resized
        }

        return Result.success(processed)
    }

    private fun cleanupTempFiles(files: List<FileToUpload>) {
        files.forEach { file ->
            if (!file.deleteOnCompletion) return@forEach
            if (file.uri.scheme != "file") return@forEach
            val path = file.uri.path ?: return@forEach
            runCatching { File(path).delete() }
        }
    }

    private fun deleteLocalFiles(photos: List<ImageProcessorPhotoEntity>) {
        photos.forEach { photo ->
            val rawPath = photo.localFilePath ?: return@forEach
            runCatching {
                val uri = Uri.parse(rawPath)
                when (uri.scheme?.lowercase(Locale.getDefault())) {
                    null, "", "file" -> {
                        val file = uri.path?.let { File(it) } ?: return@runCatching
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                    else -> context.contentResolver.delete(uri, null, null)
                }
            }
        }
    }

    private fun resizePhoto(file: FileToUpload): FileToUpload? {
        val bitmap = context.contentResolver.openInputStream(file.uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return null

        return try {
            val compressedBytes = compressBitmap(bitmap, MAX_PHOTO_UPLOAD_BYTES) ?: return null
            val tempFile = File.createTempFile("rp_image_processor_", ".jpg", context.cacheDir)
            FileOutputStream(tempFile).use { output -> output.write(compressedBytes) }
            file.copy(
                uri = Uri.fromFile(tempFile),
                deleteOnCompletion = true
            )
        } catch (error: Exception) {
            remoteLogger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Error while resizing photo for upload",
                metadata = mapOf(
                    "filename" to file.filename,
                    "reason" to (error.message ?: "unknown")
                )
            )
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun compressBitmap(bitmap: Bitmap, maxBytes: Long): ByteArray? {
        var currentBitmap = bitmap
        var quality = JPEG_QUALITY_START
        val outputStream = ByteArrayOutputStream()

        for (attempt in 0 until MAX_COMPRESSION_PASSES) {
            outputStream.reset()
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            if (outputStream.size() <= maxBytes) {
                val result = outputStream.toByteArray()
                if (currentBitmap !== bitmap) {
                    currentBitmap.recycle()
                }
                return result
            }

            if (quality > JPEG_QUALITY_MIN) {
                quality -= JPEG_QUALITY_STEP
            } else {
                val newWidth = (currentBitmap.width * BITMAP_SCALE_FACTOR).toInt().coerceAtLeast(1)
                val newHeight = (currentBitmap.height * BITMAP_SCALE_FACTOR).toInt().coerceAtLeast(1)
                if (newWidth == currentBitmap.width && newHeight == currentBitmap.height) {
                    break
                }
                val scaled = Bitmap.createScaledBitmap(currentBitmap, newWidth, newHeight, true)
                if (scaled !== currentBitmap && currentBitmap !== bitmap) {
                    currentBitmap.recycle()
                }
                currentBitmap = scaled
                quality = JPEG_QUALITY_START
            }
        }

        if (currentBitmap !== bitmap) {
            currentBitmap.recycle()
        }

        return null
    }

    private fun getFileSize(uri: Uri): Long {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
    }

    private fun logLifecycle(
        event: String,
        assemblyId: String,
        metadata: Map<String, String>
    ) {
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = event,
            metadata = metadata + ("assembly_id" to assemblyId)
        )
    }
}
