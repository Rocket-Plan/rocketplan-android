package com.example.rocketplan_android.data.repository

import android.content.Context
import android.net.Uri
import com.example.rocketplan_android.data.api.ImageProcessorApi
import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.dao.OfflineDao
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.data.model.FileToUpload
import com.example.rocketplan_android.data.model.IRPhotoData
import com.example.rocketplan_android.data.model.ImageProcessorAssemblyRequest
import com.example.rocketplan_android.data.storage.ImageProcessorUploadStore
import com.example.rocketplan_android.data.storage.StoredUploadData
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

class ImageProcessorRepository(
    private val context: Context,
    private val api: ImageProcessorApi,
    private val dao: ImageProcessorDao,
    private val offlineDao: OfflineDao,
    private val uploadStore: ImageProcessorUploadStore,
    private val configurationRepository: ImageProcessingConfigurationRepository,
    private val secureStorage: SecureStorage,
    private val remoteLogger: RemoteLogger?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "ImageProcessorRepository"
    }

    suspend fun createAssembly(
        roomId: Long?,
        projectId: Long,
        filesToUpload: List<FileToUpload>,
        templateId: String,
        groupUuid: String = UUID.randomUUID().toString(),
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

        val project = offlineDao.getProject(projectId)
            ?: return@withContext logFailure(
                message = "Project not found for image processor assembly",
                metadata = mapOf("project_id_local" to projectId.toString())
            )
        val projectServerId = project.serverId
            ?: return@withContext logFailure(
                message = "Project is not synced with the server",
                metadata = mapOf(
                    "project_id_local" to projectId.toString(),
                    "assembly_context" to "image_processor"
                )
            )

        val roomServerId = if (roomId != null) {
            val room = offlineDao.getRoom(roomId)
                ?: return@withContext logFailure(
                    message = "Room not found for image processor assembly",
                    metadata = mapOf(
                        "room_id_local" to roomId.toString(),
                        "project_id_local" to projectId.toString()
                    )
                )
            room.serverId
                ?: return@withContext logFailure(
                    message = "Room is not synced with the server",
                    metadata = mapOf(
                        "room_id_local" to roomId.toString(),
                        "project_id_local" to projectId.toString()
                    )
                )
        } else {
            null
        }

        val config = configurationRepository.getConfiguration()
            .getOrElse { error ->
                remoteLogger?.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Failed to load image processing configuration",
                    metadata = mapOf("reason" to (error.message ?: "unknown"))
                )
                return@withContext Result.failure(error)
            }

        val assemblyId = UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        val totalBytes = filesToUpload.sumOf { getFileSize(it.uri) }
        val status = AssemblyStatus.QUEUED.value

        val assemblyEntity = ImageProcessorAssemblyEntity(
            assemblyId = assemblyId,
            roomId = roomId,
            projectId = projectId,
            groupUuid = groupUuid,
            status = status,
            totalFiles = filesToUpload.size,
            bytesReceived = totalBytes,
            createdAt = now,
            lastUpdatedAt = now,
            entityType = entityType,
            entityId = entityId
        )

        val localId = dao.insertAssembly(assemblyEntity)

        val photoEntities = filesToUpload.mapIndexed { index, file ->
            ImageProcessorPhotoEntity(
                photoId = UUID.randomUUID().toString(),
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
                processingUrl = config.url,
                apiKey = config.apiKey,
                templateId = templateId,
                projectId = projectId,
                roomId = roomId,
                groupUuid = groupUuid,
                userId = userId,
                albums = albums,
                order = order,
                notes = notes,
                entityType = entityType,
                entityId = entityId
            )
        )

        updateAssemblyStatus(assemblyId, AssemblyStatus.CREATING)

        val request = ImageProcessorAssemblyRequest(
            assemblyId = assemblyId,
            totalFiles = filesToUpload.size,
            roomId = roomServerId,
            projectId = projectServerId,
            groupUuid = groupUuid,
            bytesReceived = totalBytes,
            photoNames = filesToUpload.map { it.filename },
            albums = albums,
            irPhotos = irPhotos,
            order = order,
            notes = notes,
            entityType = entityType,
            entityId = entityId
        )

        return@withContext runCatching {
            val response = if (roomServerId != null) {
                api.createRoomAssembly(roomServerId, request)
            } else {
                api.createEntityAssembly(request)
            }

            val body = response.body()
            val telemetryData = mutableMapOf(
                "project_id_server" to projectServerId.toString(),
                "project_id_local" to projectId.toString(),
                "total_files" to filesToUpload.size.toString(),
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
