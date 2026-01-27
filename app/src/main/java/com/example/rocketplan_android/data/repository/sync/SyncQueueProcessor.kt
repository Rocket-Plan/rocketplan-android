package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.sync.SyncOperationOutcome
import com.example.rocketplan_android.data.sync.SyncQueueLogger
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportConversationEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportMessageEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.data.repository.mapper.PendingLocationCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.PendingProjectCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingLocationUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.PendingAtmosphericLogCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingSupportConversationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingSupportMessagePayload
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.google.gson.Gson
import retrofit2.HttpException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.rocketplan_android.util.UuidUtils
import java.util.Date
import java.util.UUID
import kotlin.math.min
import kotlin.text.Charsets
import com.example.rocketplan_android.data.repository.sync.handlers.AtmosphericLogPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.EquipmentPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.LocationPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.MoistureLogPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.NotePushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.OperationOutcome as HandlerOutcome
import com.example.rocketplan_android.data.repository.sync.handlers.PhotoPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.ProjectPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.PropertyPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.PushHandlerContext
import com.example.rocketplan_android.data.repository.sync.handlers.RoomPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.SupportPushHandler
import com.example.rocketplan_android.data.repository.sync.handlers.PendingProjectSyncResult

data class PendingOperationResult(
    val createdProjects: List<PendingProjectSyncResult> = emptyList()
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
    private val syncQueueLogger = SyncQueueLogger(remoteLogger)

    // Handler context and handlers for extracted push logic
    private val handlerContext by lazy {
        PushHandlerContext(
            api = api,
            localDataService = localDataService,
            gson = gson,
            remoteLogger = remoteLogger,
            syncProjectEssentials = syncProjectEssentials,
            persistProperty = persistProperty,
            imageProcessorQueueManagerProvider = imageProcessorQueueManagerProvider
        )
    }
    private val projectHandler by lazy { ProjectPushHandler(handlerContext) }
    private val propertyHandler by lazy { PropertyPushHandler(handlerContext) }
    private val locationHandler by lazy { LocationPushHandler(handlerContext) }
    private val roomHandler by lazy { RoomPushHandler(handlerContext, isNetworkAvailable) }
    private val noteHandler by lazy { NotePushHandler(handlerContext) }
    private val equipmentHandler by lazy { EquipmentPushHandler(handlerContext) }
    private val moistureLogHandler by lazy { MoistureLogPushHandler(handlerContext) }
    private val photoHandler by lazy { PhotoPushHandler(handlerContext) }
    private val atmosphericLogHandler by lazy { AtmosphericLogPushHandler(handlerContext) }
    private val supportHandler by lazy { SupportPushHandler(handlerContext) }

    private enum class OperationOutcome {
        SUCCESS,
        SKIP,
        RETRY,
        DROP,
        CONFLICT_PENDING
    }

    /**
     * Check if an entity has been deleted locally (isDeleted = true).
     * If so, we should drop the sync operation to avoid race conditions where
     * a delete clears the sync queue but a concurrent processor re-inserts operations.
     */
    private suspend fun isEntityDeleted(entityType: String, entityId: Long, entityUuid: String?): Boolean {
        return when (entityType) {
            "project" -> localDataService.getProject(entityId)?.isDeleted == true
            "room" -> localDataService.getRoom(entityId)?.isDeleted == true
            "location" -> localDataService.getLocation(entityId)?.isDeleted == true
            "note" -> entityUuid?.let { localDataService.getNoteByUuid(it)?.isDeleted } == true
            "photo" -> localDataService.getPhoto(entityId)?.isDeleted == true
            "equipment" -> localDataService.getEquipment(entityId)?.isDeleted == true
            "moisture_log" -> entityUuid?.let { localDataService.getMoistureLogByUuid(it)?.isDeleted } == true
            "atmospheric_log" -> entityUuid?.let { localDataService.getAtmosphericLogByUuid(it)?.isDeleted } == true
            // For other entity types, assume not deleted if we can't check
            else -> false
        }
    }

    private fun HandlerOutcome.toLocal(): OperationOutcome = when (this) {
        HandlerOutcome.SUCCESS -> OperationOutcome.SUCCESS
        HandlerOutcome.SKIP -> OperationOutcome.SKIP
        HandlerOutcome.RETRY -> OperationOutcome.RETRY
        HandlerOutcome.DROP -> OperationOutcome.DROP
        HandlerOutcome.CONFLICT_PENDING -> OperationOutcome.CONFLICT_PENDING
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

        // Start a sync session for metrics tracking
        val session = syncQueueLogger.startSession()
        Log.d(TAG, "📊 Starting sync session ${session.sessionId} with ${operations.size} operations")

        suspend fun handleOperation(
            operation: OfflineSyncQueueEntity,
            label: String,
            block: suspend () -> OperationOutcome
        ) {
            val startTime = System.currentTimeMillis()
            // Check if entity was deleted (e.g., by cascadeDeleteProject) to prevent race condition
            // where delete clears sync queue but concurrent processor is mid-execution
            if (operation.operationType != SyncOperationType.DELETE && isEntityDeleted(operation.entityType, operation.entityId, operation.entityUuid)) {
                Log.d(TAG, "⏭️ [$label] Entity ${operation.entityType}/${operation.entityId} is deleted locally, dropping operation")
                localDataService.removeSyncOperation(operation.operationId)
                return
            }

            runCatching { block() }
                .onSuccess { outcome ->
                    val durationMs = System.currentTimeMillis() - startTime
                    val metricsOutcome = when (outcome) {
                        OperationOutcome.SUCCESS -> SyncOperationOutcome.SUCCESS
                        OperationOutcome.SKIP -> SyncOperationOutcome.SKIP
                        OperationOutcome.RETRY -> SyncOperationOutcome.SKIP
                        OperationOutcome.DROP -> SyncOperationOutcome.DROP
                        OperationOutcome.CONFLICT_PENDING -> SyncOperationOutcome.CONFLICT_PENDING
                    }
                    syncQueueLogger.recordOperationResult(
                        entityType = operation.entityType,
                        operationType = operation.operationType.name,
                        outcome = metricsOutcome,
                        durationMs = durationMs
                    )
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
                        OperationOutcome.SKIP -> {
                            // Track skip count to prevent infinite retry loops
                            val nextSkip = operation.skipCount + 1
                            if (nextSkip >= operation.maxSkips) {
                                // Build detailed error message about what's likely missing
                                val dependencyHint = buildDependencyHint(operation)
                                val errorMessage = "Sync blocked: $dependencyHint. Will auto-retry when app returns to foreground or you can manually sync the project."

                                Log.w(TAG, "⚠️ [$label] Max skips reached for op=${operation.operationId}, marking as failed. $dependencyHint")
                                remoteLogger?.log(
                                    LogLevel.ERROR,
                                    TAG,
                                    "Sync operation exhausted max skips - marking for user retry",
                                    mapOf(
                                        "operationId" to operation.operationId,
                                        "entityType" to operation.entityType,
                                        "entityId" to operation.entityId.toString(),
                                        "operationType" to operation.operationType.name,
                                        "skipCount" to nextSkip.toString(),
                                        "maxSkips" to operation.maxSkips.toString(),
                                        "dependencyHint" to dependencyHint
                                    )
                                )
                                // Mark as FAILED but preserve the operation for manual retry
                                // The resetFailedOperationsForRetry() will pick these up on next foreground
                                val failed = operation.copy(
                                    skipCount = nextSkip,
                                    status = SyncStatus.FAILED,
                                    lastAttemptAt = Date(),
                                    errorMessage = errorMessage
                                )
                                localDataService.enqueueSyncOperation(failed)
                            } else {
                                // Increment skip count with exponential backoff
                                val backoffSeconds = kotlin.math.min(30 * 60, 30 * (1 shl kotlin.math.min(nextSkip, 6)))
                                val updated = operation.copy(
                                    skipCount = nextSkip,
                                    lastAttemptAt = Date(),
                                    scheduledAt = Date(System.currentTimeMillis() + backoffSeconds * 1000L)
                                )
                                localDataService.enqueueSyncOperation(updated)
                            }
                        }
                        OperationOutcome.RETRY -> Unit
                        OperationOutcome.CONFLICT_PENDING -> {
                            // Mark operation as having a conflict pending user resolution
                            Log.i(TAG, "⚠️ [$label] Conflict pending for op=${operation.operationId}")
                            remoteLogger?.log(
                                LogLevel.INFO,
                                TAG,
                                "Sync operation has conflict pending user resolution",
                                mapOf(
                                    "operationId" to operation.operationId,
                                    "entityType" to operation.entityType,
                                    "entityId" to operation.entityId.toString(),
                                    "operationType" to operation.operationType.name
                                )
                            )
                            val conflictOp = operation.copy(
                                status = SyncStatus.CONFLICT,
                                lastAttemptAt = Date(),
                                errorMessage = "Conflict pending user resolution"
                            )
                            localDataService.enqueueSyncOperation(conflictOp)
                        }
                    }
                }
                .onFailure { error ->
                    val durationMs = System.currentTimeMillis() - startTime
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
                    // Record failure in metrics
                    syncQueueLogger.recordOperationResult(
                        entityType = operation.entityType,
                        operationType = operation.operationType.name,
                        outcome = SyncOperationOutcome.FAILURE,
                        durationMs = durationMs
                    )
                    markSyncOperationFailure(operation, error)
                }
        }

        operations.forEach { operation ->
            when (operation.entityType) {
                "project" -> handleOperation(operation, "pending:project") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> {
                            val result = projectHandler.handleCreate(operation)
                            if (result != null) {
                                createdProjects += result
                                OperationOutcome.SUCCESS
                            } else {
                                OperationOutcome.DROP
                            }
                        }
                        SyncOperationType.UPDATE -> projectHandler.handleUpdate(operation).toLocal()
                        SyncOperationType.DELETE -> projectHandler.handleDelete(operation).toLocal()
                    }
                }
                "property" -> handleOperation(operation, "pending:property") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> propertyHandler.handleCreate(operation).toLocal()
                        SyncOperationType.UPDATE -> propertyHandler.handleUpdate(operation).toLocal()
                        SyncOperationType.DELETE -> propertyHandler.handleDelete(operation).toLocal()
                    }
                }
                "location" -> handleOperation(operation, "pending:location") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> locationHandler.handleCreate(operation).toLocal()
                        SyncOperationType.UPDATE -> locationHandler.handleUpdate(operation).toLocal()
                        SyncOperationType.DELETE -> locationHandler.handleDelete(operation).toLocal()
                    }
                }
                "room" -> handleOperation(operation, "pending:room") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> roomHandler.handleCreate(operation).toLocal()
                        SyncOperationType.UPDATE -> roomHandler.handleUpdate(operation).toLocal()
                        SyncOperationType.DELETE -> roomHandler.handleDelete(operation).toLocal()
                    }
                }
                "note" -> handleOperation(operation, "pending:note") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE,
                        SyncOperationType.UPDATE -> noteHandler.handleUpsert(operation).toLocal()
                        SyncOperationType.DELETE -> noteHandler.handleDelete(operation).toLocal()
                    }
                }
                "equipment" -> handleOperation(operation, "pending:equipment") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE,
                        SyncOperationType.UPDATE -> equipmentHandler.handleUpsert(operation).toLocal()
                        SyncOperationType.DELETE -> equipmentHandler.handleDelete(operation).toLocal()
                    }
                }
                "moisture_log" -> handleOperation(operation, "pending:moisture") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE,
                        SyncOperationType.UPDATE -> moistureLogHandler.handleUpsert(operation).toLocal()
                        SyncOperationType.DELETE -> moistureLogHandler.handleDelete(operation).toLocal()
                    }
                }
                "photo" -> handleOperation(operation, "pending:photo") {
                    when (operation.operationType) {
                        SyncOperationType.DELETE -> photoHandler.handleDelete(operation).toLocal()
                        else -> OperationOutcome.DROP
                    }
                }
                "atmospheric_log" -> handleOperation(operation, "pending:atmospheric") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE,
                        SyncOperationType.UPDATE -> atmosphericLogHandler.handleUpsert(operation).toLocal()
                        SyncOperationType.DELETE -> atmosphericLogHandler.handleDelete(operation).toLocal()
                    }
                }
                "support_conversation" -> handleOperation(operation, "pending:support_conversation") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> supportHandler.handleConversationCreate(operation).toLocal()
                        else -> OperationOutcome.DROP
                    }
                }
                "support_message" -> handleOperation(operation, "pending:support_message") {
                    when (operation.operationType) {
                        SyncOperationType.CREATE -> supportHandler.handleMessageCreate(operation).toLocal()
                        else -> OperationOutcome.DROP
                    }
                }
                else -> {
                    Log.w(TAG, "⚠️ [processPendingOperations] Unknown operation type=${operation.entityType}, removing")
                    localDataService.removeSyncOperation(operation.operationId)
                }
            }
        }

        // End sync session and log summary
        syncQueueLogger.endSession()

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
            operationId = "project-${project.projectId}-${UuidUtils.generateUuidV7()}",
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
            operationId = "property-${property.propertyId}-${UuidUtils.generateUuidV7()}",
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
            operationId = "location-${location.locationId}-${UuidUtils.generateUuidV7()}",
            entityType = "location",
            entityId = location.locationId,
            entityUuid = location.uuid,
            operationType = SyncOperationType.CREATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
        localDataService.enqueueSyncOperation(operation)
    }

    override suspend fun enqueueLocationUpdate(
        location: OfflineLocationEntity,
        name: String?,
        floorNumber: Int?,
        isAccessible: Boolean?,
        lockUpdatedAt: String?
    ) {
        if (location.serverId == null) {
            // Location not yet synced; update the pending CREATE payload instead
            val updated = updateCreateOperationPayload("location", location.locationId) { payload ->
                val existing = runCatching {
                    gson.fromJson(String(payload, Charsets.UTF_8), PendingLocationCreationPayload::class.java)
                }.getOrNull() ?: return@updateCreateOperationPayload null
                val refreshed = existing.copy(
                    locationName = name ?: existing.locationName,
                    floorNumber = floorNumber ?: existing.floorNumber,
                    isAccessible = isAccessible ?: existing.isAccessible
                )
                gson.toJson(refreshed).toByteArray(Charsets.UTF_8)
            }
            if (!updated) {
                Log.w(TAG, "⚠️ [enqueueLocationUpdate] No pending create for location ${location.locationId}; skipping update")
            }
            return
        }
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "location",
            entityId = location.locationId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLocationUpdatePayload(
            locationId = location.locationId,
            locationUuid = location.uuid,
            name = name,
            floorNumber = floorNumber,
            isAccessible = isAccessible,
            lockUpdatedAt = resolvedLockUpdatedAt
        )
        enqueueOperation(
            entityType = "location",
            entityId = location.locationId,
            entityUuid = location.uuid,
            operationType = SyncOperationType.UPDATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
    }

    override suspend fun enqueueLocationDeletion(
        location: OfflineLocationEntity,
        lockUpdatedAt: String?
    ) {
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "location",
            entityId = location.locationId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "location",
            entityId = location.locationId,
            entityUuid = location.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.HIGH
        )
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
            operationId = "room-${room.roomId}-${UuidUtils.generateUuidV7()}",
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

    override suspend fun enqueuePropertyDeletion(
        property: OfflinePropertyEntity,
        lockUpdatedAt: String?
    ) {
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "property",
            entityId = property.propertyId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "property",
            entityId = property.propertyId,
            entityUuid = property.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.HIGH
        )
    }

    override suspend fun enqueueRoomUpdate(
        room: OfflineRoomEntity,
        isSource: Boolean,
        levelId: Long?,
        roomTypeId: Long?,
        lockUpdatedAt: String?
    ) {
        if (room.serverId == null) {
            // Room not yet synced; update the pending CREATE payload instead
            val updated = updateCreateOperationPayload("room", room.roomId) { payload ->
                val existing = runCatching {
                    gson.fromJson(String(payload, Charsets.UTF_8), PendingRoomCreationPayload::class.java)
                }.getOrNull() ?: return@updateCreateOperationPayload null
                val refreshed = existing.copy(
                    isSource = isSource,
                    levelServerId = levelId ?: existing.levelServerId,
                    roomTypeId = roomTypeId ?: existing.roomTypeId
                )
                gson.toJson(refreshed).toByteArray(Charsets.UTF_8)
            }
            if (!updated) {
                Log.w(TAG, "⚠️ [enqueueRoomUpdate] No pending create for room ${room.roomId}; skipping update")
            }
            return
        }
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "room",
            entityId = room.roomId,
            fallback = lockUpdatedAt
        )
        val payload = PendingRoomUpdatePayload(
            roomId = room.roomId,
            roomUuid = room.uuid,
            projectId = room.projectId,
            locationId = room.locationId,
            isSource = isSource,
            levelId = levelId,
            roomTypeId = roomTypeId,
            lockUpdatedAt = resolvedLockUpdatedAt
        )
        enqueueOperation(
            entityType = "room",
            entityId = room.roomId,
            entityUuid = room.uuid,
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

    override suspend fun enqueueAtmosphericLogUpsert(
        log: OfflineAtmosphericLogEntity,
        lockUpdatedAt: String?
    ) {
        val entityId = resolveEntityId(log.logId, log.uuid)
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "atmospheric_log",
            entityId = entityId,
            fallback = lockUpdatedAt
        )
        val opType = if (log.serverId == null) SyncOperationType.CREATE else SyncOperationType.UPDATE

        if (opType == SyncOperationType.CREATE) {
            // For creates, we need to store the full payload
            val project = localDataService.getProject(log.projectId)
            val room = log.roomId?.let { localDataService.getRoom(it) }
            val payload = PendingAtmosphericLogCreationPayload(
                localLogId = log.logId,
                logUuid = log.uuid,
                projectId = log.projectId,
                projectUuid = project?.uuid,
                roomId = log.roomId,
                roomUuid = room?.uuid,
                idempotencyKey = log.uuid
            )
            enqueueOperation(
                entityType = "atmospheric_log",
                entityId = entityId,
                entityUuid = log.uuid,
                operationType = opType,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                priority = SyncPriority.MEDIUM
            )
        } else {
            // For updates, just use the lock payload
            val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
            enqueueOperation(
                entityType = "atmospheric_log",
                entityId = entityId,
                entityUuid = log.uuid,
                operationType = opType,
                payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
                priority = SyncPriority.MEDIUM
            )
        }
    }

    override suspend fun enqueueAtmosphericLogDeletion(
        log: OfflineAtmosphericLogEntity,
        lockUpdatedAt: String?
    ) {
        val entityId = resolveEntityId(log.logId, log.uuid)
        val resolvedLockUpdatedAt = resolveLockUpdatedAt(
            entityType = "atmospheric_log",
            entityId = entityId,
            fallback = lockUpdatedAt
        )
        val payload = PendingLockPayload(lockUpdatedAt = resolvedLockUpdatedAt)
        enqueueOperation(
            entityType = "atmospheric_log",
            entityId = entityId,
            entityUuid = log.uuid,
            operationType = SyncOperationType.DELETE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
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
            // Use a combination of both UUID halves to avoid collision from using only mostSignificantBits
            val parsed = UUID.fromString(uuid)
            parsed.mostSignificantBits xor parsed.leastSignificantBits
        }.getOrElse { uuid.hashCode().toLong() }

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            gson.fromJson(String(payload, Charsets.UTF_8), PendingLockPayload::class.java).lockUpdatedAt
        }.getOrNull()

    /**
     * Build a human-readable hint about what dependency might be blocking a sync operation.
     * This helps with debugging and provides useful error messages to users.
     */
    private fun buildDependencyHint(operation: OfflineSyncQueueEntity): String {
        return when (operation.entityType) {
            "room" -> "Room creation waiting for project/location to sync"
            "location" -> "Location creation waiting for property to sync"
            "property" -> "Property creation waiting for project to sync"
            "note" -> "Note sync waiting for parent room/project to sync"
            "photo" -> "Photo sync waiting for parent room to sync"
            "equipment" -> "Equipment sync waiting for parent room to sync"
            "moisture_log" -> "Moisture log sync waiting for parent room to sync"
            "atmospheric_log" -> "Atmospheric log sync waiting for parent room to sync"
            "support_conversation" -> "Support conversation sync waiting for server connection"
            "support_message" -> "Support message sync waiting for conversation to sync"
            else -> "Sync operation waiting for dependencies to resolve"
        }
    }

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
            operationId = "$entityType-$entityId-${UuidUtils.generateUuidV7()}",
            entityType = entityType,
            entityId = entityId,
            entityUuid = entityUuid,
            operationType = operationType,
            payload = payload,
            priority = priority
        )
        localDataService.enqueueSyncOperation(operation)
    }

    override suspend fun enqueueSupportConversationCreation(
        conversation: OfflineSupportConversationEntity,
        initialMessageBody: String
    ) {
        val payload = PendingSupportConversationPayload(
            localConversationId = conversation.conversationId,
            conversationUuid = conversation.uuid,
            categoryId = conversation.categoryId,
            subject = conversation.subject,
            initialMessageBody = initialMessageBody,
            idempotencyKey = conversation.uuid
        )
        val operation = OfflineSyncQueueEntity(
            operationId = "support_conversation-${conversation.conversationId}-${UuidUtils.generateUuidV7()}",
            entityType = "support_conversation",
            entityId = conversation.conversationId,
            entityUuid = conversation.uuid,
            operationType = SyncOperationType.CREATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
        localDataService.enqueueSyncOperation(operation)
    }

    override suspend fun enqueueSupportMessageCreation(
        message: OfflineSupportMessageEntity
    ) {
        val payload = PendingSupportMessagePayload(
            localMessageId = message.messageId,
            messageUuid = message.uuid,
            conversationId = message.conversationId,
            conversationServerId = message.conversationServerId,
            body = message.body,
            idempotencyKey = message.uuid
        )
        val operation = OfflineSyncQueueEntity(
            operationId = "support_message-${message.messageId}-${UuidUtils.generateUuidV7()}",
            entityType = "support_message",
            entityId = message.messageId,
            entityUuid = message.uuid,
            operationType = SyncOperationType.CREATE,
            payload = gson.toJson(payload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.MEDIUM
        )
        localDataService.enqueueSyncOperation(operation)
    }

    companion object {
        private const val TAG = "API"
    }
}
