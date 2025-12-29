package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.CreateCompanyProjectRequest
import com.example.rocketplan_android.data.model.CreateLocationRequest
import com.example.rocketplan_android.data.model.CreateRoomRequest
import com.example.rocketplan_android.data.model.DeleteProjectRequest
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.UpdateProjectRequest
import com.example.rocketplan_android.data.model.offline.CreateNoteRequest
import com.example.rocketplan_android.data.model.offline.DamageMaterialRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RestoreRecordsRequest
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.data.repository.mapper.PendingLocationCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.PendingProjectCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomCreationPayload
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toRequest
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.util.parseTargetMoisture
import com.google.gson.Gson
import retrofit2.HttpException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID
import com.example.rocketplan_android.util.UuidUtils
import kotlin.math.min
import kotlin.text.Charsets

private const val DEFAULT_DAMAGE_TYPE_ID: Long = 1L

private fun Throwable.isConflict(): Boolean = (this as? HttpException)?.code() == 409
private fun Throwable.isMissingOnServer(): Boolean = (this as? HttpException)?.code() in listOf(404, 410)

data class PendingOperationResult(
    val createdProjects: List<PendingProjectSyncResult> = emptyList()
)

data class PendingProjectSyncResult(
    val localProjectId: Long,
    val serverProjectId: Long
)

