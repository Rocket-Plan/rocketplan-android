package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.cache.PhotoCacheManager
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.CreateCompanyProjectRequest
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.model.offline.WorkScopeSheetDto
import com.example.rocketplan_android.data.model.offline.WorkScopeItemRequest
import com.example.rocketplan_android.data.repository.mapper.*
import com.example.rocketplan_android.data.repository.sync.DeletedRecordsSyncService
import com.example.rocketplan_android.data.repository.sync.EquipmentSyncService
import com.example.rocketplan_android.data.repository.sync.MoistureLogSyncService
import com.example.rocketplan_android.data.repository.sync.NoteSyncService
import com.example.rocketplan_android.data.repository.sync.PhotoSyncService
import com.example.rocketplan_android.data.repository.sync.ProjectMetadataSyncService
import com.example.rocketplan_android.data.repository.sync.PropertySyncService
import com.example.rocketplan_android.data.repository.sync.ProjectSyncService
import com.example.rocketplan_android.data.repository.sync.PendingOperationResult
import com.example.rocketplan_android.data.repository.sync.RoomSyncService
import com.example.rocketplan_android.data.repository.sync.SyncQueueProcessor
import com.example.rocketplan_android.data.repository.sync.WorkScopeSyncService
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.example.rocketplan_android.util.DateUtils
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.util.Date
import java.util.UUID

// Status value for locally-created projects not yet synced to server
private const val OFFLINE_PENDING_STATUS = "pending_offline"

data class PhotoDeletionResult(val synced: Boolean)
data class RoomDeletionResult(val synced: Boolean)

class OfflineSyncRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val photoCacheScheduler: PhotoCacheScheduler,
    private val syncCheckpointStore: SyncCheckpointStore,
    private val roomTypeRepository: RoomTypeRepository,
    private val photoCacheManager: PhotoCacheManager? = null,
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var imageProcessorQueueManager: ImageProcessorQueueManager? = null

    // Lazily initialized services for delegation
    private val photoSyncService by lazy {
        PhotoSyncService(
            api = api,
            localDataService = localDataService,
            syncCheckpointStore = syncCheckpointStore,
            photoCacheScheduler = photoCacheScheduler,
            remoteLogger = remoteLogger,
            ioDispatcher = ioDispatcher
        )
    }

    private val projectSyncService by lazy {
        ProjectSyncService(
            api = api,
            localDataService = localDataService,
            syncCheckpointStore = syncCheckpointStore,
            ioDispatcher = ioDispatcher
        )
    }

    private val propertySyncService by lazy {
        PropertySyncService(
            api = api,
            localDataService = localDataService,
            roomTypeRepository = roomTypeRepository,
            syncQueueEnqueuer = { syncQueueProcessor },
            ioDispatcher = ioDispatcher
        )
    }

    private val roomSyncService by lazy {
        RoomSyncService(
            api = api,
            localDataService = localDataService,
            roomTypeRepository = roomTypeRepository,
            syncQueueEnqueuer = { syncQueueProcessor },
            syncProjectEssentials = ::syncProjectEssentials,
            logLocalDeletion = ::logLocalDeletion,
            removePhotoFiles = ::removePhotoFiles,
            ioDispatcher = ioDispatcher
        )
    }

    private val noteSyncService by lazy {
        NoteSyncService(
            localDataService = localDataService,
            syncQueueEnqueuer = { syncQueueProcessor },
            logLocalDeletion = ::logLocalDeletion,
            ioDispatcher = ioDispatcher
        )
    }

    private val equipmentSyncService by lazy {
        EquipmentSyncService(
            localDataService = localDataService,
            syncQueueEnqueuer = { syncQueueProcessor },
            logLocalDeletion = ::logLocalDeletion,
            ioDispatcher = ioDispatcher
        )
    }

    private val moistureLogSyncService by lazy {
        MoistureLogSyncService(
            localDataService = localDataService,
            syncQueueEnqueuer = { syncQueueProcessor },
            ioDispatcher = ioDispatcher
        )
    }

    private val workScopeSyncService by lazy {
        WorkScopeSyncService(
            api = api,
            localDataService = localDataService,
            ioDispatcher = ioDispatcher
        )
    }

    private val projectMetadataSyncService by lazy {
        ProjectMetadataSyncService(
            api = api,
            localDataService = localDataService,
            syncCheckpointStore = syncCheckpointStore,
            workScopeSyncService = workScopeSyncService,
            resolveServerProjectId = ::resolveServerProjectId,
            ioDispatcher = ioDispatcher
        )
    }

    private val deletedRecordsSyncService by lazy {
        DeletedRecordsSyncService(
            api = api,
            localDataService = localDataService,
            syncCheckpointStore = syncCheckpointStore,
            photoCacheManager = photoCacheManager,
            ioDispatcher = ioDispatcher
        )
    }

    private val syncQueueProcessor: SyncQueueProcessor by lazy {
        SyncQueueProcessor(
            api = api,
            localDataService = localDataService,
            syncProjectEssentials = { projectId -> syncProjectEssentials(projectId) },
            persistProperty = { projectId, property, propertyTypeValue, existing, forcePropertyIdUpdate ->
                propertySyncService.persistProperty(projectId, property, propertyTypeValue, existing, forcePropertyIdUpdate = forcePropertyIdUpdate)
            },
            imageProcessorQueueManagerProvider = { imageProcessorQueueManager },
            remoteLogger = remoteLogger,
            ioDispatcher = ioDispatcher
        )
    }

    fun attachImageProcessorQueueManager(manager: ImageProcessorQueueManager) {
        imageProcessorQueueManager = manager
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = localDataService.getProject(projectId)
        return project?.serverId ?: projectId.takeIf { it > 0 }
    }

    // ============================================================================
    // Project Sync Operations - Delegated to ProjectSyncService
    // ============================================================================

    suspend fun syncCompanyProjects(
        companyId: Long,
        assignedToMe: Boolean = false,
        forceFullSync: Boolean = false
    ): Set<Long> = projectSyncService.syncCompanyProjects(companyId, assignedToMe, forceFullSync)

    suspend fun syncUserProjects(userId: Long) =
        projectSyncService.syncUserProjects(userId)

    suspend fun deleteProject(localProjectId: Long) = withContext(ioDispatcher) {
        Log.d("API", "🗑️ [deleteProject] Starting delete for local project ID: $localProjectId")

        // Get the project to retrieve its server ID
        val project = localDataService.getAllProjects().firstOrNull { it.projectId == localProjectId }
            ?: run {
                Log.e("API", "❌ [deleteProject] Project not found locally: $localProjectId")
                throw Exception("Project not found locally")
            }

        Log.d("API", "🗑️ [deleteProject] Found project - title: ${project.title}, serverId: ${project.serverId}, uuid: ${project.uuid}")

        val lockUpdatedAt = project.updatedAt.toApiTimestamp()

        localDataService.deleteProject(localProjectId)
        val markedProject = localDataService.getProject(localProjectId) ?: project
        val marked = markedProject.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = now()
        )
        localDataService.saveProjects(listOf(marked))

        val serverId = marked.serverId
        if (serverId == null) {
            localDataService.removeSyncOperationsForEntity(entityType = "project", entityId = marked.projectId)
            logLocalDeletion("project", marked.projectId, marked.uuid)
            val cleaned = marked.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
            localDataService.saveProjects(listOf(cleaned))
            Log.d("API", "✅ [deleteProject] Deleted local-only project $localProjectId")
            return@withContext
        }

        syncQueueProcessor.enqueueProjectDeletion(marked, lockUpdatedAt)
        Log.d("API", "🗑️ [deleteProject] Queued delete for project serverId=$serverId (local=$localProjectId)")
    }

    /**
     * Syncs the essential navigation chain: Project → Property → Levels → Rooms → Albums.
     * This provides everything needed to navigate from project to photos without additional syncs.
     *
     * Skips: Notes, equipment, damages, logs (not in navigation path)
     */
    suspend fun syncProjectEssentials(projectId: Long): SyncResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()

        // Guard: require company context before syncing. Capture once to avoid race if cleared mid-sync.
        val companyId = localDataService.currentCompanyIdOrNull
        if (companyId == null) {
            Log.w("API", "⚠️ [syncProjectEssentials] No company context set; skipping sync for project $projectId")
            return@withContext SyncResult.incomplete(
                SyncSegment.PROJECT_ESSENTIALS,
                IncompleteReason.NO_COMPANY_CONTEXT,
                System.currentTimeMillis() - startTime
            )
        }

        val serverProjectId = resolveServerProjectId(projectId)
            ?: return@withContext SyncResult.failure(
                SyncSegment.PROJECT_ESSENTIALS,
                IllegalStateException("Project $projectId has not been synced to server"),
                System.currentTimeMillis() - startTime
            )
        Log.d("API", "🔄 [syncProjectEssentials] Starting navigation chain for project $projectId (server=$serverProjectId)")

        val detail = runCatching { api.getProjectDetail(serverProjectId).data }
            .onFailure { error ->
                val duration = System.currentTimeMillis() - startTime
                // If project returns 404, it MAY have been deleted on server.
                // Only cascade delete if the local project has no unsynced changes (isDirty=false).
                // This prevents data loss from transient 404s (auth issues, eventual consistency, etc.)
                if (error is HttpException && error.code() == 404) {
                    // Use captured company context to scope the lookup - prevents cross-tenant issues
                    val localProject = localDataService.getProjectByServerId(serverProjectId, companyId)
                    if (localProject == null) {
                        // No local project found for this company - nothing to delete
                        Log.i("API", "🗑️ [syncProjectEssentials] Project $serverProjectId not found (404) and no local record exists for companyId=$companyId")
                        return@withContext SyncResult.success(SyncSegment.PROJECT_ESSENTIALS, 0, duration)
                    }
                    // Check for any pending changes - project-level OR child entities (photos, notes, etc.)
                    val pendingOpsCount = localDataService.countPendingSyncOpsForProject(localProject.projectId)
                    if (localProject.isDirty || pendingOpsCount > 0) {
                        // Project or children have unsynced changes - don't delete, treat as sync failure
                        Log.w(
                            "API",
                            "⚠️ [syncProjectEssentials] Project $serverProjectId returned 404 but has pending changes " +
                                "(isDirty=${localProject.isDirty}, pendingOps=$pendingOpsCount). " +
                                "Preserving local data to avoid losing unsynced changes."
                        )
                        return@withContext SyncResult.failure(
                            SyncSegment.PROJECT_ESSENTIALS,
                            IllegalStateException("Project not found on server but has unsynced local changes"),
                            duration
                        )
                    }
                    Log.i("API", "🗑️ [syncProjectEssentials] Project $serverProjectId not found (404), cascade deleting locally")
                    val cachedPhotos = localDataService.cascadeDeleteProjectsByServerIds(
                        serverIds = listOf(serverProjectId),
                        companyId = localProject.companyId
                    )
                    if (cachedPhotos.isNotEmpty()) {
                        photoCacheManager?.removeCachedPhotos(cachedPhotos)
                    }
                    return@withContext SyncResult.success(SyncSegment.PROJECT_ESSENTIALS, 0, duration)
                }
                Log.e("API", "❌ [syncProjectEssentials] Failed", error)
                return@withContext SyncResult.failure(SyncSegment.PROJECT_ESSENTIALS, error, duration)
            }
            .getOrNull() ?: run {
                val duration = System.currentTimeMillis() - startTime
                return@withContext SyncResult.failure(
                    SyncSegment.PROJECT_ESSENTIALS,
                    Exception("Project detail returned null"),
                    duration
                )
            }

        var itemCount = 0

        // Guard against phantom project 0 - API sometimes returns empty/default DTOs
        if (detail.id == 0L) {
            Log.e("API", "❌ [syncProjectEssentials] API returned project with id=0, skipping save")
            val duration = System.currentTimeMillis() - startTime
            return@withContext SyncResult.failure(
                SyncSegment.PROJECT_ESSENTIALS,
                Exception("API returned invalid project with id=0"),
                duration
            )
        }

        // Clean up any phantom room with ID 0 (legacy bug)
        localDataService.deletePhantomRoom()

        // Save project entity (preserve property link if list sync already populated it)
        val existingProject = localDataService.getProject(projectId) ?: localDataService.getProject(detail.id)
        localDataService.saveProjects(listOf(detail.toEntity(existing = existingProject)))
        itemCount++
        ensureActive()

        // Save USERS (essential for photo metadata)
        detail.users?.let {
            localDataService.saveUsers(it.map { user -> user.toEntity() })
            itemCount += it.size
        }
        ensureActive()

        // Save embedded SNAPSHOTS from detail (quick preview)
        detail.locations?.let {
            localDataService.saveLocations(it.map { loc -> loc.toEntity(defaultProjectId = detail.id) })
            itemCount += it.size
        }
        ensureActive()

        detail.rooms?.let { rooms ->
            val resolvedRooms = rooms.mapNotNull { room ->
                val existing = roomSyncService.resolveExistingRoomForSync(projectId, room)
                if (room.id <= 0 && existing == null) {
                    Log.w(
                        "API",
                        "📴 [syncProjectEssentials] Skipping room with invalid id=${room.id} title=${room.title ?: room.name}"
                    )
                    return@mapNotNull null
                }
                room.toEntity(existing, projectId = detail.id, locationId = room.locationId)
            }
            localDataService.saveRooms(resolvedRooms)
            itemCount += rooms.size
        }
        ensureActive()

        detail.photos?.let {
            if (photoSyncService.persistPhotos(it)) itemCount += it.size
        }
        ensureActive()

        // === NAVIGATION CHAIN: Property → Levels → Rooms ===

        // 1. Property
        val property = propertySyncService.fetchProjectProperty(serverProjectId, detail) ?: run {
            val duration = System.currentTimeMillis() - startTime
            // If the project doesn't have a property yet, this is expected - skip property work.
            // Check both local state and API response to avoid skipping when a property exists.
            val hasPropertyReference = existingProject?.propertyId != null ||
                detail.propertyId != null ||
                !detail.properties.isNullOrEmpty()
            if (!hasPropertyReference) {
                Log.d(
                    "API",
                    "ℹ️ [syncProjectEssentials] No property yet for project $projectId; skipping property sync"
                )
                return@withContext SyncResult.success(SyncSegment.PROJECT_ESSENTIALS, itemCount, duration)
            }
            Log.w(
                "API",
                "⚠️ [syncProjectEssentials] No property found for project $projectId; returning incomplete result"
            )
            return@withContext SyncResult.incomplete(
                SyncSegment.PROJECT_ESSENTIALS,
                IncompleteReason.MISSING_PROPERTY,
                duration,
                itemCount
            )
        }
        Log.d("API", "🏠 [syncProjectEssentials] Property DTO received: id=${property.id}, address=${property.address}, city=${property.city}, state=${property.state}, zip=${property.postalCode}, lat=${property.latitude}, lng=${property.longitude}")
        Log.d("API", "🏠 [syncProjectEssentials] Project address fallback: address=${detail.address?.address}, city=${detail.address?.city}, state=${detail.address?.state}, zip=${detail.address?.zip}")
        // Try to get propertyType from: detail.propertyType, property.propertyType, or embedded properties list
        val embeddedPropertyType = detail.properties?.firstOrNull()?.resolvedPropertyType()
        val resolvedPropertyType = detail.propertyType ?: property.resolvedPropertyType() ?: embeddedPropertyType
        val existingProperty = existingProject?.propertyId?.let { localDataService.getProperty(it) }
        val entity = propertySyncService.persistProperty(
            projectId = projectId,
            property = property,
            propertyTypeValue = resolvedPropertyType,
            existing = existingProperty,
            projectAddress = detail.address,
            forceRoomTypeRefresh = false
        )
        val resolvedId = entity.serverId ?: entity.propertyId
        Log.d("API", "🏠 [syncProjectEssentials] Property Entity created: serverId=${entity.serverId}, address=${entity.address}, city=${entity.city}, state=${entity.state}, zip=${entity.zipCode}")
        Log.d("API", "🏠 [syncProjectEssentials] Attaching property $resolvedId to project $projectId with propertyType=$resolvedPropertyType (detail.propertyType=${detail.propertyType}, property.propertyType=${property.resolvedPropertyType()}, embeddedPropertyType=$embeddedPropertyType)")
        itemCount++
        ensureActive()

        // 2. Levels (Locations from property)
        var usedIncrementalSync = false
        val propertyLocations = run {
            val propertyId = property.id
            val existingLocationData = localDataService.getLatestLocationUpdate(projectId = projectId)
            val levels = runCatching { api.getPropertyLevels(propertyId) }
                .getOrNull()?.data ?: emptyList()

            val updatedSinceParam = existingLocationData?.let { DateUtils.formatApiDate(it) }
            if (updatedSinceParam != null) {
                Log.d("API", "🔄 [FAST] Requesting locations for property $propertyId since $updatedSinceParam (incremental)")
                usedIncrementalSync = true
            } else {
                Log.d("API", "🔄 [FAST] Requesting locations for property $propertyId (full sync - first run)")
            }

            val nested = runCatching {
                api.getPropertyLocations(
                    propertyId,
                    updatedSince = updatedSinceParam
                )
            }
                .getOrNull()?.data ?: emptyList()

            Log.d("API", "✅ [FAST] Received ${levels.size} levels + ${nested.size} nested locations for property $propertyId")
            levels + nested
        }

        val locationIds = mutableSetOf<Long>()
        if (propertyLocations.isNotEmpty()) {
            localDataService.saveLocations(
                propertyLocations.map { it.toEntity(defaultProjectId = projectId) }
            )
            locationIds += propertyLocations.map { it.id }
            itemCount += propertyLocations.size
        }
        ensureActive()

        // 3. Rooms for each location
        locationIds.distinct().forEach { locationId ->
            val rooms = roomSyncService.fetchRoomsForLocation(locationId)
            if (rooms.isNotEmpty()) {
                val resolvedRooms = rooms.map { room ->
                val existing = roomSyncService.resolveExistingRoomForSync(projectId, room)
                room.toEntity(
                    existing = existing,
                    projectId = projectId,
                        locationId = room.locationId ?: locationId
                    )
                }
                localDataService.saveRooms(resolvedRooms)
                itemCount += rooms.size
            }
            ensureActive()
        }

        // 4. Relink room-scoped data (ensures foreign keys are correct)
        runCatching { localDataService.relinkRoomScopedData() }
            .onFailure { Log.e("API", "❌ [syncProjectEssentials] Relink failed", it) }
        ensureActive()

        // 5. Albums (needed for photo organization)
        runCatching {
            fetchAllPages { page -> api.getProjectAlbums(projectId, page) }
        }.onSuccess { albums ->
            val albumEntities = albums.map { it.toEntity(defaultProjectId = projectId) }
            localDataService.saveAlbums(albumEntities)
            itemCount += albums.size
        }
        ensureActive()

        val duration = System.currentTimeMillis() - startTime
        val syncMode = if (usedIncrementalSync) "incremental" else "full"
        Log.d("API", "✅ [syncProjectEssentials] Synced $itemCount items in ${duration}ms ($syncMode)")
        Log.d("API", "   Navigation chain: Property → ${locationIds.size} Levels → Rooms → Albums")
        SyncResult.success(SyncSegment.PROJECT_ESSENTIALS, itemCount, duration)
    }

    suspend fun createAddress(
        request: CreateAddressRequest
    ) = withContext(ioDispatcher) {
        runCatching {
            Log.d("API", "🏠 [createAddress] Creating address for ${request.address}")
            api.createAddress(request).data
        }
    }

    suspend fun createCompanyProject(
        companyId: Long,
        request: CreateCompanyProjectRequest,
        projectAddress: ProjectAddressDto? = null,
        addressRequest: CreateAddressRequest? = null
    ): Result<OfflineProjectEntity> = withContext(ioDispatcher) {
        val addressReq = addressRequest
            ?: throw IllegalStateException("Address request is required for offline project creation")
        val idempotencyKey = request.idempotencyKey ?: UUID.randomUUID().toString()
        val pending = createPendingProject(
            companyId = companyId,
            statusValue = request.projectStatusId.toString(),
            projectAddress = projectAddress,
            addressRequest = addressReq,
            idempotencyKey = idempotencyKey
        )
        syncQueueProcessor.enqueueProjectCreation(
            project = pending,
            companyId = companyId,
            statusId = request.projectStatusId,
            addressRequest = addressReq,
            idempotencyKey = idempotencyKey
        )
        Log.d("API", "🗃️ [createCompanyProject] Queued project create for company=$companyId (local=${pending.projectId})")
        Result.success(pending)
    }

    suspend fun updateProjectAlias(
        projectId: Long,
        alias: String
    ): Result<OfflineProjectEntity> = withContext(ioDispatcher) {
        val normalizedAlias = alias.trim().takeIf { it.isNotEmpty() }
            ?: return@withContext Result.failure(IllegalArgumentException("Alias cannot be blank"))

        runCatching {
            val project = localDataService.getProject(projectId)
                ?: throw IllegalStateException("Project not found locally")
            val lockUpdatedAt = project.updatedAt.toApiTimestamp()
            val updated = project.copy(
                alias = normalizedAlias,
                updatedAt = now(),
                isDirty = true,
                syncStatus = SyncStatus.PENDING
            )
            localDataService.saveProjects(listOf(updated))
            syncQueueProcessor.enqueueProjectUpdate(updated, lockUpdatedAt)
            updated
        }
    }

    suspend fun updateProjectStatus(
        projectId: Long,
        status: ProjectStatus
    ): Result<OfflineProjectEntity> = withContext(ioDispatcher) {
        runCatching {
            val project = localDataService.getProject(projectId)
                ?: throw IllegalStateException("Project not found locally")
            if (project.status.equals(status.apiValue, ignoreCase = true)) {
                return@runCatching project
            }
            val lockUpdatedAt = project.updatedAt.toApiTimestamp()
            val updated = project.copy(
                status = status.apiValue,
                updatedAt = now(),
                isDirty = true,
                syncStatus = SyncStatus.PENDING
            )
            localDataService.saveProjects(listOf(updated))
            syncQueueProcessor.enqueueProjectUpdate(updated, lockUpdatedAt)
            updated
        }
    }

    suspend fun createProjectProperty(
        projectId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?,
        idempotencyKey: String? = null
    ): Result<OfflinePropertyEntity> =
        propertySyncService.createProjectProperty(projectId, request, propertyTypeValue, idempotencyKey)

    suspend fun updateProjectProperty(
        projectId: Long,
        propertyId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?
    ): Result<OfflinePropertyEntity> =
        propertySyncService.updateProjectProperty(projectId, propertyId, request, propertyTypeValue)

    suspend fun createRoom(
        projectId: Long,
        roomName: String,
        roomTypeId: Long,
        roomTypeName: String? = null,
        isSource: Boolean = false,
        isExterior: Boolean = false,
        idempotencyKey: String? = null
    ): Result<OfflineRoomEntity> =
        roomSyncService.createRoom(projectId, roomName, roomTypeId, roomTypeName, isSource, isExterior, idempotencyKey)

    suspend fun createDefaultLocationAndRoom(
        projectId: Long,
        propertyTypeValue: String?,
        locationName: String,
        seedDefaultRoom: Boolean
    ): Result<Unit> =
        roomSyncService.createDefaultLocationAndRoom(projectId, propertyTypeValue, locationName, seedDefaultRoom)

    suspend fun createNote(
        projectId: Long,
        content: String,
        roomId: Long? = null,
        categoryId: Long? = null,
        photoId: Long? = null
    ): OfflineNoteEntity =
        noteSyncService.createNote(projectId, content, roomId, categoryId, photoId)

    suspend fun updateNote(
        note: OfflineNoteEntity,
        newContent: String
    ): OfflineNoteEntity =
        noteSyncService.updateNote(note, newContent)

    suspend fun deleteNote(projectId: Long, note: OfflineNoteEntity) =
        noteSyncService.deleteNote(projectId, note)

    suspend fun deleteRoom(projectId: Long, roomId: Long): RoomDeletionResult =
        roomSyncService.deleteRoom(projectId, roomId)

    suspend fun deletePhoto(photoId: Long): PhotoDeletionResult = withContext(ioDispatcher) {
        val photo = localDataService.getPhoto(photoId)
            ?: throw IllegalArgumentException("Photo not found: $photoId")
        val lockUpdatedAt = photo.updatedAt.toApiTimestamp()
        val timestamp = now()
        val marked = photo.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = timestamp
        )
        localDataService.savePhotos(listOf(marked))
        removePhotoFiles(photo)

        photo.roomId?.let { roomId ->
            runCatching { localDataService.refreshRoomPhotoSnapshot(roomId) }
                .onFailure {
                    Log.w("API", "⚠️ [deletePhoto] Failed to refresh photo snapshot for room $roomId", it)
                }
        }

        val serverId = photo.serverId
        if (serverId == null) {
            localDataService.removeSyncOperationsForEntity(entityType = "photo", entityId = photo.photoId)
            logLocalDeletion("photo", photo.photoId, photo.uuid)
            val cleaned = marked.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
            localDataService.savePhotos(listOf(cleaned))
            return@withContext PhotoDeletionResult(synced = true)
        }
        syncQueueProcessor.enqueuePhotoDeletion(marked, lockUpdatedAt)
        PhotoDeletionResult(synced = false)
    }

    private fun removePhotoFiles(photo: OfflinePhotoEntity) {
        fun deleteIfExists(path: String?) {
            if (path.isNullOrBlank()) return
            runCatching {
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }.onFailure {
                Log.w("API", "⚠️ [deletePhoto] Failed to delete file at $path", it)
            }
        }

        deleteIfExists(photo.localPath)
        deleteIfExists(photo.cachedOriginalPath)
        deleteIfExists(photo.cachedThumbnailPath)
    }

    /**
     * Runs a set of sync segments sequentially for a project.
     * Used by SyncQueueManager to compose fast vs. background sync passes without relying on the monolithic graph.
     *
     * @param source Optional caller identifier for telemetry (e.g., "RoomDetailFragment", "SyncQueueManager")
     */
    suspend fun syncProjectSegments(
        projectId: Long,
        segments: List<SyncSegment>,
        source: String? = null
    ): List<SyncResult> {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<SyncResult>()

        for (segment in segments) {
            val result = when (segment) {
                SyncSegment.PROJECT_ESSENTIALS -> syncProjectEssentials(projectId)
                SyncSegment.PROJECT_METADATA -> syncProjectMetadata(projectId)
                SyncSegment.ALL_ROOM_PHOTOS -> syncAllRoomPhotos(projectId)
                SyncSegment.PROJECT_LEVEL_PHOTOS -> syncProjectLevelPhotos(projectId)
                SyncSegment.ROOM_PHOTOS -> {
                    Log.w(
                        "API",
                        "⚠️ [syncProjectSegments] ROOM_PHOTOS requires a roomId; skipping"
                    )
                    SyncResult.failure(
                        SyncSegment.ROOM_PHOTOS,
                        IllegalArgumentException("roomId required for ROOM_PHOTOS segment"),
                        0
                    )
                }
            }
            when (result) {
                is SyncResult.Failure -> {
                    Log.w(
                        "API",
                        "⚠️ [syncProjectSegments] Segment ${segment.name} failed for project $projectId",
                        result.error
                    )
                }
                is SyncResult.Incomplete -> {
                    Log.w(
                        "API",
                        "⚠️ [syncProjectSegments] Segment ${segment.name} incomplete (${result.reason}) for project $projectId"
                    )
                    result.error?.let { Log.w("API", "⚠️ [syncProjectSegments] Incomplete reason detail", it) }
                }
                else -> {
                    // no-op
                }
            }
            results += result
            logSegmentTelemetry(result, projectId, source)
            coroutineContext.ensureActive()
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(
            "API",
            "✅ [syncProjectSegments] Completed ${segments.joinToString { it.name }} for project $projectId in ${duration}ms"
        )
        return results
    }

    private fun logSegmentTelemetry(result: SyncResult, projectId: Long, source: String?) {
        val status = when (result) {
            is SyncResult.Success -> "success"
            is SyncResult.Failure -> "failure"
            is SyncResult.Incomplete -> "incomplete"
        }
        remoteLogger?.log(
            level = if (result.success) LogLevel.INFO else LogLevel.WARN,
            tag = "SyncTelemetry",
            message = "Segment ${result.segment.name} $status",
            metadata = buildMap {
                put("segment", result.segment.name)
                put("status", status)
                put("projectId", projectId.toString())
                put("itemsSynced", result.itemsSynced.toString())
                put("durationMs", result.durationMs.toString())
                source?.let { put("source", it) }
                result.error?.let { put("error", it.message ?: it.javaClass.simpleName) }
                if (result is SyncResult.Incomplete) {
                    put("incompleteReason", result.reason.name)
                }
            }
        )
    }

    /**
     * Background-friendly sync pass that skips essentials but fetches metadata and photos.
     */
    suspend fun syncProjectContent(projectId: Long, source: String? = null): List<SyncResult> =
        syncProjectSegments(
            projectId,
            listOf(
                SyncSegment.PROJECT_METADATA,
                SyncSegment.ALL_ROOM_PHOTOS,
                SyncSegment.PROJECT_LEVEL_PHOTOS
            ),
            source
        )

    suspend fun processPendingOperations(): PendingOperationResult =
        syncQueueProcessor.processPendingOperations()

    suspend fun upsertEquipmentOffline(
        projectId: Long,
        roomId: Long?,
        type: String,
        brand: String? = null,
        model: String? = null,
        serialNumber: String? = null,
        quantity: Int = 1,
        status: String = "active",
        startDate: Date? = null,
        endDate: Date? = null,
        equipmentId: Long? = null,
        uuid: String? = null
    ): OfflineEquipmentEntity =
        equipmentSyncService.upsertEquipmentOffline(
            projectId = projectId,
            roomId = roomId,
            type = type,
            brand = brand,
            model = model,
            serialNumber = serialNumber,
            quantity = quantity,
            status = status,
            startDate = startDate,
            endDate = endDate,
            equipmentId = equipmentId,
            uuid = uuid
        )

    suspend fun upsertMoistureLogOffline(
        log: OfflineMoistureLogEntity
    ): OfflineMoistureLogEntity =
        moistureLogSyncService.upsertMoistureLogOffline(log)

    suspend fun deleteEquipmentOffline(
        equipmentId: Long? = null,
        uuid: String? = null
    ): OfflineEquipmentEntity? =
        equipmentSyncService.deleteEquipmentOffline(equipmentId, uuid)

    suspend fun fetchWorkScopeCatalog(companyId: Long): List<WorkScopeSheetDto> =
        workScopeSyncService.fetchWorkScopeCatalog(companyId)

    suspend fun addWorkScopeItems(
        projectId: Long,
        roomId: Long,
        items: List<WorkScopeItemRequest>
    ): Boolean =
        workScopeSyncService.addWorkScopeItems(projectId, roomId, items)

    /**
     * Full project sync: syncs essentials + metadata + all photos.
     * Uses modular functions to avoid duplication.
     */
    suspend fun syncProjectGraph(projectId: Long, skipPhotos: Boolean = false, source: String? = null): List<SyncResult> = withContext(ioDispatcher) {
        val syncType = if (skipPhotos) "FAST (rooms only)" else "FULL"
        Log.d("API", "🔄 [syncProjectGraph] Starting $syncType sync for project $projectId")
        val startTime = System.currentTimeMillis()

        val segments = buildList {
            add(SyncSegment.PROJECT_ESSENTIALS)
            if (!skipPhotos) {
                add(SyncSegment.PROJECT_METADATA)
                add(SyncSegment.ALL_ROOM_PHOTOS)
                add(SyncSegment.PROJECT_LEVEL_PHOTOS)
            }
        }

        val results = syncProjectSegments(projectId, segments, source)
        val duration = System.currentTimeMillis() - startTime

        val essentialsResult = results.firstOrNull { it.segment == SyncSegment.PROJECT_ESSENTIALS }
        when (essentialsResult) {
            is SyncResult.Failure -> {
                Log.e("API", "❌ [syncProjectGraph] Failed to sync essentials", essentialsResult.error)
                return@withContext results
            }
            is SyncResult.Incomplete -> {
                Log.w(
                    "API",
                    "⚠️ [syncProjectGraph] Essentials sync incomplete (${essentialsResult.reason}); skipping follow-up segments"
                )
                return@withContext results
            }
            else -> {
                // Success, continue
            }
        }

        // Skip metadata and photos for FAST foreground sync - rooms are available now
        if (skipPhotos) {
            Log.d("API", "✅ [syncProjectGraph] FAST sync completed in ${duration}ms (metadata & photos deferred)")
            essentialsResult?.let {
                Log.d("API", "   Essentials: ${it.itemsSynced} items in ${it.durationMs}ms")
            }
            return@withContext results
        }

        val metadataResult = results.firstOrNull { it.segment == SyncSegment.PROJECT_METADATA }
        if (metadataResult != null && !metadataResult.success) {
            Log.w("API", "⚠️ [syncProjectGraph] Metadata sync failed but continuing", metadataResult.error)
        }
        val roomPhotosResult = results.firstOrNull { it.segment == SyncSegment.ALL_ROOM_PHOTOS }
        if (roomPhotosResult != null && !roomPhotosResult.success) {
            Log.w("API", "⚠️ [syncProjectGraph] Room photos sync failed but continuing", roomPhotosResult.error)
        }
        val projectPhotosResult = results.firstOrNull { it.segment == SyncSegment.PROJECT_LEVEL_PHOTOS }
        if (projectPhotosResult != null && !projectPhotosResult.success) {
            Log.w("API", "⚠️ [syncProjectGraph] Project-level photos sync failed", projectPhotosResult.error)
        }

        Log.d("API", "✅ [syncProjectGraph] FULL sync completed in ${duration}ms")
        essentialsResult?.let {
            Log.d("API", "   Essentials: ${it.itemsSynced} items in ${it.durationMs}ms")
        }
        metadataResult?.let {
            Log.d("API", "   Metadata: ${it.itemsSynced} items in ${it.durationMs}ms")
        }
        roomPhotosResult?.let {
            Log.d("API", "   Room photos: ${it.itemsSynced} photos in ${it.durationMs}ms")
        }
        projectPhotosResult?.let {
            Log.d("API", "   Project photos: ${it.itemsSynced} photos in ${it.durationMs}ms")
        }
        results
    }

    /**
     * Syncs project metadata: notes, equipment, damages, logs, work scopes.
     * These are not needed for navigation but useful for offline access.
     */
    suspend fun syncProjectMetadata(projectId: Long): SyncResult =
        projectMetadataSyncService.syncProjectMetadata(projectId)

    suspend fun syncRoomWorkScopes(projectId: Long, roomId: Long): Int =
        workScopeSyncService.syncRoomWorkScopes(projectId, roomId)

    suspend fun syncRoomDamages(projectId: Long, roomId: Long): Int =
        projectMetadataSyncService.syncRoomDamages(projectId, roomId)

    suspend fun syncRoomMoistureLogs(projectId: Long, roomId: Long): Int =
        projectMetadataSyncService.syncRoomMoistureLogs(projectId, roomId)

    suspend fun syncDeletedRecords(
        types: List<String> = DeletedRecordsSyncService.DEFAULT_TYPES
    ): Result<Unit> = deletedRecordsSyncService.syncDeletedRecords(types)

    // ============================================================================
    // Photo Sync Operations - Delegated to PhotoSyncService
    // ============================================================================

    /**
     * Syncs photos for all rooms in a project.
     * Fetches room list from database and syncs photos for each.
     */
    suspend fun syncAllRoomPhotos(projectId: Long): SyncResult =
        photoSyncService.syncAllRoomPhotos(projectId)

    /**
     * Syncs photos for a single room. Returns SyncResult for composability.
     */
    suspend fun syncRoomPhotos(
        projectId: Long,
        roomId: Long,
        ignoreCheckpoint: Boolean = false,
        source: String? = null
    ): SyncResult = photoSyncService.syncRoomPhotos(projectId, roomId, ignoreCheckpoint, source)

    /**
     * Legacy API: syncs photos for a single room without returning SyncResult.
     * Kept for backward compatibility. Prefer syncRoomPhotos() for new code.
     */
    suspend fun refreshRoomPhotos(projectId: Long, roomId: Long) =
        photoSyncService.refreshRoomPhotos(projectId, roomId)

    /**
     * Syncs project-level photos (floor, location, unit).
     * This is typically done in the background as it's not needed for room navigation.
     */
    suspend fun syncProjectLevelPhotos(projectId: Long): SyncResult {
        val serverProjectId = resolveServerProjectId(projectId)
            ?: return SyncResult.failure(
                SyncSegment.PROJECT_LEVEL_PHOTOS,
                IllegalStateException("Project $projectId has not been synced to server"),
                0
            )
        return photoSyncService.syncProjectLevelPhotos(projectId, serverProjectId)
    }

    private suspend fun <T> fetchAllPages(
        fetch: suspend (page: Int) -> PaginatedResponse<T>
    ): List<T> {
        val results = mutableListOf<T>()
        var page = 1
        while (true) {
            val response = fetch(page)
            results += response.data
            val current = response.meta?.currentPage ?: page
            val last = response.meta?.lastPage ?: current
            val hasMore = current < last && response.data.isNotEmpty()
            if (!hasMore) {
                break
            }
            page = current + 1
        }
        return results
    }

    private fun logLocalDeletion(entityType: String, entityId: Long, entityUuid: String?) {
        remoteLogger?.log(
            level = LogLevel.INFO,
            tag = "SyncQueue",
            message = "Dropped queued operation for local-only delete",
            metadata = mapOf(
                "entityType" to entityType,
                "entityId" to entityId.toString(),
                "entityUuid" to (entityUuid ?: "null")
            )
        )
    }

    private suspend fun createPendingProject(
        companyId: Long,
        statusValue: String,
        projectAddress: ProjectAddressDto?,
        addressRequest: CreateAddressRequest,
        idempotencyKey: String? = null
    ): OfflineProjectEntity {
        val timestamp = now()
        val localId = -System.currentTimeMillis()
        val resolvedTitle = listOfNotNull(
            projectAddress?.address?.takeIf { it.isNotBlank() },
            addressRequest.address?.takeIf { it.isNotBlank() },
            "Offline project"
        ).first()
        val entity = OfflineProjectEntity(
            projectId = localId,
            serverId = null,
            uuid = UUID.randomUUID().toString(),
            title = resolvedTitle,
            projectNumber = null,
            uid = null,
            alias = null,
            addressLine1 = addressRequest.address ?: projectAddress?.address,
            addressLine2 = addressRequest.address2 ?: projectAddress?.address2,
            status = statusValue.ifBlank { OFFLINE_PENDING_STATUS },
            propertyType = null,
            companyId = companyId,
            propertyId = projectAddress?.id,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null
        )
        localDataService.saveProjects(listOf(entity))
        return entity
    }

}
