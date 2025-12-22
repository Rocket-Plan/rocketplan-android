package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
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
import com.example.rocketplan_android.data.model.offline.DeletedRecordsResponse
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.model.offline.WorkScopeSheetDto
import com.example.rocketplan_android.data.model.offline.AddWorkScopeItemsRequest
import com.example.rocketplan_android.data.model.offline.WorkScopeItemRequest
import com.example.rocketplan_android.data.repository.mapper.*
import com.example.rocketplan_android.data.repository.sync.PhotoSyncService
import com.example.rocketplan_android.data.repository.sync.PropertySyncService
import com.example.rocketplan_android.data.repository.sync.ProjectSyncService
import com.example.rocketplan_android.data.repository.sync.PendingOperationResult
import com.example.rocketplan_android.data.repository.sync.RoomSyncService
import com.example.rocketplan_android.data.repository.sync.SyncQueueProcessor
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.example.rocketplan_android.util.DateUtils
import com.google.gson.Gson
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

// Pagination limits - balance between network efficiency and memory usage
private const val NOTES_PAGE_LIMIT = 30

// Checkpoint keys for incremental sync - stored in SyncCheckpointStore
private const val DELETED_RECORDS_CHECKPOINT_KEY = "deleted_records_global"
private const val SERVER_TIME_CHECKPOINT_KEY = "deleted_records_server_date"

// Entity types to check for server-side deletions during sync
private val DEFAULT_DELETION_TYPES = listOf(
    "projects",
    "photos",
    "notes",
    "rooms",
    "locations",
    "equipment",
    "damage_materials",
    "damage_material_room_logs",
    "atmospheric_logs",
    "work_scope_actions"
)

// How far back to check for deleted records on first sync (30 days)
private val DEFAULT_DELETION_LOOKBACK_MS = TimeUnit.DAYS.toMillis(30)

// Status value for locally-created projects not yet synced to server
private const val OFFLINE_PENDING_STATUS = "pending_offline"

private fun projectNotesKey(projectId: Long) = "project_notes_$projectId"
private fun projectDamagesKey(projectId: Long) = "project_damages_$projectId"
private fun projectAtmosLogsKey(projectId: Long) = "project_atmos_logs_$projectId"
data class PhotoDeletionResult(val synced: Boolean)
data class RoomDeletionResult(val synced: Boolean)

class OfflineSyncRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val photoCacheScheduler: PhotoCacheScheduler,
    private val syncCheckpointStore: SyncCheckpointStore,
    private val roomTypeRepository: RoomTypeRepository,
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
            syncQueueProcessorProvider = { syncQueueProcessor },
            ioDispatcher = ioDispatcher
        )
    }

    private val roomSyncService by lazy {
        RoomSyncService(
            api = api,
            localDataService = localDataService,
            roomTypeRepository = roomTypeRepository,
            syncQueueProcessorProvider = { syncQueueProcessor },
            syncProjectEssentials = ::syncProjectEssentials,
            logLocalDeletion = ::logLocalDeletion,
            removePhotoFiles = ::removePhotoFiles,
            ioDispatcher = ioDispatcher
        )
    }

    private val syncQueueProcessor by lazy {
        SyncQueueProcessor(
            api = api,
            localDataService = localDataService,
            syncProjectEssentials = { projectId -> syncProjectEssentials(projectId) },
            persistProperty = { projectId, property, propertyTypeValue, existing ->
                propertySyncService.persistProperty(projectId, property, propertyTypeValue, existing)
            },
            imageProcessorQueueManagerProvider = { imageProcessorQueueManager },
            ioDispatcher = ioDispatcher
        )
    }

    private val gson = Gson()

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
        val serverProjectId = resolveServerProjectId(projectId)
            ?: return@withContext SyncResult.failure(
                SyncSegment.PROJECT_ESSENTIALS,
                IllegalStateException("Project $projectId has not been synced to server"),
                System.currentTimeMillis() - startTime
            )
        Log.d("API", "🔄 [syncProjectEssentials] Starting navigation chain for project $projectId (server=$serverProjectId)")

        val detail = runCatching { api.getProjectDetail(serverProjectId).data }
            .onFailure {
                Log.e("API", "❌ [syncProjectEssentials] Failed", it)
                val duration = System.currentTimeMillis() - startTime
                return@withContext SyncResult.failure(SyncSegment.PROJECT_ESSENTIALS, it, duration)
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
        val embeddedPropertyType = detail.properties?.firstOrNull()?.propertyType
        val resolvedPropertyType = detail.propertyType ?: property.propertyType ?: embeddedPropertyType
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
        Log.d("API", "🏠 [syncProjectEssentials] Attaching property $resolvedId to project $projectId with propertyType=$resolvedPropertyType (detail.propertyType=${detail.propertyType}, property.propertyType=${property.propertyType}, embeddedPropertyType=$embeddedPropertyType)")
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
    ): OfflineNoteEntity = withContext(ioDispatcher) {
        val timestamp = now()
        val pending = OfflineNoteEntity(
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            roomId = roomId,
            photoId = photoId,
            content = content,
            categoryId = categoryId,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false
        )
        localDataService.saveNotes(listOf(pending))
        val saved = localDataService.getNoteByUuid(pending.uuid) ?: pending
        syncQueueProcessor.enqueueNoteUpsert(saved)
        saved
    }

    suspend fun updateNote(
        note: OfflineNoteEntity,
        newContent: String
    ): OfflineNoteEntity = withContext(ioDispatcher) {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty() || trimmed == note.content) return@withContext note
        val updated = note.copy(
            content = trimmed,
            updatedAt = now(),
            isDirty = true,
            syncStatus = SyncStatus.PENDING
        )
        val lockUpdatedAt = note.updatedAt.toApiTimestamp()
        localDataService.saveNote(updated)
        syncQueueProcessor.enqueueNoteUpsert(updated, lockUpdatedAt)
        updated
    }

    suspend fun deleteNote(projectId: Long, note: OfflineNoteEntity) = withContext(ioDispatcher) {
        val lockUpdatedAt = note.updatedAt.toApiTimestamp()
        val updated = note.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = now()
        )
        localDataService.saveNote(updated)

        if (note.serverId == null) {
            localDataService.removeSyncOperationsForEntity(entityType = "note", entityId = note.noteId)
            logLocalDeletion("note", note.noteId, note.uuid)
            localDataService.saveNote(
                updated.copy(
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = now()
                )
            )
        } else {
            syncQueueProcessor.enqueueNoteDeletion(updated, lockUpdatedAt)
        }
    }

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
     */
    suspend fun syncProjectSegments(
        projectId: Long,
        segments: List<SyncSegment>
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
            coroutineContext.ensureActive()
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(
            "API",
            "✅ [syncProjectSegments] Completed ${segments.joinToString { it.name }} for project $projectId in ${duration}ms"
        )
        return results
    }

    /**
     * Background-friendly sync pass that skips essentials but fetches metadata and photos.
     */
    suspend fun syncProjectContent(projectId: Long): List<SyncResult> =
        syncProjectSegments(
            projectId,
            listOf(
                SyncSegment.PROJECT_METADATA,
                SyncSegment.ALL_ROOM_PHOTOS,
                SyncSegment.PROJECT_LEVEL_PHOTOS
            )
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
    ): OfflineEquipmentEntity = withContext(ioDispatcher) {
        val timestamp = now()
        val existing = equipmentId?.let { localDataService.getEquipment(it) }
            ?: uuid?.let { localDataService.getEquipmentByUuid(it) }
        val lockUpdatedAt = existing?.serverId?.let { existing.updatedAt.toApiTimestamp() }

        val resolvedId = existing?.equipmentId ?: equipmentId ?: -System.currentTimeMillis()
        val resolvedUuid = existing?.uuid ?: uuid ?: UUID.randomUUID().toString()

        val entity = OfflineEquipmentEntity(
            equipmentId = resolvedId,
            serverId = existing?.serverId,
            uuid = resolvedUuid,
            projectId = existing?.projectId ?: projectId,
            roomId = roomId ?: existing?.roomId,
            type = type,
            brand = brand,
            model = model,
            serialNumber = serialNumber,
            quantity = quantity,
            status = status,
            startDate = startDate ?: existing?.startDate,
            endDate = endDate ?: existing?.endDate,
            createdAt = existing?.createdAt ?: timestamp,
            updatedAt = timestamp,
            lastSyncedAt = existing?.lastSyncedAt,
            syncStatus = SyncStatus.PENDING,
            syncVersion = existing?.syncVersion ?: 0,
            isDirty = true,
            isDeleted = false
        )

        localDataService.saveEquipment(listOf(entity))
        val saved = localDataService.getEquipmentByUuid(resolvedUuid) ?: entity
        syncQueueProcessor.enqueueEquipmentUpsert(saved, lockUpdatedAt)
        saved
    }

    suspend fun upsertMoistureLogOffline(
        log: OfflineMoistureLogEntity
    ): OfflineMoistureLogEntity = withContext(ioDispatcher) {
        val existing = localDataService.getMoistureLogByUuid(log.uuid)
        val lockUpdatedAt = existing?.serverId?.let { existing.updatedAt.toApiTimestamp() }
        localDataService.saveMoistureLogs(listOf(log))
        val saved = localDataService.getMoistureLogByUuid(log.uuid) ?: log
        syncQueueProcessor.enqueueMoistureLogUpsert(saved, lockUpdatedAt)
        saved
    }

    suspend fun deleteEquipmentOffline(
        equipmentId: Long? = null,
        uuid: String? = null
    ): OfflineEquipmentEntity? = withContext(ioDispatcher) {
        val existing = when {
            equipmentId != null -> localDataService.getEquipment(equipmentId)
            uuid != null -> localDataService.getEquipmentByUuid(uuid)
            else -> null
        } ?: return@withContext null

        val lockUpdatedAt = existing.serverId?.let { existing.updatedAt.toApiTimestamp() }
        val timestamp = now()
        val updated = existing.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = timestamp
        )
        localDataService.saveEquipment(listOf(updated))
        if (existing.serverId == null) {
            localDataService.removeSyncOperationsForEntity(entityType = "equipment", entityId = existing.equipmentId)
            logLocalDeletion("equipment", existing.equipmentId, existing.uuid)
            val cleaned = updated.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
            localDataService.saveEquipment(listOf(cleaned))
            return@withContext cleaned
        }
        syncQueueProcessor.enqueueEquipmentDeletion(updated, lockUpdatedAt)
        updated
    }

    private suspend fun syncWorkScopesForProject(projectId: Long): Int {
        val start = System.currentTimeMillis()
        val roomIds = localDataService.getServerRoomIdsForProject(projectId).distinct()
        if (roomIds.isEmpty()) {
            Log.d("API", "ℹ️ [syncWorkScopes] No server room IDs for project $projectId; skipping work scope sync")
            return 0
        }

        var total = 0
        roomIds.forEach { roomId ->
            coroutineContext.ensureActive()
            total += syncRoomWorkScopes(projectId, roomId)
        }

        val duration = System.currentTimeMillis() - start
        Log.d(
            "API",
            "✅ [syncWorkScopes] Synced $total work scope items across ${roomIds.size} rooms for project $projectId in ${duration}ms"
        )
        return total
    }

    private suspend fun syncDamagesForProject(projectId: Long): Int {
        val start = System.currentTimeMillis()
        val roomIds = localDataService.getServerRoomIdsForProject(projectId).distinct()
        if (roomIds.isEmpty()) {
            Log.d("API", "ℹ️ [syncDamages] No server room IDs for project $projectId; skipping damage sync")
            return 0
        }
        var total = 0
        roomIds.forEach { roomId ->
            coroutineContext.ensureActive()
            total += syncRoomDamages(projectId, roomId)
        }
        val duration = System.currentTimeMillis() - start
        Log.d(
            "API",
            "✅ [syncDamages] Synced $total damages across ${roomIds.size} rooms for project $projectId in ${duration}ms"
        )
        return total
    }

    private suspend fun syncMoistureLogsForProject(projectId: Long): Int {
        val start = System.currentTimeMillis()
        val roomIds = localDataService.getServerRoomIdsForProject(projectId).distinct()
        if (roomIds.isEmpty()) {
            Log.d("API", "ℹ️ [syncMoistureLogs] No server room IDs for project $projectId; skipping moisture log sync")
            return 0
        }
        var total = 0
        roomIds.forEach { roomId ->
            coroutineContext.ensureActive()
            total += syncRoomMoistureLogs(projectId, roomId)
        }
        val duration = System.currentTimeMillis() - start
        Log.d(
            "API",
            "✅ [syncMoistureLogs] Synced $total moisture logs across ${roomIds.size} rooms for project $projectId in ${duration}ms"
        )
        return total
    }

    suspend fun fetchWorkScopeCatalog(companyId: Long): List<WorkScopeSheetDto> = withContext(ioDispatcher) {
        val start = System.currentTimeMillis()
        runCatching { api.getWorkScopeCatalog(companyId).data }
            .onFailure { Log.e("API", "❌ [workScopeCatalog] Failed for companyId=$companyId", it) }
            .getOrElse { emptyList() }
            .also { sheets ->
                val duration = System.currentTimeMillis() - start
                Log.d("API", "📑 [workScopeCatalog] Fetched ${sheets.size} sheets for companyId=$companyId in ${duration}ms")
            }
    }

    suspend fun addWorkScopeItems(
        projectId: Long,
        roomId: Long,
        items: List<WorkScopeItemRequest>
    ): Boolean = withContext(ioDispatcher) {
        if (items.isEmpty()) return@withContext true
        val body = AddWorkScopeItemsRequest(selectedItems = items)
        val start = System.currentTimeMillis()
        val response = runCatching { api.addRoomWorkScopeItems(roomId, body) }
            .onFailure { Log.e("API", "❌ [addWorkScopeItems] Failed roomId=$roomId projectId=$projectId", it) }
            .getOrNull()
        val duration = System.currentTimeMillis() - start
        if (response?.isSuccessful == true) {
            Log.d("API", "✅ [addWorkScopeItems] Pushed ${items.size} items for roomId=$roomId in ${duration}ms")
            true
        } else {
            Log.e("API", "❌ [addWorkScopeItems] Non-success response roomId=$roomId projectId=$projectId code=${response?.code()}")
            false
        }
    }

    /**
     * Full project sync: syncs essentials + metadata + all photos.
     * Uses modular functions to avoid duplication.
     */
    suspend fun syncProjectGraph(projectId: Long, skipPhotos: Boolean = false): List<SyncResult> = withContext(ioDispatcher) {
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

        val results = syncProjectSegments(projectId, segments)
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
    suspend fun syncProjectMetadata(projectId: Long): SyncResult = withContext(ioDispatcher) {
        val serverProjectId = resolveServerProjectId(projectId)
            ?: return@withContext SyncResult.failure(
                SyncSegment.PROJECT_METADATA,
                IllegalStateException("Project $projectId has not been synced to server"),
                0
            )
        val startTime = System.currentTimeMillis()
        Log.d("API", "🔄 [syncProjectMetadata] Starting for project $projectId (server=$serverProjectId)")
        var itemCount = 0

        val notesCheckpointKey = projectNotesKey(projectId)
        val notesSince = syncCheckpointStore.updatedSinceParam(notesCheckpointKey)
        // Notes
        Log.d(
            "API",
            "🔄 [syncProjectMetadata] Fetching notes with pagination (limit=$NOTES_PAGE_LIMIT) since ${notesSince ?: "beginning"}"
        )
        runCatching {
            fetchAllPages { page ->
                api.getProjectNotes(
                    projectId = serverProjectId,
                    page = page,
                    limit = NOTES_PAGE_LIMIT,
                    updatedSince = notesSince
                )
            }
        }
            .onSuccess { notes ->
                localDataService.saveNotes(notes.mapNotNull { it.toEntity() })
                itemCount += notes.size
                Log.d("API", "📝 [syncProjectMetadata] Saved ${notes.size} notes (paginated)")
                notes.latestTimestamp { it.updatedAt }
                    ?.let { syncCheckpointStore.updateCheckpoint(notesCheckpointKey, it) }
            }
            .onFailure { Log.e("API", "❌ [syncProjectMetadata] Failed to fetch notes", it) }
        ensureActive()

        // Equipment
        runCatching { api.getProjectEquipment(serverProjectId) }
            .onSuccess { response ->
                val equipment = response.data
                localDataService.saveEquipment(equipment.map { it.toEntity() })
                itemCount += equipment.size
                Log.d("API", "🔧 [syncProjectMetadata] Saved ${equipment.size} equipment (single response)")
            }
            .onFailure { Log.e("API", "❌ [syncProjectMetadata] Failed to fetch equipment", it) }
        ensureActive()

        val damagesCheckpointKey = projectDamagesKey(projectId)
        val damagesSince = syncCheckpointStore.updatedSinceParam(damagesCheckpointKey)
        // Damages
        val projectDamagesFetched = runCatching { api.getProjectDamageMaterials(serverProjectId, updatedSince = damagesSince) }
            .onSuccess { response ->
                val damages = response.data
                Log.d("API", "🔍 [syncProjectMetadata] Raw damages from API: ${damages.size}, roomIds=${damages.take(5).map { it.roomId }}")
                val entities = damages.mapNotNull { it.toEntity(defaultProjectId = projectId) }
                val (scopedDamages, unscopedDamages) = entities.partition { it.roomId != null }
                if (unscopedDamages.isNotEmpty()) {
                    Log.w(
                        "API",
                        "⚠️ [syncProjectMetadata] Dropping ${unscopedDamages.size} damages with no roomId (projectId=$projectId)"
                    )
                }
                localDataService.saveDamages(scopedDamages)
                val materialEntities = damages.map { it.toMaterialEntity() }
                if (materialEntities.isNotEmpty()) {
                    localDataService.saveMaterials(materialEntities)
                }
                itemCount += scopedDamages.size
                Log.d("API", "⚠️ [syncProjectMetadata] Saved ${scopedDamages.size} damages (from ${damages.size} API results)")
                damages.latestTimestamp { it.updatedAt }
                    ?.let { syncCheckpointStore.updateCheckpoint(damagesCheckpointKey, it) }
            }
            .onFailure { error ->
                Log.e("API", "❌ [syncProjectMetadata] Failed to fetch damages", error)
            }
            .isSuccess
        ensureActive()

        // If project-level damages did not include room IDs, fall back to per-room fetch.
        if (!projectDamagesFetched) {
            Log.w("API", "⚠️ [syncProjectMetadata] Project damage fetch failed; falling back to per-room damage sync")
        }
        val damagesFromProject = localDataService.observeDamages(projectId).first().takeIf { it.isNotEmpty() }
        val hasRoomScopedDamages = damagesFromProject?.any { it.roomId != null } == true
        if (!hasRoomScopedDamages) {
            if (projectDamagesFetched) {
                Log.w("API", "⚠️ [syncProjectMetadata] Project damages missing roomIds; falling back to per-room damage sync (projectId=$projectId)")
            }
            val damageCount = syncDamagesForProject(projectId)
            itemCount += damageCount
        }

        val moistureCount = syncMoistureLogsForProject(projectId)
        itemCount += moistureCount
        ensureActive()

        val workScopeCount = syncWorkScopesForProject(projectId)
        itemCount += workScopeCount
        ensureActive()

        val atmosCheckpointKey = projectAtmosLogsKey(projectId)
        val atmosSince = syncCheckpointStore.updatedSinceParam(atmosCheckpointKey)
        // Atmospheric logs
        runCatching { api.getProjectAtmosphericLogs(serverProjectId, updatedSince = atmosSince) }
            .onSuccess { response ->
                val logs = response.data
                localDataService.saveAtmosphericLogs(logs.map { it.toEntity(defaultRoomId = null) })
                itemCount += logs.size
                Log.d("API", "🌡️ [syncProjectMetadata] Saved ${logs.size} atmospheric logs")
                logs.latestTimestamp { it.updatedAt }
                    ?.let { syncCheckpointStore.updateCheckpoint(atmosCheckpointKey, it) }
            }
            .onFailure { Log.e("API", "❌ [syncProjectMetadata] Failed to fetch atmospheric logs", it) }
        ensureActive()

        val duration = System.currentTimeMillis() - startTime
        Log.d("API", "✅ [syncProjectMetadata] Synced $itemCount items in ${duration}ms")
        SyncResult.success(SyncSegment.PROJECT_METADATA, itemCount, duration)
    }

    suspend fun syncRoomWorkScopes(projectId: Long, roomId: Long): Int = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val response = runCatching { api.getRoomWorkScope(roomId) }
            .onFailure { Log.e("API", "❌ [syncRoomWorkScopes] Failed for roomId=$roomId (projectId=$projectId)", it) }
            .getOrNull() ?: return@withContext 0

        val entities = response.data.mapNotNull { it.toEntity(defaultProjectId = projectId, defaultRoomId = roomId) }
        if (entities.isNotEmpty()) {
            localDataService.saveWorkScopes(entities)
        }
        val duration = System.currentTimeMillis() - startTime
        Log.d(
            "API",
            "🛠️ [syncRoomWorkScopes] Saved ${entities.size} scope items for roomId=$roomId (projectId=$projectId) in ${duration}ms"
        )
        entities.size
    }

    suspend fun syncRoomDamages(projectId: Long, roomId: Long): Int = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val response = runCatching { api.getRoomDamageMaterials(roomId) }
            .onFailure { error ->
                Log.e("API", "❌ [syncRoomDamages] Failed for roomId=$roomId (projectId=$projectId)", error)
            }
            .getOrNull() ?: return@withContext 0

        val damages = response.data
        if (damages.isEmpty()) {
            Log.d("API", "ℹ️ [syncRoomDamages] No damages returned for roomId=$roomId (projectId=$projectId)")
            return@withContext 0
        }

        val entities = damages.mapNotNull { it.toEntity(defaultProjectId = projectId, defaultRoomId = roomId) }
        if (entities.isNotEmpty()) {
            localDataService.saveDamages(entities)
            val materialEntities = damages.map { it.toMaterialEntity() }
            if (materialEntities.isNotEmpty()) {
                localDataService.saveMaterials(materialEntities)
            }
        }
        val duration = System.currentTimeMillis() - startTime
        Log.d(
            "API",
            "🛠️ [syncRoomDamages] Saved ${entities.size} damages for roomId=$roomId (projectId=$projectId) in ${duration}ms"
        )
        entities.size
    }

    suspend fun syncRoomMoistureLogs(projectId: Long, roomId: Long): Int = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val response = runCatching { api.getRoomMoistureLogs(roomId, include = "damageMaterial") }
            .onFailure { error ->
                Log.e("API", "❌ [syncRoomMoistureLogs] Failed for roomId=$roomId (projectId=$projectId)", error)
            }
            .getOrNull() ?: return@withContext 0

        // Handle flexible data format: API may return array or single object
        val logs: List<MoistureLogDto> = when {
            response.data == null -> emptyList()
            response.data.isJsonArray -> {
                gson.fromJson(response.data, Array<MoistureLogDto>::class.java)?.toList() ?: emptyList()
            }
            response.data.isJsonObject -> {
                listOfNotNull(gson.fromJson(response.data, MoistureLogDto::class.java))
            }
            else -> {
                Log.w(
                    "API",
                    "⚠️ [syncRoomMoistureLogs] Unexpected JSON format for roomId=$roomId: ${response.data?.javaClass?.simpleName}"
                )
                emptyList()
            }
        }

        if (logs.isEmpty()) {
            Log.d("API", "ℹ️ [syncRoomMoistureLogs] No moisture logs returned for roomId=$roomId (projectId=$projectId)")
            return@withContext 0
        }

        val entities = logs.mapNotNull { it.toEntity() }
        localDataService.saveMoistureLogs(entities)
        val duration = System.currentTimeMillis() - startTime
        Log.d(
            "API",
            "💧 [syncRoomMoistureLogs] Saved ${entities.size} moisture logs for roomId=$roomId (projectId=$projectId) in ${duration}ms"
        )
        entities.size
    }

    suspend fun syncDeletedRecords(types: List<String> = DEFAULT_DELETION_TYPES) = withContext(ioDispatcher) {
        val now = Date()
        val lastServerDate = syncCheckpointStore.getCheckpoint(SERVER_TIME_CHECKPOINT_KEY)
        val rawSinceDate = syncCheckpointStore.getCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY)
            ?: Date(now.time - DEFAULT_DELETION_LOOKBACK_MS)
        val sinceDate = when {
            lastServerDate != null && rawSinceDate.after(lastServerDate) -> {
                Log.w(
                    "API",
                    "⚠️ [syncDeletedRecords] Future checkpoint $rawSinceDate clamped to last server time $lastServerDate (device now=$now)"
                )
                syncCheckpointStore.updateCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY, lastServerDate)
                lastServerDate
            }
            lastServerDate == null && rawSinceDate.after(now) -> {
                val clamped = Date(0) // Safe epoch fallback avoids future-dated requests
                Log.w(
                    "API",
                    "⚠️ [syncDeletedRecords] Future checkpoint $rawSinceDate with no server time; clamping to epoch (device now=$now)"
                )
                syncCheckpointStore.updateCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY, clamped)
                clamped
            }
            else -> rawSinceDate
        }
        val sinceParam = DateUtils.formatApiDate(sinceDate)
        val filteredTypes = types.filter { it.isNotBlank() }

        Log.d("API", "🔄 [syncDeletedRecords] Fetching deletions since $sinceParam (types=${filteredTypes.joinToString()})")
        val response = runCatching {
            api.getDeletedRecords(
                since = sinceParam,
                types = filteredTypes.takeIf { it.isNotEmpty() }
            )
        }.onFailure {
            Log.e("API", "❌ [syncDeletedRecords] Failed to fetch deletions", it)
        }.getOrElse { return@withContext }

        if (!response.isSuccessful) {
            Log.e(
                "API",
                "❌ [syncDeletedRecords] Non-success response ${response.code()}"
            )
            return@withContext
        }

        val body = response.body()
        if (body == null) {
            Log.w("API", "⚠️ [syncDeletedRecords] Empty body; skipping apply")
            return@withContext
        }

        applyDeletedRecords(body)

        val serverTimestamp = DateUtils.parseHttpDate(response.headers()["Date"])
        if (serverTimestamp != null) {
            syncCheckpointStore.updateCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY, serverTimestamp)
            syncCheckpointStore.updateCheckpoint(SERVER_TIME_CHECKPOINT_KEY, serverTimestamp)
        } else {
            Log.w("API", "⚠️ [syncDeletedRecords] Missing Date header; checkpoint not advanced")
        }

        Log.d(
            "API",
            "✅ [syncDeletedRecords] Applied deletions projects=${body.projects.size}, rooms=${body.rooms.size}, photos=${body.photos.size}, notes=${body.notes.size}"
        )
    }

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
        ignoreCheckpoint: Boolean = false
    ): SyncResult = photoSyncService.syncRoomPhotos(projectId, roomId, ignoreCheckpoint)

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

    private suspend fun applyDeletedRecords(response: DeletedRecordsResponse) {
        localDataService.markProjectsDeleted(response.projects)
        localDataService.markRoomsDeleted(response.rooms)
        localDataService.markLocationsDeleted(response.locations)
        localDataService.markPhotosDeleted(response.photos)
        localDataService.markNotesDeleted(response.notes)
        localDataService.markEquipmentDeleted(response.equipment)
        localDataService.markDamagesDeleted(response.damageMaterials)
        localDataService.markAtmosphericLogsDeleted(response.atmosphericLogs)
        localDataService.markMoistureLogsDeleted(response.moistureLogs)
        localDataService.markWorkScopesDeleted(response.workScopeActions)
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
