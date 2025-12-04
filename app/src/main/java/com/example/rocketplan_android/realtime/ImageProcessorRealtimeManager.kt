package com.example.rocketplan_android.realtime

import com.example.rocketplan_android.data.local.dao.ImageProcessorDao
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ImageProcessorRealtimeManager(
    private val dao: ImageProcessorDao,
    private val pusherService: PusherService,
    private val remoteLogger: RemoteLogger?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val trackedAssemblies = ConcurrentHashMap.newKeySet<String>()
    private val terminalStates = setOf(
        AssemblyStatus.COMPLETED,
        AssemblyStatus.CANCELLED,
        AssemblyStatus.FAILED
    )
    private val assemblyResultType = object : TypeToken<AssemblyResultEnvelope>() {}.type

    init {
        scope.launch {
            dao.observeAllAssemblies().collectLatest { assemblies ->
                val activeIds = assemblies
                    .filter { assembly -> needsRealtimeTracking(assembly.status) }
                    .map { it.assemblyId }
                    .toSet()

                activeIds.forEach { ensureSubscribed(it) }

                val toRemove = trackedAssemblies.filterNot { it in activeIds }
                toRemove.forEach { stopTracking(it) }
            }
        }
    }

    fun trackAssembly(assemblyId: String) {
        scope.launch { ensureSubscribed(assemblyId) }
    }

    private suspend fun ensureSubscribed(assemblyId: String) {
        if (!trackedAssemblies.add(assemblyId)) return
        subscribeToAssembly(assemblyId)
    }

    private fun subscribeToAssembly(assemblyId: String) {
        val channelName = PusherConfig.channelNameForAssembly(assemblyId)

        pusherService.bindImageProcessorEvent(channelName, PusherConfig.PHOTO_EVENT) { update ->
            val payload = update ?: return@bindImageProcessorEvent
            if (payload.assemblyId.isNullOrBlank()) return@bindImageProcessorEvent
            scope.launch { handleUpdate(payload, isFinalEvent = false) }
        }

        pusherService.bindImageProcessorEvent(channelName, PusherConfig.ASSEMBLY_EVENT) { update ->
            val payload = update ?: return@bindImageProcessorEvent
            if (payload.assemblyId.isNullOrBlank()) return@bindImageProcessorEvent
            scope.launch { handleUpdate(payload, isFinalEvent = true) }
        }

        // Legacy fallback: some backends emit PhotoAssemblyUpdated for failures
        val legacyChannel = PusherConfig.legacyAssemblyChannel(assemblyId)
        pusherService.bindTypedEvent<AssemblyResultEnvelope>(
            channelName = legacyChannel,
            eventName = PusherConfig.PHOTO_ASSEMBLY_RESULT_EVENT,
            type = assemblyResultType
        ) { envelope: AssemblyResultEnvelope? ->
            val result = envelope?.assemblyResponse
            scope.launch { handleAssemblyResult(assemblyId, result) }
        }

        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Subscribed to image processor realtime updates",
            metadata = mapOf("assembly_id" to assemblyId, "channel" to channelName)
        )
    }

    private suspend fun handleUpdate(
        update: ImageProcessorUpdate,
        isFinalEvent: Boolean
    ) {
        val assemblyId = update.assemblyId ?: return
        val assembly = dao.getAssembly(assemblyId) ?: return

        if (shouldIgnore(update, assembly)) {
            return
        }

        val mappedStatus = update.status?.let { mapAssemblyStatus(it) }
        val updatedAssembly = assembly.copy(
            status = mappedStatus?.value ?: assembly.status,
            bytesReceived = update.bytesReceived ?: assembly.bytesReceived,
            lastUpdatedAt = System.currentTimeMillis(),
            errorMessage = update.errorMessage ?: assembly.errorMessage
        )
        dao.updateAssembly(updatedAssembly)

        update.photos?.forEach { photoUpdate ->
            val photoEntity = findPhotoEntity(assemblyId, photoUpdate, assembly.id) ?: return@forEach
            val mappedPhotoStatus = photoUpdate.status?.let { mapPhotoStatus(it) }
            val updatedPhoto = photoEntity.copy(
                status = mappedPhotoStatus?.value ?: photoEntity.status,
                bytesUploaded = photoUpdate.bytesUploaded ?: photoEntity.bytesUploaded,
                errorMessage = photoUpdate.errorMessage ?: photoEntity.errorMessage,
                lastUpdatedAt = System.currentTimeMillis()
            )
            dao.updatePhoto(updatedPhoto)
        }

        if (isFinalEvent && mappedStatus != null && mappedStatus in terminalStates) {
            stopTracking(assemblyId)
        }
    }

    private suspend fun findPhotoEntity(
        assemblyUuid: String,
        update: PhotoUpdate,
        assemblyLocalId: Long
    ): ImageProcessorPhotoEntity? {
        update.uploadTaskId?.let { taskId ->
            dao.getPhotoByUploadTaskId(taskId)?.let { return it }
        }

        val fileName = update.fileName ?: return null
        return dao.getPhotoByFilename(assemblyUuid, fileName)
            ?: run {
                // Fallback to lookup by order when filenames got deduped locally
                val photos = dao.getPhotosByAssemblyLocalId(assemblyLocalId)
                photos.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
            }
    }

    private fun stopTracking(assemblyId: String) {
        if (!trackedAssemblies.remove(assemblyId)) return
        val channelName = PusherConfig.channelNameForAssembly(assemblyId)
        pusherService.unsubscribe(channelName)
        pusherService.unsubscribe(PusherConfig.legacyAssemblyChannel(assemblyId))

        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Unsubscribed from image processor realtime updates",
            metadata = mapOf("assembly_id" to assemblyId, "channel" to channelName)
        )
    }

    private fun needsRealtimeTracking(statusValue: String): Boolean {
        val status = AssemblyStatus.fromValue(statusValue)
        return status !in terminalStates
    }

    private fun shouldIgnore(
        update: ImageProcessorUpdate,
        assembly: ImageProcessorAssemblyEntity
    ): Boolean {
        val localStatus = AssemblyStatus.fromValue(assembly.status)
        val backendStatus = update.status?.lowercase(Locale.US) ?: return false

        if (localStatus == AssemblyStatus.COMPLETED && backendStatus == "processing") {
            return true
        }

        if (localStatus in setOf(
                AssemblyStatus.RETRYING,
                AssemblyStatus.CREATING,
                AssemblyStatus.UPLOADING,
                AssemblyStatus.FAILED
            ) && backendStatus == "processing"
        ) {
            return true
        }

        return false
    }

    private suspend fun handleAssemblyResult(
        assemblyId: String,
        result: AssemblyResponse?
    ) {
        val status = result?.status ?: return
        if (!status.equals("failed", ignoreCase = true)) return

        val assembly = dao.getAssembly(assemblyId) ?: return
        val updated = assembly.copy(
            status = AssemblyStatus.FAILED.value,
            errorMessage = assembly.errorMessage ?: "Marked failed via Pusher"
        )
        dao.updateAssembly(updated)
        stopTracking(assemblyId)

        remoteLogger?.log(
            level = LogLevel.WARN,
            tag = TAG,
            message = "Assembly marked failed from legacy Pusher event",
            metadata = mapOf("assembly_id" to assemblyId)
        )
    }

    private fun mapAssemblyStatus(raw: String): AssemblyStatus? = when (raw.lowercase(Locale.US)) {
        "processing" -> AssemblyStatus.PROCESSING
        "success" -> AssemblyStatus.COMPLETED
        "failed" -> AssemblyStatus.FAILED
        "pending" -> AssemblyStatus.PENDING
        "creating" -> AssemblyStatus.CREATING
        "created" -> AssemblyStatus.CREATED
        "uploading" -> AssemblyStatus.UPLOADING
        "cancelled" -> AssemblyStatus.CANCELLED
        "retrying" -> AssemblyStatus.RETRYING
        "queued" -> AssemblyStatus.QUEUED
        "waiting_for_connectivity" -> AssemblyStatus.WAITING_FOR_CONNECTIVITY
        else -> {
            remoteLogger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Unknown assembly status from Pusher: $raw"
            )
            null
        }
    }

    private fun mapPhotoStatus(raw: String): PhotoStatus? = when (raw.lowercase(Locale.US)) {
        "processing" -> PhotoStatus.PROCESSING
        "success" -> PhotoStatus.COMPLETED
        "failed" -> PhotoStatus.FAILED
        "pending" -> PhotoStatus.PENDING
        "uploading" -> PhotoStatus.UPLOADING
        "cancelled" -> PhotoStatus.CANCELLED
        else -> {
            remoteLogger?.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Unknown photo status from Pusher: $raw"
            )
            null
        }
    }

    companion object {
        private const val TAG = "ImageProcessorRealtimeManager"
    }
}