class SyncQueueProcessor(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val syncProjectEssentials: suspend (Long) -> SyncResult,
    private val persistProperty: suspend (
        projectId: Long,
        property: PropertyDto,
        propertyTypeValue: String?,
        existing: OfflinePropertyEntity?,
        forcePropertyIdUpdate: Boolean
    ) -> OfflinePropertyEntity,
    private val imageProcessorQueueManagerProvider: () -> ImageProcessorQueueManager?,
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isNetworkAvailable: () -> Boolean = { false } // Default to offline for safety
) : SyncQueueEnqueuer {
    private val gson = Gson()

    private enum class OperationOutcome {
        SUCCESS,
        SKIP,
        RETRY,
        DROP
    }

    private fun now() = Date()

    suspend fun processPendingOperations(): PendingOperationResult = withContext(ioDispatcher) {
        // Skip sync attempts when offline to avoid unnecessary failures and log spam
        if (!isNetworkAvailable()) {
            Log.d(TAG, "⏭️ Skipping pending operations sync (no network)")
            return@withContext PendingOperationResult()
        }

        val operations = localDataService.getPendingSyncOperations()
        if (operations.isEmpty()) return@withContext PendingOperationResult()

        val createdProjects = mutableListOf<PendingProjectSyncResult>()

        suspend fun handleOperation(
            operation: OfflineSyncQueueEntity,
            label: String,
            block: suspend () -> OperationOutcome
        ) {
            runCatching { block() }
                .onSuccess { outcome ->
                    when (outcome) {
                        OperationOutcome.SUCCESS -> localDataService.removeSyncOperation(operation.operationId)
                        OperationOutcome.DROP -> {
                            Log.w(TAG, "⚠️ [$label] Dropping sync op=${operation.operationId} type=${operation.entityType}")
                            remoteLogger?.log(
                                LogLevel.WARN,
                                TAG,
                                "Sync operation dropped",
                                mapOf(
                                    "operationId" to operation.operationId,
                                    "entityType" to operation.entityType,
                                    "entityId" to operation.entityId.toString(),
                                    "operationType" to operation.operationType.name
                                )
                            )
                            localDataService.removeSyncOperation(operation.operationId)
                        }
                        OperationOutcome.SKIP,
                        OperationOutcome.RETRY -> Unit
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "⚠️ [$label] Sync operation failed", error)
                    val httpCode = (error as? HttpException)?.code()
                    val errorBody = if (error is HttpException) {
                        runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                    } else null
                    if (httpCode != null) {
                        Log.w(TAG, "⚠️ [$label] HTTP $httpCode response: $errorBody")
                    }
                    remoteLogger?.log(
                        LogLevel.ERROR,
                        TAG,
                        "Sync operation failed: ${error.message}",
                        buildMap {
                            put("operationId", operation.operationId)
                            put("entityType", operation.entityType)
                            put("entityId", operation.entityId.toString())
                            put("operationType", operation.operationType.name)
                            put("retryCount", operation.retryCount.toString())
                            httpCode?.let { put("httpCode", it.toString()) }
                            errorBody?.take(500)?.let { put("errorBody", it) }
                        }
                    )
                    markSyncOperationFailure(operation, error)
                }
        }

        operations.forEach { operation ->
            when (operation.entityType) {
                "project" -> handleOperation(operation, "pending:project") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> {
                            val result = handlePendingProjectCreation(operation)
                            if (result != null) {
                                createdProjects += result
                                OperationOutcome.SUCCESS
                            } else {
                                OperationOutcome.DROP
                            }
                        }
                        SyncOperationType.UPDATE -> handlePendingProjectUpdate(operation)
                        SyncOperationType.DELETE -> handlePendingProjectDeletion(operation)
                    }
                }
                "property" -> handleOperation(operation, "pending:property") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> handlePendingPropertyCreation(operation)
                        SyncOperationType.UPDATE -> handlePendingPropertyUpdate(operation)
                        SyncOperationType.DELETE -> OperationOutcome.DROP
                    }
                }
                "location" -> handleOperation(operation, "pending:location") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> handlePendingLocationCreation(operation)
                        SyncOperationType.UPDATE -> OperationOutcome.DROP
                        SyncOperationType.DELETE -> OperationOutcome.DROP
                    }
                }
                "room" -> handleOperation(operation, "pending:room") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> handlePendingRoomCreation(operation)
                        SyncOperationType.UPDATE -> OperationOutcome.DROP
                        SyncOperationType.DELETE -> handlePendingRoomDeletion(operation)
                    }
                }
                "note" -> handleOperation(operation, "pending:note") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE,
                        SyncOperationType.UPDATE -> handlePendingNoteUpsert(operation)
                        SyncOperationType.DELETE -> handlePendingNoteDeletion(operation)
                    }
                }
                "equipment" -> handleOperation(operation, "pending:equipment") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE,
                        SyncOperationType.UPDATE -> handlePendingEquipmentUpsert(operation)
                        SyncOperationType.DELETE -> handlePendingEquipmentDeletion(operation)
                    }
                }
                "moisture_log" -> handleOperation(operation, "pending:moisture") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE,
                        SyncOperationType.UPDATE -> handlePendingMoistureLogUpsert(operation)
                        SyncOperationType.DELETE -> handlePendingMoistureLogDeletion(operation)
                    }
                }
                "photo" -> handleOperation(operation, "pending:photo") {
                    when (operation.operationType) {
                        SyncOperationType.DELETE -> handlePendingPhotoDeletion(operation)
                        else -> OperationOutcome.DROP
                    }
                }
                else -> {
                    Log.w(TAG, "⚠️ [processPendingOperations] Unknown operation type=${operation.entityType}, removing")
                    localDataService.removeSyncOperation(operation.operationId)
                }
            }
        }

        PendingOperationResult(createdProjects = createdProjects)
    }

    override suspend fun enqueueProjectCreation(
        project: OfflineProjectEntity,
        companyId: Long,
        statusId: Int,
        addressRequest: CreateAddressRequest,
        idempotencyKey: String?
    ) {
        val payload = PendingProjectCreationPayload(
            localProjectId = project.projectId,
            projectUuid = project.uuid,
            companyId = companyId,
            projectStatusId = statusId,
            addressRequest = addressRequest,
            idempotencyKey = idempotencyKey
        )
        val operation = OfflineSyncQueueEntity(
            operationId = "project-${project.projectId}-${UUID.randomUUID()}",
            entityType = "project",
            entityId = project.projectId,
            entityUuid = project.uuid,
            operationType = SyncOperationType.CREATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.HIGH
        )
        localDataService.enqueueSyncOperation(operation)
    }

    override suspend fun enqueuePropertyCreation(
        property: OfflinePropertyEntity,
        projectId: Long,
        propertyTypeId: Int,
        propertyTypeValue: String?,
        idempotencyKey: String?
    ) {
        val payload = PendingPropertyCreationPayload(
            localPropertyId = property.propertyId,
            propertyUuid = property.uuid,
            projectId = projectId,
            propertyTypeId = propertyTypeId,
            propertyTypeValue = propertyTypeValue,
            idempotencyKey = idempotencyKey
        )
        val operation = OfflineSyncQueueEntity(
            operationId = "property-${property.propertyId}-${UUID.randomUUID()}",
            entityType = "property",
            entityId = property.propertyId,
            entityUuid = property.uuid,
            operationType = SyncOperationType.CREATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
        localDataService.enqueueSyncOperation(operation)
    }

    override suspend fun enqueueLocationCreation(
        location: OfflineLocationEntity,
        propertyLocalId: Long,
        locationName: String,
        locationTypeId: Long,
        type: String,
        floorNumber: Int,
        isCommon: Boolean,
        isAccessible: Boolean,
        isCommercial: Boolean,
        idempotencyKey: String?
    ) {
        val payload = PendingLocationCreationPayload(
            localLocationId = location.locationId,
            locationUuid = location.uuid,
            projectId = location.projectId,
            propertyLocalId = propertyLocalId,
            locationName = locationName,
            locationTypeId = locationTypeId,
            type = type,
            floorNumber = floorNumber,
            isCommon = isCommon,
            isAccessible = isAccessible,
            isCommercial = isCommercial,
            idempotencyKey = idempotencyKey
        )
        val operation = OfflineSyncQueueEntity(
            operationId = "location-${location.locationId}-${UUID.randomUUID()}",
            entityType = "location",
            entityId = location.locationId,
            entityUuid = location.uuid,
            operationType = SyncOperationType.CREATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
        localDataService.enqueueSyncOperation(operation)
    }

    override suspend fun enqueueRoomCreation(
        room: OfflineRoomEntity,
        roomTypeId: Long,
        roomTypeName: String?,
        isSource: Boolean,
        isExterior: Boolean,
        levelServerId: Long?,
        locationServerId: Long?,
        levelUuid: String,
        locationUuid: String,
        idempotencyKey: String?
    ) {
        val payload = PendingRoomCreationPayload(
            localRoomId = room.roomId,
            roomUuid = room.uuid,
            projectId = room.projectId,
            roomName = room.title,
            roomTypeId = roomTypeId,
            roomTypeName = roomTypeName ?: room.title,
            isSource = isSource,
            isExterior = isExterior,
            levelServerId = levelServerId,
            locationServerId = locationServerId,
            levelUuid = levelUuid,
            locationUuid = locationUuid,
            idempotencyKey = idempotencyKey
        )
        val operation = OfflineSyncQueueEntity(
            operationId = "room-${room.roomId}-${UUID.randomUUID()}",
            entityType = "room",
            entityId = room.roomId,
            entityUuid = room.uuid,
            operationType = SyncOperationType.CREATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
        localDataService.enqueueSyncOperation(operation)
    }

    override suspend fun enqueueProjectUpdate(
        project: OfflineProjectEntity,
        lockUpdatedAt: String?
    ) {
        if (project.serverId == null) {
            val statusId = ProjectStatus.fromApiValue(project.status)?.backendId
            val updated = updateCreateOperationPayload("project", project.projectId) { payload ->
                val existing = runCatching {
                    gson.fromJson(String(payload, Charsets.UTF_8), PendingProjectCreationPayload::class.java)
                }.getOrNull() ?: return@updateCreateOperationPayload null
                val resolvedStatus = statusId ?: existing.projectStatusId
                val refreshed = existing.copy(projectStatusId = resolvedStatus)
                gson.toJson(refreshed).toByteArray(Charsets.UTF_8)
            }
            if (!updated) {
                Log.w(TAG, "⚠️ [enqueueProjectUpdate] No pending create for project ${project.projectId}; skipping update")
            }
            return
        }
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "project",
            entityId = project.projectId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "project",
            entityId = project.projectId,
            entityUuid = project.uuid,
            operationType = SyncOperationType.UPDATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.HIGH
        )
    }

    override suspend fun enqueueProjectDeletion(
        project: OfflineProjectEntity,
        lockUpdatedAt: String?
    ) {
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "project",
            entityId = project.projectId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "project",
            entityId = project.projectId,
            entityUuid = project.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.HIGH
        )
    }

    override suspend fun enqueuePropertyUpdate(
        property: OfflinePropertyEntity,
        projectId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?,
        lockUpdatedAt: String?
    ) {
        if (property.serverId == null) {
            val updated = updateCreateOperationPayload("property", property.propertyId) { payload ->
                val existing = runCatching {
                    gson.fromJson(String(payload, Charsets.UTF_8), PendingPropertyCreationPayload::class.java)
                }.getOrNull() ?: return@updateCreateOperationPayload null
                val refreshed = existing.copy(
                    propertyTypeId = request.propertyTypeId,
                    propertyTypeValue = propertyTypeValue
                )
                gson.toJson(refreshed).toByteArray(Charsets.UTF_8)
            }
            if (!updated) {
                Log.w(TAG, "⚠️ [enqueuePropertyUpdate] No pending create for property ${property.propertyId}; skipping update")
            }
            return
        }
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "property",
            entityId = property.propertyId,
            fallback = lockUpdatedAt
        )
        val payload = PendingPropertyUpdatePayload(
            projectId = projectId,
            propertyId = property.propertyId,
            request = request,
            propertyTypeValue = propertyTypeValue,
            lockUpdatedAt = resolvedLockUpdatedAt
        )
        enqueueOperation(
            entityType = "property",
            entityId = property.propertyId,
            entityUuid = property.uuid,
            operationType = SyncOperationType.UPDATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueRoomDeletion(
        room: OfflineRoomEntity,
        lockUpdatedAt: String?
    ) {
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "room",
            entityId = room.roomId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "room",
            entityId = room.roomId,
            entityUuid = room.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueNoteUpsert(
        note: OfflineNoteEntity,
        lockUpdatedAt: String?
    ) {
        val entityId = resolveEntityId(note.noteId, note.uuid)
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "note",
            entityId = entityId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        val opType = if (note.serverId == null) SyncOperationType.CREATE else SyncOperationType.UPDATE
        enqueueOperation(
            entityType = "note",
            entityId = entityId,
            entityUuid = note.uuid,
            operationType = opType,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueNoteDeletion(
        note: OfflineNoteEntity,
        lockUpdatedAt: String?
    ) {
        val entityId = resolveEntityId(note.noteId, note.uuid)
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "note",
            entityId = entityId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "note",
            entityId = entityId,
            entityUuid = note.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueEquipmentUpsert(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String?
    ) {
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "equipment",
            entityId = equipment.equipmentId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        val opType = if (equipment.serverId == null) SyncOperationType.CREATE else SyncOperationType.UPDATE
        enqueueOperation(
            entityType = "equipment",
            entityId = equipment.equipmentId,
            entityUuid = equipment.uuid,
            operationType = opType,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueEquipmentDeletion(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String?
    ) {
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "equipment",
            entityId = equipment.equipmentId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "equipment",
            entityId = equipment.equipmentId,
            entityUuid = equipment.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueMoistureLogUpsert(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String?
    ) {
        val entityId = resolveEntityId(log.logId, log.uuid)
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "moisture_log",
            entityId = entityId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        val opType = if (log.serverId == null) SyncOperationType.CREATE else SyncOperationType.UPDATE
        enqueueOperation(
            entityType = "moisture_log",
            entityId = entityId,
            entityUuid = log.uuid,
            operationType = opType,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueMoistureLogDeletion(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String?
    ) {
        val entityId = resolveEntityId(log.logId, log.uuid)
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "moisture_log",
            entityId = entityId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "moisture_log",
            entityId = entityId,
            entityUuid = log.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueuePhotoDeletion(
        photo: OfflinePhotoEntity,
        lockUpdatedAt: String?
    ) {
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "photo",
            entityId = photo.photoId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "photo",
            entityId = photo.photoId,
            entityUuid = photo.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.LOW
        )
    }

    private suspend fun handlePendingProjectCreation(
        operation: OfflineSyncQueueEntity
    ): PendingProjectSyncResult? {
        val payload = runCatching {
            gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingProjectCreationPayload::class.java)
        }.getOrNull() ?: return null

        val existing = localDataService.getProject(payload.localProjectId)
            ?: return null

        val addressDto = api.createAddress(payload.addressRequest).data
        val addressId = addressDto.id
            ?: throw IllegalStateException("Address creation succeeded but returned null id")

        val idempotencyKey = payload.idempotencyKey ?: payload.projectUuid
        val projectRequest = CreateCompanyProjectRequest(
            projectStatusId = payload.projectStatusId,
            addressId = addressId,
            idempotencyKey = idempotencyKey
        )

        val dto = api.createCompanyProject(payload.companyId, projectRequest).data

        // Enhanced logging for debugging server response issues
        Log.d(
            TAG,
            "📥 [handlePendingProjectCreation] Server response: " +
                "id=${dto.id} companyId=${dto.companyId} uid=${dto.uid} uuid=${dto.uuid} " +
                "propertyId=${dto.propertyId} createdAt=${dto.createdAt} updatedAt=${dto.updatedAt}"
        )

        // CRITICAL: Validate the server returned a project for the correct company
        // This detects server-side bugs where wrong company's project is returned
        if (dto.companyId != null && dto.companyId != payload.companyId) {
            Log.e(
                TAG,
                "🚨 [handlePendingProjectCreation] COMPANY MISMATCH! " +
                    "Requested companyId=${payload.companyId} but server returned companyId=${dto.companyId} " +
                    "for project id=${dto.id}. This indicates a server-side bug. " +
                    "Rejecting response to prevent data corruption."
            )
            throw IllegalStateException(
                "Server returned project from wrong company: expected=${payload.companyId}, got=${dto.companyId}"
            )
        }

        // Re-read project to get latest propertyId (may have been attached during API call)
        val freshExisting = localDataService.getProject(payload.localProjectId) ?: existing
        Log.d(TAG, "🔍 [handlePendingProjectCreation] dto.id=${dto.id} dto.propertyId=${dto.propertyId} freshExisting.propertyId=${freshExisting.propertyId}")
        val entity = dto.toEntity(existing = freshExisting).withAddressFallback(
            projectAddress = addressDto,
            addressRequest = payload.addressRequest
        ).copy(
            projectId = freshExisting.projectId,
            uuid = freshExisting.uuid,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = now()
        )
        Log.d(TAG, "🔍 [handlePendingProjectCreation] entity.propertyId=${entity.propertyId} (should be ${freshExisting.propertyId})")

        localDataService.saveProjects(listOf(entity))
        val pendingAlias = freshExisting.alias?.takeIf { it.isNotBlank() }
        if (!pendingAlias.isNullOrBlank() && pendingAlias != entity.alias) {
            val updatedDto = api.updateProject(
                projectId = dto.id,
                body = UpdateProjectRequest(
                    alias = pendingAlias,
                    projectStatusId = null,
                    updatedAt = dto.updatedAt
                )
            ).data
            val updated = updatedDto.toEntity(existing = entity).copy(
                projectId = entity.projectId,
                uuid = entity.uuid,
                syncStatus = SyncStatus.SYNCED,
                isDirty = false,
                lastSyncedAt = now()
            )
            localDataService.saveProjects(listOf(updated))
        }
        return PendingProjectSyncResult(localProjectId = entity.projectId, serverProjectId = dto.id)
    }

    private suspend fun handlePendingProjectUpdate(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val project = localDataService.getProject(operation.entityId) ?: return OperationOutcome.DROP
        val serverId = project.serverId ?: return OperationOutcome.SKIP
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: project.updatedAt.toApiTimestamp()
        val statusId = ProjectStatus.fromApiValue(project.status)?.backendId
        val request = UpdateProjectRequest(
            alias = project.alias?.takeIf { it.isNotBlank() },
            projectStatusId = statusId,
            updatedAt = lockUpdatedAt
        )
        val dto = api.updateProject(serverId, request).data
        val entity = dto.toEntity(existing = project).copy(
            projectId = project.projectId,
            uuid = project.uuid,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = now()
        )
        localDataService.saveProjects(listOf(entity))
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingProjectDeletion(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val project = localDataService.getProject(operation.entityId) ?: return OperationOutcome.DROP
        val serverId = project.serverId ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: project.updatedAt.toApiTimestamp()
        val request = DeleteProjectRequest(
            projectId = serverId,
            updatedAt = lockUpdatedAt
        )
        val response = api.deleteProject(serverId, request)
        if (!response.isSuccessful) {
            if (response.code() == 409) {
                // Conflict - fetch fresh and retry delete with updated timestamp
                Log.w(TAG, "⚠️ [handlePendingProjectDeletion] 409 conflict for project $serverId; fetching fresh and retrying")
                remoteLogger?.log(
                    LogLevel.WARN,
                    TAG,
                    "Project delete 409 conflict, retrying",
                    mapOf(
                        "projectServerId" to serverId.toString(),
                        "projectLocalId" to project.projectId.toString()
                    )
                )
                val freshProject = runCatching {
                    api.getProjectDetail(serverId).data
                }.getOrElse { error ->
                    Log.e(TAG, "❌ [handlePendingProjectDeletion] Failed to fetch fresh project $serverId", error)
                    remoteLogger?.log(
                        LogLevel.ERROR,
                        TAG,
                        "Project delete conflict resolution failed",
                        mapOf(
                            "projectServerId" to serverId.toString(),
                            "error" to (error.message ?: "unknown")
                        )
                    )
                    val restored = project.copy(isDeleted = false, isDirty = false, syncStatus = SyncStatus.SYNCED)
                    localDataService.saveProjects(listOf(restored))
                    return OperationOutcome.DROP
                }

                val retryRequest = DeleteProjectRequest(projectId = serverId, updatedAt = freshProject.updatedAt)
                val retryResponse = api.deleteProject(serverId, retryRequest)
                if (!retryResponse.isSuccessful && retryResponse.code() !in listOf(404, 409, 410)) {
                    throw HttpException(retryResponse)
                }
                if (retryResponse.code() == 409) {
                    Log.w(TAG, "⚠️ [handlePendingProjectDeletion] Retry still got 409; restoring project $serverId")
                    remoteLogger?.log(
                        LogLevel.WARN,
                        TAG,
                        "Project delete 409 conflict persisted, restoring",
                        mapOf("projectServerId" to serverId.toString())
                    )
                    val restored = freshProject.toEntity(existing = project)
                    localDataService.saveProjects(listOf(restored))
                    return OperationOutcome.DROP
                }
                Log.d(TAG, "✅ [handlePendingProjectDeletion] Retry delete succeeded for project $serverId")
            } else if (response.code() !in listOf(404, 410)) {
                throw HttpException(response)
            }
        }
        val cleaned = project.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = now()
        )
        localDataService.saveProjects(listOf(cleaned))
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingPropertyCreation(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val payload = runCatching {
            gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingPropertyCreationPayload::class.java)
        }.getOrNull() ?: return OperationOutcome.DROP

        val project = localDataService.getProject(payload.projectId) ?: return OperationOutcome.SKIP
        val projectServerId = project.serverId ?: return OperationOutcome.SKIP

        val request = PropertyMutationRequest(
            uuid = payload.propertyUuid,
            propertyTypeId = payload.propertyTypeId,
            projectUuid = project.uuid,
            idempotencyKey = payload.idempotencyKey
        )

        // Enhanced logging - always log for property creation to debug server issues
        Log.d(
            TAG,
            "📤 [handlePendingPropertyCreation] createProperty request: " +
                "projectServerId=$projectServerId localProjectId=${payload.projectId} " +
                "propertyTypeId=${payload.propertyTypeId} propertyTypeValue=${payload.propertyTypeValue ?: "null"} " +
                "idempotencyKey=${payload.idempotencyKey ?: "null"} localPropertyId=${payload.localPropertyId}"
        )

        val created = try {
            api.createProjectProperty(projectServerId, request).data
        } catch (error: HttpException) {
            val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            Log.w(
                TAG,
                "❌ [handlePendingPropertyCreation] createProperty failed: code=${error.code()} " +
                    "body=${errorBody ?: "null"}"
            )
            throw error
        }

        // Enhanced logging with full response details
        Log.d(
            TAG,
            "📥 [handlePendingPropertyCreation] createProperty response: id=${created.id} " +
                "uuid=${created.uuid} address=${created.address} city=${created.city} " +
                "state=${created.state} zip=${created.postalCode} " +
                "propertyTypeId=${created.propertyTypeId} propertyType=${created.propertyType} " +
                "createdAt=${created.createdAt} updatedAt=${created.updatedAt}"
        )

        // Validate the property was actually created recently (within last 5 minutes)
        // This detects server returning stale/cached property data
        val createdAtDate = created.createdAt?.let { DateUtils.parseApiDate(it) }
        val nowMillis = System.currentTimeMillis()
        val ageMinutes = createdAtDate?.let { (nowMillis - it.time) / 60_000 }
        if (ageMinutes != null && ageMinutes > 5) {
            Log.w(
                TAG,
                "⚠️ [handlePendingPropertyCreation] STALE PROPERTY WARNING: " +
                    "Property ${created.id} was created ${ageMinutes}min ago (createdAt=${created.createdAt}). " +
                    "This may indicate server returned cached/wrong property data."
            )
        }

        val refreshed = runCatching { api.getProperty(created.id).data }
            .onFailure {
                Log.w(
                    TAG,
                    "⚠️ [handlePendingPropertyCreation] getProperty failed for id=${created.id}",
                    it
                )
            }
            .getOrNull()
        val resolved = refreshed ?: created
        if (AppConfig.isLoggingEnabled) {
            val source = if (refreshed != null) "getProperty" else "createResponse"
            Log.d(
                TAG,
                "📥 [handlePendingPropertyCreation] property resolved from $source: id=${resolved.id} " +
                    "address=${resolved.address} city=${resolved.city} state=${resolved.state} zip=${resolved.postalCode} " +
                    "propertyTypeId=${resolved.propertyTypeId} propertyType=${resolved.propertyType}"
            )
        }
        val existing = localDataService.getProperty(payload.localPropertyId)
        persistProperty(payload.projectId, resolved, payload.propertyTypeValue, existing, true)

        // Root cause fix: sync local level serverIds after property creation
        // The server creates default levels when a property is created - match them with local pending levels
        runCatching {
            syncLocalLevelServerIds(payload.projectId, resolved.id)
        }.onFailure {
            Log.w(TAG, "⚠️ [handlePendingPropertyCreation] Failed to sync level serverIds", it)
        }

        return OperationOutcome.SUCCESS
    }

    /**
     * Syncs local pending levels/locations with their server counterparts by name-matching.
     * Called after property creation to populate serverId on local level entities.
     */
    private suspend fun syncLocalLevelServerIds(projectId: Long, propertyServerId: Long) {
        val localLocations = localDataService.getLocations(projectId)
        val pendingLocations = localLocations.filter { it.serverId == null }
        if (AppConfig.isLoggingEnabled) {
            Log.d(TAG, "🔍 [syncLocalLevelServerIds] projectId=$projectId propertyServerId=$propertyServerId " +
                "localLocations=${localLocations.size} pendingLocations=${pendingLocations.size}")
        }
        if (pendingLocations.isEmpty()) return

        val remoteLevels = runCatching { api.getPropertyLevels(propertyServerId).data }.getOrNull()
        val remoteLocations = runCatching { api.getPropertyLocations(propertyServerId).data }.getOrNull()
        if (remoteLevels == null && remoteLocations == null) return

        var updatedCount = 0
        for (pending in pendingLocations) {
            val pendingName = pending.title?.takeIf { it.isNotBlank() }
                ?: pending.type?.takeIf { it.isNotBlank() }
                ?: continue

            // Try levels first, then locations
            val matchedServerId = remoteLevels?.firstOrNull { level ->
                val remoteName = listOfNotNull(
                    level.title?.takeIf { it.isNotBlank() },
                    level.name?.takeIf { it.isNotBlank() }
                ).firstOrNull()
                remoteName?.equals(pendingName, ignoreCase = true) == true
            }?.id ?: remoteLocations?.firstOrNull { location ->
                val remoteName = listOfNotNull(
                    location.title?.takeIf { it.isNotBlank() },
                    location.name?.takeIf { it.isNotBlank() }
                ).firstOrNull()
                remoteName?.equals(pendingName, ignoreCase = true) == true
            }?.id ?: continue

            val updated = pending.copy(
                serverId = matchedServerId,
                syncStatus = SyncStatus.SYNCED,
                isDirty = false,
                lastSyncedAt = now()
            )
            localDataService.saveLocations(listOf(updated))
            updatedCount++
        }

        if (AppConfig.isLoggingEnabled && updatedCount > 0) {
            Log.d(TAG, "✅ [syncLocalLevelServerIds] Updated $updatedCount local levels with serverIds")
        }
    }

    private suspend fun handlePendingPropertyUpdate(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val payload = runCatching {
            gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingPropertyUpdatePayload::class.java)
        }.getOrNull() ?: return OperationOutcome.DROP

        val property = localDataService.getProperty(payload.propertyId) ?: return OperationOutcome.DROP
        val serverId = property.serverId ?: return OperationOutcome.SKIP
        val lockUpdatedAt = payload.lockUpdatedAt ?: property.updatedAt.toApiTimestamp()
        val request = payload.request.copy(
            updatedAt = lockUpdatedAt,
            idempotencyKey = null
        )
        if (AppConfig.isLoggingEnabled) {
            Log.d(
                TAG,
                "📤 [handlePendingPropertyUpdate] updateProperty payload: " +
                    "projectId=${payload.projectId} propertyId=${payload.propertyId} serverId=${serverId} " +
                    "propertyTypeId=${request.propertyTypeId} name=${request.name ?: "null"} " +
                    "lockUpdatedAt=$lockUpdatedAt"
            )
        }

        val updated = try {
            api.updateProperty(serverId, request).data
        } catch (error: HttpException) {
            if (AppConfig.isLoggingEnabled) {
                val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                Log.w(
                    TAG,
                    "❌ [handlePendingPropertyUpdate] updateProperty failed: code=${error.code()} " +
                        "body=${errorBody ?: "null"}"
                )
            }
            throw error
        }

        if (AppConfig.isLoggingEnabled) {
            Log.d(
                TAG,
                "📥 [handlePendingPropertyUpdate] updateProperty response: id=${updated.id} " +
                    "address=${updated.address} city=${updated.city} state=${updated.state} zip=${updated.postalCode} " +
                    "propertyTypeId=${updated.propertyTypeId} propertyType=${updated.propertyType}"
            )
        }

        val refreshed = runCatching { api.getProperty(updated.id).data }
            .onFailure {
                Log.w(
                    TAG,
                    "⚠️ [handlePendingPropertyUpdate] getProperty failed for id=${updated.id}",
                    it
                )
            }
            .getOrNull()
        val resolved = refreshed ?: updated
        if (AppConfig.isLoggingEnabled) {
            val source = if (refreshed != null) "getProperty" else "updateResponse"
            Log.d(
                TAG,
                "📥 [handlePendingPropertyUpdate] property resolved from $source: id=${resolved.id} " +
                    "address=${resolved.address} city=${resolved.city} state=${resolved.state} zip=${resolved.postalCode} " +
                    "propertyTypeId=${resolved.propertyTypeId} propertyType=${resolved.propertyType}"
            )
        }

        persistProperty(payload.projectId, resolved, payload.propertyTypeValue, property, false)
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingRoomCreation(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val payload = runCatching {
            gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingRoomCreationPayload::class.java)
        }.getOrNull() ?: return OperationOutcome.DROP

        val project = localDataService.getProject(payload.projectId) ?: return OperationOutcome.SKIP
        val projectServerId = project.serverId ?: return OperationOutcome.SKIP

        fun normalizeServerId(value: Long?): Long? = value?.takeIf { it > 0 }

        var refreshedEssentials = false
        suspend fun refreshEssentialsOnce() {
            if (!refreshedEssentials && isNetworkAvailable()) {
                syncProjectEssentials(payload.projectId)
                refreshedEssentials = true
            }
        }

        // Resolve property server ID (required for API calls)
        suspend fun resolvePropertyServerId(): Long? {
            val propertyId = project.propertyId ?: return null
            return normalizeServerId(localDataService.getProperty(propertyId)?.serverId)
        }

        var propertyServerId = resolvePropertyServerId()
        if (propertyServerId == null) {
            refreshEssentialsOnce()
            propertyServerId = resolvePropertyServerId()
        }
        if (propertyServerId == null) {
            Log.w(
                TAG,
                "⚠️ [handlePendingRoomCreation] Property not synced for project ${payload.projectId}; will retry"
            )
            return OperationOutcome.SKIP
        }

        // Resolve level and location by UUID (simple lookup)
        var level = localDataService.getLocationByUuid(payload.levelUuid)
        var location = localDataService.getLocationByUuid(payload.locationUuid)
        var levelServerId = normalizeServerId(payload.levelServerId) ?: normalizeServerId(level?.serverId)
        var locationServerId = normalizeServerId(payload.locationServerId) ?: normalizeServerId(location?.serverId)

        Log.d(
            TAG,
            "🔍 [handlePendingRoomCreation] Resolving IDs for room '${payload.roomName}': " +
                "levelUuid=${payload.levelUuid} → serverId=$levelServerId, " +
                "locationUuid=${payload.locationUuid} → serverId=$locationServerId"
        )

        // If IDs are missing, refresh essentials and try again
        if (levelServerId == null || locationServerId == null) {
            refreshEssentialsOnce()
            level = localDataService.getLocationByUuid(payload.levelUuid)
            location = localDataService.getLocationByUuid(payload.locationUuid)
            if (levelServerId == null) {
                levelServerId = normalizeServerId(level?.serverId)
            }
            if (locationServerId == null) {
                locationServerId = normalizeServerId(location?.serverId)
            }
        }

        // Handle Single Unit properties where level == location
        if (levelServerId != null && locationServerId == null && payload.levelUuid == payload.locationUuid) {
            locationServerId = levelServerId
        }

        if (levelServerId == null || locationServerId == null) {
            Log.w(TAG, "⚠️ [handlePendingRoomCreation] Missing location/level for room ${payload.roomName}; will retry")
            remoteLogger?.log(
                LogLevel.WARN,
                TAG,
                "Room creation missing location/level IDs",
                mapOf(
                    "roomName" to payload.roomName,
                    "projectId" to projectServerId.toString(),
                    "levelUuid" to payload.levelUuid,
                    "locationUuid" to payload.locationUuid,
                    "levelServerId" to (levelServerId?.toString() ?: "null"),
                    "locationServerId" to (locationServerId?.toString() ?: "null")
                )
            )
            return OperationOutcome.SKIP
        }

        // Skip when offline - we need network to create the room
        if (!isNetworkAvailable()) {
            Log.d(TAG, "⏭️ [handlePendingRoomCreation] No network available; will retry later")
            return OperationOutcome.SKIP
        }

        val finalLevelId = levelServerId
        val finalLocationId = locationServerId

        restoreDeletedParents(
            mapOf(
                "projects" to listOf(projectServerId),
                "locations" to listOf(finalLocationId),
                "levels" to listOf(finalLevelId)
            )
        )

        val payloadRoomUuid = payload.roomUuid?.takeIf { it.isNotBlank() }
        val idempotencyKey = payload.idempotencyKey
            ?: payloadRoomUuid
            ?: payload.localRoomId.takeIf { it != 0L }?.toString()
        val resolvedRoomTypeId = resolveRoomTypeIdForPayload(payload)
        if (resolvedRoomTypeId == null) {
            Log.w(
                TAG,
                "⚠️ [handlePendingRoomCreation] Unable to resolve roomTypeId for '${payload.roomName}' (name=${payload.roomTypeName}); will retry after room types sync"
            )
            return OperationOutcome.SKIP
        }

        val request = CreateRoomRequest(
            name = payload.roomName,
            uuid = payloadRoomUuid,
            roomTypeId = resolvedRoomTypeId,
            levelId = finalLevelId,
            levelUuid = payload.levelUuid,
            locationUuid = payload.locationUuid,
            isSource = payload.isSource,
            idempotencyKey = idempotencyKey
        )

        if (AppConfig.isLoggingEnabled) {
            Log.d(
                TAG,
                "📤 [handlePendingRoomCreation] createRoom payload: " +
                    "locationId=$finalLocationId levelId=$finalLevelId " +
                    "roomTypeId=$resolvedRoomTypeId name='${payload.roomName}' " +
                    "typeName='${payload.roomTypeName}' isSource=${payload.isSource} " +
                    "idempotencyKey=${idempotencyKey ?: "null"} projectId=${payload.projectId}"
            )
        }

        val rawResponse = try {
            api.createRoom(finalLocationId, request)
        } catch (error: HttpException) {
            if (AppConfig.isLoggingEnabled) {
                val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                Log.w(
                    TAG,
                    "❌ [handlePendingRoomCreation] createRoom failed: code=${error.code()} " +
                        "body=${errorBody ?: "null"}"
                )
            }
            throw error
        }
        if (AppConfig.isLoggingEnabled) {
            Log.d(TAG, "📥 [handlePendingRoomCreation] createRoom response: ${rawResponse.toString()}")
        }
        val dto = when {
            rawResponse.isJsonObject && rawResponse.asJsonObject.has("data") ->
                gson.fromJson(rawResponse.asJsonObject.get("data"), RoomDto::class.java)
            rawResponse.isJsonObject && rawResponse.asJsonObject.has("room") ->
                gson.fromJson(rawResponse.asJsonObject.get("room"), RoomDto::class.java)
            else -> gson.fromJson(rawResponse, RoomDto::class.java)
        }
        if (dto.id <= 0) {
            Log.w(
                TAG,
                "📴 [handlePendingRoomCreation] Server returned invalid room id=${dto.id} for ${payload.roomName}; keeping pending"
            )
            return OperationOutcome.RETRY
        }
        val preexistingServerRoom = localDataService.getRoomByServerId(dto.id)
        val existing = localDataService.getRoom(payload.localRoomId)
            ?: payloadRoomUuid?.let { uuid -> localDataService.getRoomByUuid(uuid) }
            ?: preexistingServerRoom
            ?: localDataService.getPendingRoomForProject(payload.projectId, payload.roomName)
        val resolvedRoomId = existing?.roomId
            ?: payload.localRoomId.takeIf { it != 0L }
            ?: dto.id
        val resolvedUuid = existing?.uuid
            ?: payloadRoomUuid
            ?: dto.uuid
            ?: UuidUtils.generateUuidV7()
        val entity = dto.toEntity(
            existing = existing,
            projectId = payload.projectId,
            locationId = locationServerId
        ).copy(
            roomId = resolvedRoomId,
            uuid = resolvedUuid,
            roomTypeId = resolvedRoomTypeId,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = now()
        )
        localDataService.saveRooms(listOf(entity))
        preexistingServerRoom
            ?.takeIf { it.roomId != resolvedRoomId }
            ?.let { duplicate ->
                val cleaned = duplicate.copy(
                    isDeleted = true,
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = now()
                )
                localDataService.saveRooms(listOf(cleaned))
            }
        imageProcessorQueueManagerProvider()?.processNextQueuedAssembly()
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingRoomDeletion(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val room = localDataService.getRoom(operation.entityId) ?: return OperationOutcome.DROP
        val serverId = room.serverId ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: room.updatedAt.toApiTimestamp()
        try {
            api.deleteRoom(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        } catch (error: Throwable) {
            if (!error.isMissingOnServer()) {
                throw error
            }
        }
        val cleaned = room.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = now()
        )
        localDataService.saveRooms(listOf(cleaned))
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingNoteUpsert(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val note = localDataService.getNoteByUuid(operation.entityUuid) ?: return OperationOutcome.DROP
        if (note.isDeleted) return OperationOutcome.DROP
        val projectServerId = resolveServerProjectId(note.projectId) ?: return OperationOutcome.SKIP
        val roomServerId = note.roomId?.let { roomId ->
            localDataService.getRoom(roomId)?.serverId ?: roomId.takeIf { it > 0 }
        }
        if (note.roomId != null && roomServerId == null) {
            return OperationOutcome.SKIP
        }

        // Resolve photoId to serverId - notes on unuploaded photos must wait
        val photoServerId = note.photoId?.let { photoId ->
            val photo = localDataService.getPhoto(photoId)
            photo?.serverId ?: photoId.takeIf { it > 0 && photo == null }
            // If photo exists locally but has no serverId, it hasn't uploaded yet - skip
        }
        if (note.photoId != null && photoServerId == null) {
            Log.d(TAG, "⏳ [handlePendingNoteUpsert] Note ${note.uuid} attached to photo ${note.photoId} which hasn't uploaded yet; will retry")
            return OperationOutcome.SKIP
        }

        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: note.updatedAt.toApiTimestamp()
        val request = CreateNoteRequest(
            projectId = projectServerId,
            roomId = roomServerId,
            body = note.content,
            photoId = photoServerId,
            categoryId = note.categoryId,
            idempotencyKey = note.uuid,
            updatedAt = lockUpdatedAt
        )
        val dto = if (note.serverId == null) {
            api.createProjectNote(projectServerId, request.copy(updatedAt = null)).data
        } else {
            api.updateNote(note.serverId, request).data
        }
        val entity = dto.toEntity()?.copy(
            noteId = note.noteId,
            uuid = note.uuid,
            projectId = note.projectId,
            roomId = note.roomId ?: dto.roomId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = now()
        ) ?: return OperationOutcome.SKIP
        localDataService.saveNote(entity)
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingNoteDeletion(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val note = localDataService.getNoteByUuid(operation.entityUuid) ?: return OperationOutcome.DROP
        val serverId = note.serverId ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: note.updatedAt.toApiTimestamp()
        val response = api.deleteNote(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
            throw HttpException(response)
        }
        val cleaned = note.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = now()
        )
        localDataService.saveNote(cleaned)
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingEquipmentUpsert(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val equipment = localDataService.getEquipmentByUuid(operation.entityUuid) ?: return OperationOutcome.DROP
        if (equipment.isDeleted) return OperationOutcome.DROP
        val projectServerId = resolveServerProjectId(equipment.projectId) ?: return OperationOutcome.SKIP
        val roomServerId = equipment.roomId?.let { roomId ->
            localDataService.getRoom(roomId)?.serverId
        }
        if (equipment.roomId != null && roomServerId == null) {
            return OperationOutcome.SKIP
        }
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: equipment.updatedAt.toApiTimestamp()
        val synced = pushPendingEquipmentUpsert(equipment, projectServerId, roomServerId, lockUpdatedAt)
        synced?.let { localDataService.saveEquipment(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
    }

    private suspend fun handlePendingEquipmentDeletion(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val equipment = localDataService.getEquipmentByUuid(operation.entityUuid) ?: return OperationOutcome.DROP
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: equipment.updatedAt.toApiTimestamp()
        val synced = pushPendingEquipmentDeletion(equipment, lockUpdatedAt)
        synced?.let { localDataService.saveEquipment(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
    }

    private suspend fun handlePendingMoistureLogUpsert(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val log = localDataService.getMoistureLogByUuid(operation.entityUuid) ?: return OperationOutcome.DROP
        if (log.isDeleted) return OperationOutcome.DROP
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: log.updatedAt.toApiTimestamp()
        val synced = pushPendingMoistureLogUpsert(log, lockUpdatedAt)
        synced?.let { localDataService.saveMoistureLogs(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
    }

    private suspend fun handlePendingMoistureLogDeletion(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val log = localDataService.getMoistureLogByUuid(operation.entityUuid) ?: return OperationOutcome.DROP
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: log.updatedAt.toApiTimestamp()
        val synced = pushPendingMoistureLogDeletion(log, lockUpdatedAt)
        synced?.let { localDataService.saveMoistureLogs(listOf(it)) }
        return if (synced != null) OperationOutcome.SUCCESS else OperationOutcome.SKIP
    }

    private suspend fun handlePendingPhotoDeletion(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val photo = localDataService.getPhoto(operation.entityId) ?: return OperationOutcome.DROP
        val serverId = photo.serverId ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: photo.updatedAt.toApiTimestamp()
        val response = api.deletePhoto(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
            throw HttpException(response)
        }
        val cleaned = photo.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = now()
        )
        localDataService.savePhotos(listOf(cleaned))
        return OperationOutcome.SUCCESS
    }

    private suspend fun handlePendingLocationCreation(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val payload = runCatching {
            gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingLocationCreationPayload::class.java)
        }.getOrNull() ?: return OperationOutcome.DROP

        // Try to find property by the stored local ID first
        var property = localDataService.getProperty(payload.propertyLocalId)

        // If property not found, the pending property may have been replaced with a synced one.
        // Look up the project's current property instead of returning SKIP forever.
        if (property == null) {
            val project = localDataService.getProject(payload.projectId)
            if (project == null || project.isDeleted) {
                Log.d(TAG, "🗑️ [handlePendingLocationCreation] Project ${payload.projectId} not found or deleted; dropping operation")
                return OperationOutcome.DROP
            }
            val currentPropertyId = project.propertyId
            if (currentPropertyId == null) {
                // Project exists but has no property yet - wait for property creation
                return OperationOutcome.SKIP
            }
            property = localDataService.getProperty(currentPropertyId)
            if (property == null) {
                Log.w(TAG, "⚠️ [handlePendingLocationCreation] Project ${payload.projectId} has propertyId=$currentPropertyId but property not found")
                return OperationOutcome.SKIP
            }
            Log.d(TAG, "🔄 [handlePendingLocationCreation] Property ${payload.propertyLocalId} not found; using project's current property ${property.propertyId}")
        }

        val propertyServerId = property.serverId
            ?: return OperationOutcome.SKIP

        val currentLocations = localDataService.getLocations(payload.projectId)
        val pendingLocation = currentLocations.firstOrNull {
            it.uuid == payload.locationUuid || it.locationId == payload.localLocationId
        }
        if (pendingLocation?.serverId != null) {
            return OperationOutcome.SUCCESS
        }

        fun matchesServerLocation(
            title: String?,
            type: String?,
            payloadName: String,
            payloadType: String
        ): Boolean {
            val nameMatches = title?.equals(payloadName, ignoreCase = true) ?: false
            val typeMatches = type?.equals(payloadType, ignoreCase = true) ?: true
            return nameMatches && typeMatches
        }

        val existingServerLocation = currentLocations.firstOrNull {
            it.serverId != null &&
                matchesServerLocation(it.title, it.type, payload.locationName, payload.type)
        }
        if (pendingLocation != null && existingServerLocation != null) {
            val timestamp = now()
            val merged = pendingLocation.copy(
                serverId = existingServerLocation.serverId,
                title = existingServerLocation.title,
                type = existingServerLocation.type,
                parentLocationId = existingServerLocation.parentLocationId,
                isAccessible = existingServerLocation.isAccessible,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = maxOf(pendingLocation.syncVersion, 1),
                isDirty = false,
                updatedAt = timestamp,
                lastSyncedAt = timestamp
            )
            localDataService.saveLocations(listOf(merged))
            return OperationOutcome.SUCCESS
        }

        if (pendingLocation != null) {
            val remoteMatch = runCatching {
                api.getPropertyLevels(propertyServerId).data
            }.getOrNull()?.firstOrNull { level ->
                val resolvedTitle = listOfNotNull(
                    level.title?.takeIf { it.isNotBlank() },
                    level.name?.takeIf { it.isNotBlank() }
                ).firstOrNull()
                val resolvedType = listOfNotNull(
                    level.type?.takeIf { it.isNotBlank() },
                    level.locationType?.takeIf { it.isNotBlank() }
                ).firstOrNull()
                matchesServerLocation(resolvedTitle, resolvedType, payload.locationName, payload.type)
            }

            if (remoteMatch != null) {
                val timestamp = now()
                val merged = pendingLocation.copy(
                    serverId = remoteMatch.id,
                    title = listOfNotNull(
                        remoteMatch.title?.takeIf { it.isNotBlank() },
                        remoteMatch.name?.takeIf { it.isNotBlank() },
                        pendingLocation.title
                    ).first(),
                    type = listOfNotNull(
                        remoteMatch.type?.takeIf { it.isNotBlank() },
                        remoteMatch.locationType?.takeIf { it.isNotBlank() },
                        pendingLocation.type
                    ).first(),
                    parentLocationId = remoteMatch.parentLocationId,
                    isAccessible = remoteMatch.isAccessible ?: pendingLocation.isAccessible,
                    syncStatus = SyncStatus.SYNCED,
                    syncVersion = maxOf(pendingLocation.syncVersion, 1),
                    isDirty = false,
                    updatedAt = timestamp,
                    lastSyncedAt = timestamp
                )
                localDataService.saveLocations(listOf(merged))
                return OperationOutcome.SUCCESS
            }
        }

        val request = CreateLocationRequest(
            name = payload.locationName,
            uuid = payload.locationUuid,
            floorNumber = payload.floorNumber,
            locationTypeId = payload.locationTypeId,
            isCommon = payload.isCommon,
            isAccessible = payload.isAccessible,
            isCommercial = payload.isCommercial,
            idempotencyKey = payload.idempotencyKey
        )

        val dto = api.createLocation(propertyServerId, request)
        val existing = localDataService.getLocations(payload.projectId)
            .firstOrNull { it.uuid == payload.locationUuid || it.locationId == payload.localLocationId }
        val entity = dto.toEntity(defaultProjectId = payload.projectId).copy(
            locationId = existing?.locationId ?: dto.id,
            uuid = existing?.uuid ?: dto.uuid ?: UuidUtils.generateUuidV7(),
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = now()
        )
        localDataService.saveLocations(listOf(entity))
        return OperationOutcome.SUCCESS
    }

    private suspend fun markSyncOperationFailure(
        operation: OfflineSyncQueueEntity,
        error: Throwable
    ) {
        val nextRetry = operation.retryCount + 1
        val now = Date()
        val backoffSeconds = min(30 * 60, 10 * (1 shl operation.retryCount))
        val willRetry = nextRetry < operation.maxRetries
        val updated = operation.copy(
            retryCount = nextRetry,
            lastAttemptAt = now,
            scheduledAt = if (willRetry) Date(now.time + backoffSeconds * 1000L) else null,
            status = if (willRetry) SyncStatus.PENDING else SyncStatus.FAILED,
            errorMessage = error.message
        )
        localDataService.enqueueSyncOperation(updated)

        if (!willRetry) {
            remoteLogger?.log(
                LogLevel.ERROR,
                TAG,
                "Sync operation exhausted retries",
                mapOf(
                    "operationId" to operation.operationId,
                    "entityType" to operation.entityType,
                    "entityId" to operation.entityId.toString(),
                    "operationType" to operation.operationType.name,
                    "maxRetries" to operation.maxRetries.toString(),
                    "lastError" to (error.message ?: "unknown")
                )
            )
        }
    }

    private fun resolveEntityId(entityId: Long, uuid: String): Long =
        if (entityId != 0L) entityId else runCatching {
            UUID.fromString(uuid).mostSignificantBits
        }.getOrElse { uuid.hashCode().toLong() }

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            gson.fromJson(String(payload, Charsets.UTF_8), PendingLockPayload::class.java).lockUpdatedAt
        }.getOrNull()

    private suspend fun resolveLockUpdatedAt(
        entityType: String,
        entityId: Long,
        fallback: String?
    ): String? {
        val existing = localDataService.getSyncOperationForEntity(entityType, entityId) ?: return fallback
        return extractLockUpdatedAt(existing.payload) ?: fallback
    }

    private suspend fun updateCreateOperationPayload(
        entityType: String,
        entityId: Long,
        updater: (ByteArray) -> ByteArray?
    ): Boolean {
        val existing = localDataService.getSyncOperationForEntity(entityType, entityId) ?: return false
        if (existing.operationType != SyncOperationType.CREATE) return false
        val updatedPayload = updater(existing.payload) ?: return false
        localDataService.enqueueSyncOperation(existing.copy(payload = updatedPayload))
        return true
    }

    private suspend fun enqueueOperation(
        entityType: String,
        entityId: Long,
        entityUuid: String,
        operationType: SyncOperationType,
        payload: ByteArray,
        priority: SyncPriority
    ) {
        localDataService.removeSyncOperationsForEntity(entityType, entityId)
        val operation = OfflineSyncQueueEntity(
            operationId = "$entityType-$entityId-${UUID.randomUUID()}",
            entityType = entityType,
            entityId = entityId,
            entityUuid = entityUuid,
            operationType = operationType,
            payload = payload,
            priority = priority
        )
        localDataService.enqueueSyncOperation(operation)
    }

    private suspend fun pushPendingEquipmentDeletion(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String? = null
    ): OfflineEquipmentEntity? {
        if (equipment.serverId == null) {
            // Never reached server; treat as resolved locally
            return equipment.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
        }

        val deleteRequest = DeleteWithTimestampRequest(updatedAt = lockUpdatedAt ?: equipment.updatedAt.toApiTimestamp())
        return runCatching {
            api.deleteEquipment(equipment.serverId, deleteRequest)
            equipment.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() -> equipment.copy(
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = now()
                )
                else -> throw error
            }
        }.onFailure {
            Log.w(TAG, "⚠️ [syncPendingEquipment] Failed to delete equipment ${equipment.uuid}", it)
        }.getOrNull()
    }

    private suspend fun pushPendingEquipmentUpsert(
        equipment: OfflineEquipmentEntity,
        projectServerId: Long,
        roomServerIdOverride: Long? = null,
        lockUpdatedAt: String? = null
    ): OfflineEquipmentEntity? {
        val roomServerId = roomServerIdOverride ?: equipment.roomId?.let { roomId ->
            localDataService.getRoom(roomId)?.serverId ?: roomId
        }
        val request = equipment.toRequest(projectServerId, roomServerId, lockUpdatedAt)
        val synced = runCatching {
            val dto = if (equipment.serverId == null) {
                api.createProjectEquipment(projectServerId, request.copy(updatedAt = null))
            } else {
                api.updateEquipment(equipment.serverId, request)
            }
            dto.toEntity().copy(
                equipmentId = equipment.equipmentId,
                uuid = equipment.uuid,
                projectId = equipment.projectId,
                roomId = equipment.roomId ?: dto.roomId,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = false,
                lastSyncedAt = now()
            )
        }.recoverCatching { error ->
            when {
                equipment.serverId != null && error.isMissingOnServer() -> {
                    val created = api.createProjectEquipment(projectServerId, request.copy(updatedAt = null))
                    created.toEntity().copy(
                        equipmentId = equipment.equipmentId,
                        uuid = equipment.uuid,
                        projectId = equipment.projectId,
                        roomId = equipment.roomId ?: created.roomId,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = now()
                    )
                }
                else -> throw error
            }
        }.onFailure { error ->
            Log.w(TAG, "⚠️ [syncPendingEquipment] Failed to push equipment ${equipment.uuid}", error)
        }.getOrNull()

        return synced
    }

    private suspend fun pushPendingMoistureLogDeletion(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
    ): OfflineMoistureLogEntity? {
        if (log.serverId == null) {
            return log.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = true,
                lastSyncedAt = now()
            )
        }

        val deleteRequest = DeleteWithTimestampRequest(updatedAt = lockUpdatedAt ?: log.updatedAt.toApiTimestamp())
        return runCatching {
            api.deleteMoistureLog(log.serverId, deleteRequest)
            log.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
        }.recoverCatching { error ->
            when {
                error.isMissingOnServer() -> log.copy(
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = now()
                )
                else -> throw error
            }
        }.onFailure {
            Log.w(TAG, "⚠️ [syncPendingMoistureLogs] Failed to delete moisture log ${log.uuid}", it)
        }.getOrNull()
    }

    private suspend fun pushPendingMoistureLogUpsert(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
    ): OfflineMoistureLogEntity? {
        val room = localDataService.getRoom(log.roomId)
        val roomServerId = room?.serverId ?: log.roomId.takeIf { it > 0 }
        var material = localDataService.getMaterial(log.materialId)
        val projectServerId = resolveServerProjectId(log.projectId)
        if (projectServerId == null || roomServerId == null) {
            Log.w(
                TAG,
                "⚠️ [syncPendingMoistureLogs] Missing server ids (project=$projectServerId, room=$roomServerId) for log uuid=${log.uuid}"
            )
            return null
        }

        if (material?.serverId == null) {
            material = material?.let { ensureMaterialSynced(it, projectServerId, roomServerId, log) }
        }
        val materialServerId = material?.serverId ?: log.materialId.takeIf { it > 0 }
        if (materialServerId == null) {
            Log.w(
                TAG,
                "⚠️ [syncPendingMoistureLogs] Unable to resolve material server id for log uuid=${log.uuid}"
            )
            return null
        }

        val request = log.toRequest(lockUpdatedAt)
        val synced = runCatching {
            val dto = if (log.serverId == null) {
                api.createMoistureLog(roomServerId, materialServerId, request.copy(updatedAt = null))
            } else {
                api.updateMoistureLog(log.serverId, request)
            }
            dto.toEntity()?.copy(
                logId = log.logId,
                uuid = log.uuid,
                projectId = log.projectId,
                roomId = log.roomId,
                materialId = materialServerId,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                isDeleted = false,
                lastSyncedAt = now()
            )
        }.recoverCatching { error ->
            when {
                log.serverId != null && error.isMissingOnServer() -> {
                    val created = api.createMoistureLog(roomServerId, materialServerId, request.copy(updatedAt = null))
                    created.toEntity()?.copy(
                        logId = log.logId,
                        uuid = log.uuid,
                        projectId = log.projectId,
                        roomId = log.roomId,
                        materialId = materialServerId,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        isDeleted = false,
                        lastSyncedAt = now()
                    )
                }
                else -> throw error
            }
        }.onFailure { error ->
            Log.w(TAG, "⚠️ [syncPendingMoistureLogs] Failed to push moisture log ${log.uuid}", error)
        }.getOrNull()

        return synced
    }

    private suspend fun ensureMaterialSynced(
        material: OfflineMaterialEntity,
        projectServerId: Long,
        roomServerId: Long,
        log: OfflineMoistureLogEntity
    ): OfflineMaterialEntity? {
        val request = DamageMaterialRequest(
            name = material.name.ifBlank { "Material ${material.materialId}" },
            damageTypeId = DEFAULT_DAMAGE_TYPE_ID,
            description = material.description,
            dryingGoal = material.description?.let { parseTargetMoisture(it) },
            idempotencyKey = material.uuid
        )

        val created = runCatching {
            api.createProjectDamageMaterial(projectServerId, request.copy(updatedAt = null))
        }.recoverCatching { error ->
            if (error.isConflict()) {
                val existing = runCatching { api.getProjectDamageMaterials(projectServerId).data }
                    .getOrElse { emptyList() }
                    .firstOrNull { dto ->
                        dto.title.equals(material.name, ignoreCase = true) &&
                            (dto.damageTypeId ?: DEFAULT_DAMAGE_TYPE_ID) == request.damageTypeId
                    }
                existing ?: throw error
            } else {
                throw error
            }
        }.onFailure {
            Log.w(
                TAG,
                "⚠️ [materials] Failed to create damage material for log uuid=${log.uuid} name=${material.name}",
                it
            )
        }.getOrNull() ?: return material

        runCatching {
            api.attachDamageMaterialToRoom(
                roomId = roomServerId,
                damageMaterialId = created.id,
                body = DamageMaterialRequest(
                    name = created.title ?: material.name,
                    damageTypeId = created.damageTypeId ?: DEFAULT_DAMAGE_TYPE_ID,
                    dryingGoal = material.description?.let { parseTargetMoisture(it) },
                    idempotencyKey = material.uuid
                )
            )
        }.onFailure {
            Log.w(
                TAG,
                "⚠️ [materials] Failed to attach damage material ${created.id} to room $roomServerId for log uuid=${log.uuid}",
                it
            )
        }

        val timestamp = now()
        val updated = material.copy(
            serverId = created.id,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = (material.syncVersion + 1),
            lastSyncedAt = timestamp,
            updatedAt = timestamp
        )
        localDataService.saveMaterials(listOf(updated))
        return updated
    }

    private suspend fun restoreDeletedParents(targets: Map<String, List<Long>>) {
        targets.forEach { (type, ids) ->
            val filteredIds = ids.filter { it > 0 }
            if (filteredIds.isEmpty()) return@forEach

            runCatching {
                api.restoreDeletedRecords(RestoreRecordsRequest(type = type, ids = filteredIds))
            }
                .onSuccess { response ->
                    Log.d(
                        TAG,
                        "♻️ [syncRestore] type=$type restored=${response.restored.size}, already_restored=${response.alreadyRestored.size}, not_found=${response.notFound.size}, unauthorized=${response.unauthorized.size}"
                    )
                }
                .onFailure { error ->
                    Log.w(TAG, "⚠️ [syncRestore] Failed to restore $type ids=${filteredIds.joinToString()}", error)
                }
        }
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = localDataService.getProject(projectId)
        return project?.serverId ?: projectId.takeIf { it > 0 }
    }

    private fun OfflineProjectEntity.withAddressFallback(
        projectAddress: ProjectAddressDto?,
        addressRequest: CreateAddressRequest?
    ): OfflineProjectEntity {
        val resolvedLine1 = listOfNotNull(
            addressLine1?.takeIf { it.isNotBlank() },
            projectAddress?.address?.takeIf { it.isNotBlank() },
            addressRequest?.address?.takeIf { it.isNotBlank() }
        ).firstOrNull()

        val resolvedLine2 = listOfNotNull(
            addressLine2?.takeIf { it.isNotBlank() },
            projectAddress?.address2?.takeIf { it.isNotBlank() },
            addressRequest?.address2?.takeIf { it.isNotBlank() }
        ).firstOrNull()

        val resolvedPropertyId = propertyId ?: projectAddress?.id

        val resolvedTitle = listOfNotNull(
            resolvedLine1,
            title.takeIf { it.isNotBlank() },
            alias?.takeIf { it.isNotBlank() },
            projectNumber?.takeIf { it.isNotBlank() },
            uid?.takeIf { it.isNotBlank() }
        ).firstOrNull() ?: title

        return copy(
            addressLine1 = resolvedLine1,
            addressLine2 = resolvedLine2,
            propertyId = resolvedPropertyId,
            title = resolvedTitle
        )
    }

    private suspend fun resolveRoomTypeIdForPayload(
        payload: PendingRoomCreationPayload
    ): Long? {
        val project = localDataService.getProject(payload.projectId)
            ?: return payload.roomTypeId.takeIf { it > 0 }
        val propertyId = project.propertyId
            ?: return payload.roomTypeId.takeIf { it > 0 }
        val property = localDataService.getProperty(propertyId)
            ?: return payload.roomTypeId.takeIf { it > 0 }
        val propertyServerId = property.serverId ?: return null

        val roomTypes = runCatching {
            api.getPropertyRoomTypes(propertyServerId, filterType = null).data
        }
            .onFailure { error ->
                Log.w(TAG, "⚠️ [resolveRoomTypeId] Failed to fetch property room types for property=$propertyServerId", error)
            }
            .getOrElse { return null }

        val match = pickPropertyRoomTypeMatch(payload, roomTypes)
        if (match == null) {
            Log.w(
                TAG,
                "⚠️ [resolveRoomTypeId] No property room type match for roomTypeId=${payload.roomTypeId} name=${payload.roomTypeName} (property=$propertyServerId); falling back to payload id"
            )
            return payload.roomTypeId.takeIf { it > 0 }
        }

        Log.d(
            TAG,
            "✅ [resolveRoomTypeId] Resolved room type '${payload.roomTypeName}' to property id=${match.id} for property=$propertyServerId"
        )
        return match.id
    }

    private fun pickPropertyRoomTypeMatch(
        payload: PendingRoomCreationPayload,
        roomTypes: List<RoomTypeDto>
    ): RoomTypeDto? {
        if (roomTypes.isEmpty()) return null
        val idMatch = payload.roomTypeId
            .takeIf { it > 0 }
            ?.let { id -> roomTypes.firstOrNull { it.id == id } }
        if (idMatch != null) return idMatch
        val name = payload.roomTypeName?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        val nameMatches = roomTypes.filter { it.name?.equals(name, ignoreCase = true) == true }
        if (nameMatches.isEmpty()) return null
        if (nameMatches.size == 1) return nameMatches.first()
        val exteriorMatches = nameMatches.filter { isExteriorRoomType(it.type) == payload.isExterior }
        return exteriorMatches.firstOrNull() ?: nameMatches.first()
    }

    private fun isExteriorRoomType(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return false
        return normalized.contains("external") || normalized.contains("exterior")
    }

    companion object {
        private const val TAG = "API"
    }
}
