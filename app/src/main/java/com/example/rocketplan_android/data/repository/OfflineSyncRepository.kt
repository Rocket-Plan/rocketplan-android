package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
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
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.CreateCompanyProjectRequest
import com.example.rocketplan_android.data.model.CreateLocationRequest
import com.example.rocketplan_android.data.model.CategoryAlbums
import com.example.rocketplan_android.data.model.CreateRoomRequest
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.UpdateProjectRequest
import com.example.rocketplan_android.data.model.DeleteProjectRequest
import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.CreateNoteRequest
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialRequest
import com.example.rocketplan_android.data.model.offline.DeletedRecordsResponse
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.EquipmentRequest
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.MoistureLogRequest
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.PaginationMeta
import com.example.rocketplan_android.data.model.offline.RestoreRecordsRequest
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomPhotoDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.repository.RoomTypeRepository.RequestType
import com.example.rocketplan_android.data.model.offline.WorkScopeCatalogItemDto
import com.example.rocketplan_android.data.model.offline.WorkScopeSheetDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.data.model.offline.AddWorkScopeItemsRequest
import com.example.rocketplan_android.data.model.offline.WorkScopeItemRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.util.parseTargetMoisture
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import retrofit2.HttpException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.text.Charsets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val ROOM_PHOTO_INCLUDE = "photo,albums,notes_count,creator"
private const val ROOM_PHOTO_PAGE_LIMIT = 30
private const val NOTES_PAGE_LIMIT = 30
private const val DELETED_RECORDS_CHECKPOINT_KEY = "deleted_records_global"
private const val SERVER_TIME_CHECKPOINT_KEY = "deleted_records_server_date"
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
private const val DEFAULT_DAMAGE_TYPE_ID: Long = 1L
private val DEFAULT_DELETION_LOOKBACK_MS = TimeUnit.DAYS.toMillis(30)
    private fun companyProjectsKey(companyId: Long, assignedOnly: Boolean) =
        if (assignedOnly) "company_projects_${companyId}_assigned" else "company_projects_$companyId"
    private fun userProjectsKey(userId: Long) = "user_projects_$userId"
    private fun roomPhotosKey(roomId: Long) = "room_photos_$roomId"
private fun floorPhotosKey(projectId: Long) = "project_floor_photos_$projectId"
private fun locationPhotosKey(projectId: Long) = "project_location_photos_$projectId"
private fun unitPhotosKey(projectId: Long) = "project_unit_photos_$projectId"
private fun projectNotesKey(projectId: Long) = "project_notes_$projectId"
    private fun projectDamagesKey(projectId: Long) = "project_damages_$projectId"
    private fun projectAtmosLogsKey(projectId: Long) = "project_atmos_logs_$projectId"
    private const val OFFLINE_PENDING_STATUS = "pending_offline"
private fun Throwable.isConflict(): Boolean = (this as? HttpException)?.code() == 409
private fun Throwable.isMissingOnServer(): Boolean = (this as? HttpException)?.code() in listOf(404, 410)
private fun Date?.toApiTimestamp(): String? = this?.let(DateUtils::formatApiDate)

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

    private val gson = Gson()
    private val roomPhotoListType = object : TypeToken<List<RoomPhotoDto>>() {}.type

    data class PendingOperationResult(
        val createdProjects: List<PendingProjectSyncResult> = emptyList()
    )

    data class PendingProjectSyncResult(
        val localProjectId: Long,
        val serverProjectId: Long
    )

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = localDataService.getProject(projectId)
        return project?.serverId ?: projectId.takeIf { it > 0 }
    }

    suspend fun syncCompanyProjects(companyId: Long, assignedToMe: Boolean = false): Set<Long> = withContext(ioDispatcher) {
        val checkpointKey = companyProjectsKey(companyId, assignedToMe)
        val updatedSince = syncCheckpointStore.updatedSinceParam(checkpointKey)
        val existingProjects = localDataService.getAllProjects().associateBy { it.serverId ?: it.projectId }
        val projects = fetchAllPages { page ->
            api.getCompanyProjects(
                companyId = companyId,
                page = page,
                updatedSince = updatedSince,
                assignedToMe = if (assignedToMe) "1" else null
            )
        }
        localDataService.saveProjects(
            projects.map { it.toEntity(existing = existingProjects[it.id], fallbackCompanyId = companyId) }
        )
        projects.latestTimestamp { it.updatedAt }
            ?.let { syncCheckpointStore.updateCheckpoint(checkpointKey, it) }
        projects.map { it.id }.toSet()
    }

    suspend fun syncUserProjects(userId: Long) = withContext(ioDispatcher) {
        val checkpointKey = userProjectsKey(userId)
        val updatedSince = syncCheckpointStore.updatedSinceParam(checkpointKey)
        val existingProjects = localDataService.getAllProjects().associateBy { it.serverId ?: it.projectId }
        val projects = fetchAllPages { page ->
            api.getUserProjects(userId = userId, page = page, updatedSince = updatedSince)
        }
        localDataService.saveProjects(
            projects.map { it.toEntity(existing = existingProjects[it.id]) }
        )
        projects.latestTimestamp { it.updatedAt }
            ?.let { syncCheckpointStore.updateCheckpoint(checkpointKey, it) }
    }

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

        enqueueProjectDeletion(marked, lockUpdatedAt)
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
            val resolvedRooms = rooms.map { room ->
                val existing = localDataService.getRoomByServerId(room.id)
                    ?: room.uuid?.let { uuid -> localDataService.getRoomByUuid(uuid) }
                room.toEntity(existing, projectId = detail.id, locationId = room.locationId)
            }
            localDataService.saveRooms(resolvedRooms)
            itemCount += rooms.size
        }
        ensureActive()

        detail.photos?.let {
            if (persistPhotos(it)) itemCount += it.size
        }
        ensureActive()

        // === NAVIGATION CHAIN: Property → Levels → Rooms ===

        // 1. Property
        val property = fetchProjectProperty(serverProjectId, detail) ?: run {
            val duration = System.currentTimeMillis() - startTime
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
        // Pass project address as fallback for missing property address fields
        val entity = property.toEntity(projectAddress = detail.address)
        Log.d("API", "🏠 [syncProjectEssentials] Property Entity created: serverId=${entity.serverId}, address=${entity.address}, city=${entity.city}, state=${entity.state}, zip=${entity.zipCode}")
        localDataService.saveProperty(entity)
        val resolvedId = entity.serverId ?: entity.propertyId
        // Try to get propertyType from: detail.propertyType, property.propertyType, or embedded properties list
        val embeddedPropertyType = detail.properties?.firstOrNull()?.propertyType
        val resolvedPropertyType = detail.propertyType ?: property.propertyType ?: embeddedPropertyType
        Log.d("API", "🏠 [syncProjectEssentials] Attaching property $resolvedId to project $projectId with propertyType=$resolvedPropertyType (detail.propertyType=${detail.propertyType}, property.propertyType=${property.propertyType}, embeddedPropertyType=$embeddedPropertyType)")
        localDataService.attachPropertyToProject(
            projectId = projectId,
            propertyId = resolvedId,
            propertyType = resolvedPropertyType
        )
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
            val rooms = fetchRoomsForLocation(locationId)
            if (rooms.isNotEmpty()) {
                val resolvedRooms = rooms.map { room ->
                    val existing = localDataService.getRoomByServerId(room.id)
                        ?: room.uuid?.let { uuid -> localDataService.getRoomByUuid(uuid) }
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
        enqueueProjectCreation(
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
            enqueueProjectUpdate(updated, lockUpdatedAt)
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
            enqueueProjectUpdate(updated, lockUpdatedAt)
            updated
        }
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

    suspend fun createProjectProperty(
        projectId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?,
        idempotencyKey: String? = null
    ): Result<OfflinePropertyEntity> = withContext(ioDispatcher) {
        val resolvedIdempotencyKey = idempotencyKey ?: request.idempotencyKey ?: UUID.randomUUID().toString()
        val project = localDataService.getAllProjects().firstOrNull { it.projectId == projectId }
            ?: throw Exception("Project not found locally")

        val pending = createPendingProperty(
            project = project,
            propertyTypeValue = propertyTypeValue,
            propertyTypeId = request.propertyTypeId,
            idempotencyKey = resolvedIdempotencyKey
        )
        Log.d("API", "🗃️ [createProjectProperty] Queued property create for project $projectId (local=${pending.propertyId})")
        Result.success(pending)
    }

    suspend fun updateProjectProperty(
        projectId: Long,
        propertyId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?
    ): Result<OfflinePropertyEntity> = withContext(ioDispatcher) {
        // Get the property to retrieve its server ID
        var property = localDataService.getProperty(propertyId)
        if (property == null) {
            Log.d("API", "🏠 [updateProjectProperty] Property $propertyId not found locally, creating new property")
            return@withContext createProjectProperty(projectId, request, propertyTypeValue)
        }
        runCatching {
            val safeProperty = property ?: throw Exception("Property lookup failed unexpectedly")
            val lockUpdatedAt = safeProperty.updatedAt.toApiTimestamp()
            val updated = safeProperty.copy(
                updatedAt = now(),
                syncStatus = SyncStatus.PENDING,
                syncVersion = safeProperty.syncVersion + 1
            )
            localDataService.saveProperty(updated)
            localDataService.attachPropertyToProject(
                projectId = projectId,
                propertyId = updated.propertyId,
                propertyType = propertyTypeValue
            )
            enqueuePropertyUpdate(
                property = updated,
                projectId = projectId,
                request = request.copy(updatedAt = null, idempotencyKey = null),
                propertyTypeValue = propertyTypeValue,
                lockUpdatedAt = lockUpdatedAt
            )
            updated
        }
    }

    suspend fun createRoom(
        projectId: Long,
        roomName: String,
        roomTypeId: Long,
        isSource: Boolean = false,
        idempotencyKey: String? = null
    ): Result<OfflineRoomEntity> = withContext(ioDispatcher) {
        val pendingRoom = localDataService.getPendingRoomForProject(projectId, roomName)
        val roomUuid = pendingRoom?.uuid ?: UUID.randomUUID().toString()
        Log.d(
            "API",
            "🆕 [createRoom] Using roomUuid=$roomUuid (pending=${pendingRoom != null}) projectId=$projectId"
        )
        val resolvedIdempotencyKey = idempotencyKey ?: roomUuid
        val localRoom = pendingRoom ?: createPendingRoom(
            projectId = projectId,
            roomName = roomName,
            roomTypeId = roomTypeId,
            isSource = isSource,
            idempotencyKey = resolvedIdempotencyKey,
            forcedUuid = roomUuid
        ) ?: throw IllegalStateException("Unable to create pending room for project $projectId")
        Result.success(localRoom)
    }

    suspend fun createDefaultLocationAndRoom(
        projectId: Long,
        propertyTypeValue: String?,
        locationName: String,
        seedDefaultRoom: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        val config = resolveLocationDefaults(propertyTypeValue)
        val idempotencyKey = UUID.randomUUID().toString()

        runCatching {
            createPendingLocation(
                projectId = projectId,
                locationName = locationName,
                config = config,
                idempotencyKey = idempotencyKey
            )

            if (seedDefaultRoom) {
                val roomTypeId = roomTypeRepository
                    .getRoomTypes(projectId, RequestType.INTERIOR, forceRefresh = false)
                    .getOrNull()
                    ?.firstOrNull()
                    ?.id

                if (roomTypeId != null) {
                    createRoom(
                        projectId = projectId,
                        roomName = locationName.ifBlank { "Room" },
                        roomTypeId = roomTypeId,
                        isSource = true
                    )
                } else {
                    Log.w(
                        "API",
                        "ℹ️ [createDefaultLocationAndRoom] No room types available; skipping default room creation"
                    )
                }
            }
            Unit
        }.onFailure { error ->
            Log.e(
                "API",
                "❌ [createDefaultLocationAndRoom] Failed to seed default location/room for project=$projectId",
                error
            )
        }
    }

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
        enqueueNoteUpsert(saved)
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
        enqueueNoteUpsert(updated, lockUpdatedAt)
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
            enqueueNoteDeletion(updated, lockUpdatedAt)
        }
    }

    suspend fun deleteRoom(projectId: Long, roomId: Long): RoomDeletionResult = withContext(ioDispatcher) {
        val room = localDataService.getRoom(roomId)
            ?: throw IllegalArgumentException("Room not found: $roomId")

        Log.d(
            "API",
            "🗑️ [deleteRoom] Marking room for deletion (projectId=$projectId, localId=${room.roomId}, serverId=${room.serverId})"
        )

        val lockUpdatedAt = room.updatedAt.toApiTimestamp()
        val timestamp = now()
        val marked = room.copy(
            isDeleted = true,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = timestamp
        )
        localDataService.saveRooms(listOf(marked))

        val snapshotRoomId = room.serverId ?: room.roomId
        runCatching { localDataService.clearRoomPhotoSnapshot(snapshotRoomId) }
            .onFailure {
                Log.w("API", "⚠️ [deleteRoom] Failed to clear photo snapshot for roomId=$snapshotRoomId", it)
            }

        val photosToDelete = localDataService.cascadeDeleteRoom(room)
        photosToDelete.forEach { photo -> removePhotoFiles(photo) }

        if (room.serverId == null) {
            localDataService.removeSyncOperationsForEntity(entityType = "room", entityId = room.roomId)
            logLocalDeletion("room", room.roomId, room.uuid)
            val cleaned = marked.copy(
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = now()
            )
            localDataService.saveRooms(listOf(cleaned))
            return@withContext RoomDeletionResult(synced = true)
        }
        enqueueRoomDeletion(marked, lockUpdatedAt)
        RoomDeletionResult(synced = false)
    }

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
        enqueuePhotoDeletion(marked, lockUpdatedAt)
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

    private enum class OperationOutcome {
        SUCCESS,
        SKIP,
        DROP
    }

    suspend fun processPendingOperations(): PendingOperationResult = withContext(ioDispatcher) {
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
                            Log.w("API", "⚠️ [$label] Dropping sync op=${operation.operationId} type=${operation.entityType}")
                            localDataService.removeSyncOperation(operation.operationId)
                        }
                        OperationOutcome.SKIP -> Unit
                    }
                }
                .onFailure { error ->
                    Log.w("API", "⚠️ [$label] Sync operation failed", error)
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
                    Log.w("API", "⚠️ [processPendingOperations] Unknown operation type=${operation.entityType}, removing")
                    localDataService.removeSyncOperation(operation.operationId)
                }
            }
        }

        PendingOperationResult(createdProjects = createdProjects)
    }

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
        enqueueEquipmentUpsert(saved, lockUpdatedAt)
        saved
    }

    suspend fun upsertMoistureLogOffline(
        log: OfflineMoistureLogEntity
    ): OfflineMoistureLogEntity = withContext(ioDispatcher) {
        val existing = localDataService.getMoistureLogByUuid(log.uuid)
        val lockUpdatedAt = existing?.serverId?.let { existing.updatedAt.toApiTimestamp() }
        localDataService.saveMoistureLogs(listOf(log))
        val saved = localDataService.getMoistureLogByUuid(log.uuid) ?: log
        enqueueMoistureLogUpsert(saved, lockUpdatedAt)
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
        enqueueEquipmentDeletion(updated, lockUpdatedAt)
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
                localDataService.saveDamages(entities)
                val materialEntities = damages.map { it.toMaterialEntity() }
                if (materialEntities.isNotEmpty()) {
                    localDataService.saveMaterials(materialEntities)
                }
                itemCount += entities.size
                Log.d("API", "⚠️ [syncProjectMetadata] Saved ${entities.size} damages (from ${damages.size} API results)")
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
        val logs = runCatching { api.getRoomMoistureLogs(roomId, include = "damageMaterial").data }
            .onFailure { error ->
                Log.e("API", "❌ [syncRoomMoistureLogs] Failed for roomId=$roomId (projectId=$projectId)", error)
            }
            .getOrNull() ?: return@withContext 0

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

    /**
     * Syncs photos for all rooms in a project.
     * Fetches room list from database and syncs photos for each.
     */
    suspend fun syncAllRoomPhotos(projectId: Long): SyncResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        Log.d("API", "🔄 [syncAllRoomPhotos] Starting for project $projectId")

        // Get current room list from database (must already be synced by syncProjectEssentials)
        val rooms = localDataService.observeRooms(projectId).first()
        if (rooms.isEmpty()) {
            Log.d("API", "⚠️ [syncAllRoomPhotos] No rooms found for project $projectId")
            return@withContext SyncResult.success(SyncSegment.ALL_ROOM_PHOTOS, 0, 0)
        }

        var totalPhotos = 0
        var failedRooms = 0
        val roomIds = rooms.mapNotNull { it.serverId }

        Log.d("API", "📸 [syncAllRoomPhotos] Fetching photos for ${roomIds.size} rooms")
        for (roomId in roomIds) {
            val result = syncRoomPhotos(projectId, roomId)
            if (result.success) {
                totalPhotos += result.itemsSynced
            } else {
                failedRooms++
                Log.w("API", "⚠️ [syncAllRoomPhotos] Failed room $roomId", result.error)
            }
            ensureActive()
        }

        if (totalPhotos > 0) {
            photoCacheScheduler.schedulePrefetch()
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d("API", "✅ [syncAllRoomPhotos] Synced $totalPhotos photos from ${roomIds.size - failedRooms}/${roomIds.size} rooms in ${duration}ms")
        SyncResult.success(SyncSegment.ALL_ROOM_PHOTOS, totalPhotos, duration)
    }

    /**
     * Syncs photos for a single room. Returns SyncResult for composability.
     */
    suspend fun syncRoomPhotos(
        projectId: Long,
        roomId: Long,
        ignoreCheckpoint: Boolean = false
    ): SyncResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val checkpointKey = roomPhotosKey(roomId)
        val checkpointValue = syncCheckpointStore.getCheckpoint(checkpointKey)
            ?.let { DateUtils.formatApiDate(it) }
            ?: "none"
        val updatedSince = if (ignoreCheckpoint) null else syncCheckpointStore.updatedSinceParam(checkpointKey)
        if (ignoreCheckpoint) {
            Log.d(
                "API",
                "🔄 [syncRoomPhotos] Requesting photos for room $roomId (project $projectId) - " +
                    "full sync (checkpoint ignored, checkpoint=$checkpointValue)"
            )
        } else if (updatedSince != null) {
            Log.d(
                "API",
                "🔄 [syncRoomPhotos] Requesting photos for room $roomId (project $projectId) since " +
                    "$updatedSince (checkpoint=$checkpointValue)"
            )
        } else {
            Log.d(
                "API",
                "🔄 [syncRoomPhotos] Requesting photos for room $roomId (project $projectId) - " +
                    "full sync (checkpoint=$checkpointValue)"
            )
        }

        val photos = runCatching {
            fetchRoomPhotoPages(
                roomId = roomId,
                projectId = projectId,
                updatedSince = updatedSince
            )
        }.onFailure { error ->
            if (error is retrofit2.HttpException && error.code() == 404) {
                Log.d("API", "INFO [syncRoomPhotos] Room $roomId has no photos (404)")
            } else {
                Log.e("API", "❌ [syncRoomPhotos] Failed to fetch photos for room $roomId", error)
                val duration = System.currentTimeMillis() - startTime
                return@withContext SyncResult.failure(SyncSegment.ROOM_PHOTOS, error, duration)
            }
        }.getOrElse { emptyList() }

        if (photos.isEmpty()) {
            Log.d("API", "ℹ️ [syncRoomPhotos] No photos returned for room $roomId")
            val duration = System.currentTimeMillis() - startTime
            return@withContext SyncResult.success(SyncSegment.ROOM_PHOTOS, 0, duration)
        }

        if (persistPhotos(photos, defaultRoomId = roomId, defaultProjectId = projectId)) {
            Log.d("API", "💾 [syncRoomPhotos] Saved ${photos.size} photos for room $roomId")
            photoCacheScheduler.schedulePrefetch()
        }
        photos.latestTimestamp { it.serverBackedTimestamp() }
            ?.let { syncCheckpointStore.updateCheckpoint(checkpointKey, it) }

        val duration = System.currentTimeMillis() - startTime
        SyncResult.success(SyncSegment.ROOM_PHOTOS, photos.size, duration)
    }

    /**
     * Legacy API: syncs photos for a single room without returning SyncResult.
     * Kept for backward compatibility. Prefer syncRoomPhotos() for new code.
     */
    suspend fun refreshRoomPhotos(projectId: Long, roomId: Long) = withContext(ioDispatcher) {
        syncRoomPhotos(projectId, roomId)
    }

    /**
     * Syncs project-level photos (floor, location, unit).
     * This is typically done in the background as it's not needed for room navigation.
     */
    suspend fun syncProjectLevelPhotos(projectId: Long): SyncResult = withContext(ioDispatcher) {
        val serverProjectId = resolveServerProjectId(projectId)
            ?: return@withContext SyncResult.failure(
                SyncSegment.PROJECT_LEVEL_PHOTOS,
                IllegalStateException("Project $projectId has not been synced to server"),
                0
            )
        val startTime = System.currentTimeMillis()
        Log.d("API", "🔄 [syncProjectLevelPhotos] Starting for project $projectId (server=$serverProjectId)")

        var totalPhotos = 0
        var failedCount = 0

        val floorKey = floorPhotosKey(projectId)
        val floorSince = syncCheckpointStore.updatedSinceParam(floorKey)
        // Floor photos
        runCatching {
            fetchAllPages { page ->
                api.getProjectFloorPhotos(serverProjectId, page, updatedSince = floorSince)
            }
                .map { it.toPhotoDto(projectId) }
        }.onSuccess { photos ->
            if (persistPhotos(photos)) {
                totalPhotos += photos.size
                Log.d("API", "📸 [syncProjectLevelPhotos] Saved ${photos.size} floor photos")
            }
            photos.latestTimestamp { it.updatedAt }
                ?.let { syncCheckpointStore.updateCheckpoint(floorKey, it) }
        }.onFailure { error ->
            failedCount++
            Log.e("API", "❌ [syncProjectLevelPhotos] Failed to fetch floor photos", error)
        }
        ensureActive()

        // Location photos (THE SLOW ONE that was blocking room loading)
        val locationKey = locationPhotosKey(projectId)
        val locationSince = syncCheckpointStore.updatedSinceParam(locationKey)
        runCatching {
            fetchAllPages { page ->
                api.getProjectLocationPhotos(serverProjectId, page, updatedSince = locationSince)
            }
                .map { it.toPhotoDto(projectId) }
        }.onSuccess { photos ->
            if (persistPhotos(photos)) {
                totalPhotos += photos.size
                Log.d("API", "📸 [syncProjectLevelPhotos] Saved ${photos.size} location photos")
            }
            photos.latestTimestamp { it.updatedAt }
                ?.let { syncCheckpointStore.updateCheckpoint(locationKey, it) }
        }.onFailure { error ->
            failedCount++
            Log.e("API", "❌ [syncProjectLevelPhotos] Failed to fetch location photos", error)
        }
        ensureActive()

        // Unit photos
        val unitKey = unitPhotosKey(projectId)
        val unitSince = syncCheckpointStore.updatedSinceParam(unitKey)
        runCatching {
            fetchAllPages { page ->
                api.getProjectUnitPhotos(serverProjectId, page, updatedSince = unitSince)
            }
                .map { it.toPhotoDto(projectId) }
        }.onSuccess { photos ->
            if (persistPhotos(photos)) {
                totalPhotos += photos.size
                Log.d("API", "📸 [syncProjectLevelPhotos] Saved ${photos.size} unit photos")
            }
            photos.latestTimestamp { it.updatedAt }
                ?.let { syncCheckpointStore.updateCheckpoint(unitKey, it) }
        }.onFailure { error ->
            failedCount++
            Log.e("API", "❌ [syncProjectLevelPhotos] Failed to fetch unit photos", error)
        }

        if (totalPhotos > 0) {
            photoCacheScheduler.schedulePrefetch()
        }

        val duration = System.currentTimeMillis() - startTime
        if (failedCount == 3) {
            // All three photo types failed
            Log.e("API", "❌ [syncProjectLevelPhotos] All photo types failed")
            return@withContext SyncResult.failure(
                SyncSegment.PROJECT_LEVEL_PHOTOS,
                Exception("All project-level photo fetches failed"),
                duration
            )
        }
        Log.d("API", "✅ [syncProjectLevelPhotos] Synced $totalPhotos photos in ${duration}ms (${failedCount}/3 types failed)")
        SyncResult.success(SyncSegment.PROJECT_LEVEL_PHOTOS, totalPhotos, duration)
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

    private suspend fun fetchRoomPhotoPages(
        roomId: Long,
        projectId: Long,
        updatedSince: String?
    ): List<PhotoDto> {
        val collected = mutableListOf<PhotoDto>()
        var page = 1

        while (true) {
            val json = api.getRoomPhotos(
                roomId = roomId,
                page = page,
                limit = ROOM_PHOTO_PAGE_LIMIT,
                include = ROOM_PHOTO_INCLUDE,
                updatedSince = updatedSince
            )

            val parsed = parseRoomPhotoResponse(json, projectId, roomId)
            collected += parsed.photos

            if (!parsed.hasMore || parsed.nextPage == null || parsed.photos.isEmpty()) {
                break
            }
            if (parsed.nextPage == page) {
                break
            }
            page = parsed.nextPage
        }

        return collected
    }

    private fun parseRoomPhotoResponse(
        json: JsonObject,
        projectId: Long,
        roomId: Long
    ): RoomPhotoPageResult {
        val photos = mutableListOf<PhotoDto>()

        fun collect(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element is JsonArray -> {
                    val list: List<RoomPhotoDto> = gson.fromJson(element, roomPhotoListType)
                    photos += list.mapNotNull { it.toPhotoDto(defaultProjectId = projectId, defaultRoomId = roomId) }
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    collect(obj.get("data"))
                    collect(obj.get("photos"))
                }
            }
        }

        collect(json.get("data"))
        collect(json.get("photos"))

        val dataObject = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val metaElement = when {
            json.get("meta")?.isJsonObject == true -> json.getAsJsonObject("meta")
            dataObject?.get("meta")?.isJsonObject == true -> dataObject.getAsJsonObject("meta")
            else -> null
        }

        val meta = metaElement?.let { gson.fromJson(it, PaginationMeta::class.java) }
        val currentFromMeta = meta?.currentPage
        val lastFromMeta = meta?.lastPage
        val currentFromData = dataObject?.get("current_page")?.takeIf { it.isJsonPrimitive }?.asInt
        val lastFromData = dataObject?.get("last_page")?.takeIf { it.isJsonPrimitive }?.asInt

        val current = currentFromMeta ?: currentFromData ?: -1
        val last = lastFromMeta ?: lastFromData ?: current
        val hasMore = current > 0 && last > current
        val nextPage = if (hasMore) current + 1 else null

        return RoomPhotoPageResult(
            photos = photos,
            hasMore = hasMore,
            nextPage = nextPage
        )
    }

    private suspend fun persistPhotos(
        photos: List<PhotoDto>,
        defaultRoomId: Long? = null,
        defaultProjectId: Long? = null
    ): Boolean {
        if (photos.isEmpty()) {
            return false
        }

        val entities = mutableListOf<OfflinePhotoEntity>()
        var mismatchCount = 0
        val roomsNeedingSnapshotRefresh = mutableMapOf<Long, Int>()
        for (photo in photos) {
            val existing = localDataService.getPhotoByServerId(photo.id)
            val preservedRoom = existing?.roomId

            // Always use provided defaults to maintain sync context integrity
            val resolvedRoomId = defaultRoomId ?: photo.roomId ?: preservedRoom
            val resolvedProjectId = defaultProjectId ?: photo.projectId

            // Log mismatches for debugging data integrity issues
            if (defaultProjectId != null && photo.projectId != defaultProjectId) {
                mismatchCount++
                Log.w("API", "⚠️ [persistPhotos] Photo ${photo.id} has projectId=${photo.projectId} but syncing for project $defaultProjectId - using $defaultProjectId")
            }
            if (defaultRoomId != null && photo.roomId != null && photo.roomId != defaultRoomId) {
                Log.w("API", "⚠️ [persistPhotos] Photo ${photo.id} has roomId=${photo.roomId} but syncing for room $defaultRoomId - using $defaultRoomId")
            }

            val resolvedFileName = photo.fileName ?: "photo_${photo.id}.jpg"
            val removedCount = pruneLocalPlaceholderForIncomingPhoto(
                projectId = resolvedProjectId,
                roomId = resolvedRoomId,
                fileName = resolvedFileName
            )
            if (removedCount > 0 && resolvedRoomId != null) {
                roomsNeedingSnapshotRefresh.merge(resolvedRoomId, removedCount, Int::plus)
            }

            entities += photo.toEntity(
                defaultRoomId = resolvedRoomId,
                defaultProjectId = resolvedProjectId
            )
        }

        if (mismatchCount > 0) {
            Log.w("API", "⚠️ [persistPhotos] Fixed $mismatchCount photos with mismatched projectId")
        }

        localDataService.savePhotos(entities)

        // Extract and save albums from photos
        val albums = buildList<OfflineAlbumEntity> {
            photos.forEach { photo ->
                photo.albums?.forEach { album ->
                    // Always use defaultProjectId when provided (sync context) over DTO value
                    val projectId = defaultProjectId ?: photo.projectId ?: 0L
                    // Prioritize defaultRoomId (canonical server ID) when available
                    val roomId = defaultRoomId ?: photo.roomId
                    add(album.toEntity(defaultProjectId = projectId, defaultRoomId = roomId))
                }
            }
        }.distinctBy { it.albumId }

        if (albums.isNotEmpty()) {
            localDataService.saveAlbums(albums)
            Log.d("API", "📂 [persistPhotos] Saved ${albums.size} albums from photos (defaultRoomId=$defaultRoomId)")
            albums.forEach { album ->
                Log.d("API", "  Album '${album.name}' (id=${album.albumId}): roomId=${album.roomId}, projectId=${album.projectId}")
            }
        }

        val albumPhotoRelationships = buildList<OfflineAlbumPhotoEntity> {
            photos.forEach { photo ->
                photo.albums?.forEach { album ->
                    add(
                        OfflineAlbumPhotoEntity(
                            albumId = album.id,
                            photoServerId = photo.id
                        )
                    )
                }
            }
        }
        if (albumPhotoRelationships.isNotEmpty()) {
            localDataService.saveAlbumPhotos(albumPhotoRelationships)
            Log.d("API", "📸 [persistPhotos] Saved ${albumPhotoRelationships.size} album-photo relationships")
        }

        roomsNeedingSnapshotRefresh.forEach { (roomId, _) ->
            runCatching { localDataService.refreshRoomPhotoSnapshot(roomId) }
                .onFailure {
                    Log.w("API", "⚠️ [persistPhotos] Failed to refresh snapshot after placeholder cleanup for room $roomId", it)
                    remoteLogger?.log(
                        level = LogLevel.WARN,
                        tag = "OfflineSyncRepository",
                        message = "Failed to refresh snapshot after placeholder cleanup",
                        metadata = mapOf(
                            "room_id" to roomId.toString(),
                            "error" to (it.message ?: "unknown")
                        )
                    )
                }
        }

        if (roomsNeedingSnapshotRefresh.isNotEmpty()) {
            val totalRemoved = roomsNeedingSnapshotRefresh.values.sum()
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = "OfflineSyncRepository",
                message = "Removed local pending placeholders before applying server photos",
                metadata = mapOf(
                    "total_removed" to totalRemoved.toString(),
                    "rooms" to roomsNeedingSnapshotRefresh.entries.joinToString { "${it.key}:${it.value}" }
                )
            )
        }

        return true
    }

    private suspend fun pruneLocalPlaceholderForIncomingPhoto(
        projectId: Long?,
        roomId: Long?,
        fileName: String
    ): Int {
        if (projectId == null || roomId == null) return 0

        val removed = localDataService.deleteLocalPendingRoomPhoto(projectId, roomId, fileName)
        if (removed <= 0) return 0

        Log.d(
            "API",
            "🧹 [persistPhotos] Removed $removed local pending photo(s) named '$fileName' " +
                "for roomId=$roomId projectId=$projectId before applying server update"
        )

        return removed
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

    private suspend fun restoreDeletedParents(targets: Map<String, List<Long>>) {
        targets.forEach { (type, ids) ->
            val filteredIds = ids.filter { it > 0 }
            if (filteredIds.isEmpty()) return@forEach

            runCatching {
                api.restoreDeletedRecords(RestoreRecordsRequest(type = type, ids = filteredIds))
            }
                .onSuccess { response ->
                    Log.d(
                        "API",
                        "♻️ [syncRestore] type=$type restored=${response.restored.size}, already_restored=${response.alreadyRestored.size}, not_found=${response.notFound.size}, unauthorized=${response.unauthorized.size}"
                    )
                }
                .onFailure { error ->
                    Log.w("API", "⚠️ [syncRestore] Failed to restore $type ids=${filteredIds.joinToString()}", error)
                }
        }
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
            Log.w("API", "⚠️ [syncPendingEquipment] Failed to delete equipment ${equipment.uuid}", it)
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
            Log.w("API", "⚠️ [syncPendingEquipment] Failed to push equipment ${equipment.uuid}", error)
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
            Log.w("API", "⚠️ [syncPendingMoistureLogs] Failed to delete moisture log ${log.uuid}", it)
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
                "API",
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
                "API",
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
            Log.w("API", "⚠️ [syncPendingMoistureLogs] Failed to push moisture log ${log.uuid}", error)
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
                "API",
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
                "API",
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

    private data class RoomPhotoPageResult(
        val photos: List<PhotoDto>,
        val hasMore: Boolean,
        val nextPage: Int?
    )

    private suspend fun persistProperty(
        projectId: Long,
        property: PropertyDto,
        propertyTypeValue: String?,
        existing: OfflinePropertyEntity? = null
    ): OfflinePropertyEntity {
        // Fetch project address for fallback if property doesn't have address data
        val projectAddress = if (property.address.isNullOrBlank() || property.city.isNullOrBlank()) {
            runCatching { api.getProjectDetail(projectId).data.address }.getOrNull()
        } else null
        val entity = property.toEntity(existing = existing, projectAddress = projectAddress)
        localDataService.saveProperty(entity)
        val resolvedId = entity.propertyId
        localDataService.attachPropertyToProject(
            projectId = projectId,
            propertyId = resolvedId,
            propertyType = propertyTypeValue
        )
        runCatching { primeRoomTypeCaches(projectId) }
            .onFailure { Log.w("API", "⚠️ [syncProjectEssentials] Unable to prefetch room types for project $projectId", it) }
        return entity
    }

    private suspend fun primeRoomTypeCaches(projectId: Long) {
        RequestType.entries.forEach { requestType ->
            runCatching {
                val types = roomTypeRepository
                    .getRoomTypes(projectId, requestType, forceRefresh = true)
                    .getOrThrow()
                Log.d("API", "✅ [roomTypes] Prefetched ${types.size} ${requestType.name.lowercase()} room types for project $projectId")
            }.onFailure { error ->
                Log.w("API", "⚠️ [roomTypes] Prefetch failed for project $projectId (${requestType.name})", error)
            }
        }
    }

    private suspend fun fetchProjectProperty(
        projectId: Long,
        projectDetail: ProjectDetailDto? = null
    ): PropertyDto? {
        // Note: iOS uses include=propertyType,asbestosStatus,propertyDamageTypes,damageCause
        // but QA backend doesn't support it - returns empty array. Use project detail for propertyType instead.
        val result = runCatching { api.getProjectProperties(projectId) }
        result.onFailure { error ->
            Log.e("API", "❌ [fetchProjectProperty] API call failed for project $projectId", error)
        }
        val response = result.getOrNull()
        Log.d("API", "🔍 [fetchProjectProperty] Response for project $projectId: ${response?.data?.size ?: 0} properties returned")
        val property = response?.data?.firstOrNull()
        if (property != null) {
            Log.d("API", "🔍 [fetchProjectProperty] PropertyDto for project $projectId: id=${property.id}, propertyTypeId=${property.propertyTypeId}, propertyType=${property.propertyType}, address=${property.address}, city=${property.city}, state=${property.state}, zip=${property.postalCode}")
            return property
        }

        Log.d("API", "⚠️ [fetchProjectProperty] No property in response for project $projectId")
        val detail = projectDetail ?: runCatching { api.getProjectDetail(projectId).data }
            .onFailure { Log.w("API", "⚠️ [fetchProjectProperty] Unable to load project detail for fallback (project $projectId)", it) }
            .getOrNull()
        val detailPropertyId = detail?.propertyId ?: detail?.properties?.firstOrNull()?.id

        if (detailPropertyId != null) {
            val fetchedById = runCatching { api.getProperty(detailPropertyId).data }
                .onSuccess { fetched ->
                    Log.d("API", "🏠 [fetchProjectProperty] Fallback getProperty succeeded for project $projectId (id=$detailPropertyId, address=${fetched.address}, city=${fetched.city}, state=${fetched.state}, zip=${fetched.postalCode})")
                }
                .onFailure {
                    Log.e("API", "❌ [fetchProjectProperty] Fallback getProperty failed for project $projectId (id=$detailPropertyId)", it)
                }
                .getOrNull()
            if (fetchedById != null) {
                return fetchedById
            }
        }

        val embedded = detail?.properties?.firstOrNull()
        if (embedded != null) {
            Log.d("API", "🏠 [fetchProjectProperty] Using embedded property from project detail for project $projectId: id=${embedded.id}, address=${embedded.address}, city=${embedded.city}, state=${embedded.state}, zip=${embedded.postalCode}")
            return embedded
        }

        Log.d("API", "⚠️ [fetchProjectProperty] No property found for project $projectId after fallback attempts")
        return null
    }

    private suspend fun fetchRoomsForLocation(locationId: Long): List<RoomDto> {
        if (locationId <= 0) {
            Log.w("API", "⚠️ [syncProjectGraph] Skipping invalid locationId=$locationId")
            return emptyList()
        }
        val collected = mutableListOf<RoomDto>()
        var page = 1
        val updatedSince = localDataService.getLatestRoomUpdateForLocation(locationId)
            ?.let { DateUtils.formatApiDate(it) }

        if (page == 1) {
            if (updatedSince != null) {
                Log.d("API", "🔄 [FAST] Requesting rooms for location $locationId since $updatedSince (incremental)")
            } else {
                Log.d("API", "🔄 [FAST] Requesting rooms for location $locationId (full sync - first run)")
            }
        }

        while (true) {
            val response = runCatching {
                api.getRoomsForLocation(
                    locationId,
                    page = page,
                    updatedSince = updatedSince
                )
            }
                .onSuccess { result ->
                    val size = result.data.size
                    if (size == 0 && page == 1) {
                        Log.d("API", "INFO [FAST] No rooms returned for location $locationId")
                    } else if (size > 0) {
                        Log.d("API", "✅ [FAST] Fetched $size rooms for location $locationId (page $page)")
                    }
                }
                .onFailure { error ->
                    if (error is retrofit2.HttpException && error.code() == 404) {
                        Log.d("API", "INFO [syncProjectGraph] Location $locationId has no rooms (404)")
                        runCatching { localDataService.markLocationsDeleted(listOf(locationId)) }
                            .onFailure { Log.w("API", "⚠️ [syncProjectGraph] Failed to mark missing location $locationId as deleted", it) }
                    } else {
                        Log.e("API", "❌ [syncProjectGraph] Failed to fetch rooms for location $locationId", error)
                    }
                }
                .getOrNull()

            // If request failed, return what we've collected so far
            if (response == null) {
                if (collected.isNotEmpty()) {
                    Log.d("API", "⚠️ [syncProjectGraph] Returning ${collected.size} rooms collected before error for location $locationId")
                }
                return collected
            }

            val data = response.data

            // Debug: Log first room's fields to inspect payload
            if (data.isNotEmpty() && page == 1) {
                val firstRoom = data.first()
                Log.d("API", "🔍 [DEBUG] Room payload - id: ${firstRoom.id}, name: ${firstRoom.name}, title: ${firstRoom.title}, roomType.name: ${firstRoom.roomType?.name}, level.name: ${firstRoom.level?.name}")
            }

            collected += data

            val meta = response.meta
            val current = meta?.currentPage ?: page
            val last = meta?.lastPage ?: current
            val hasMore = current < last && data.isNotEmpty()
            if (!hasMore) {
                break
            }
            page = current + 1
        }
        return collected
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
        val entity = dto.toEntity(existing = existing).withAddressFallback(
            projectAddress = addressDto,
            addressRequest = payload.addressRequest
        ).copy(
            projectId = existing.projectId,
            uuid = existing.uuid,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = now()
        )

        localDataService.saveProjects(listOf(entity))
        val pendingAlias = existing.alias?.takeIf { it.isNotBlank() }
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
        if (!response.isSuccessful && response.code() !in listOf(404, 410)) {
            throw HttpException(response)
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
            propertyTypeId = payload.propertyTypeId,
            idempotencyKey = payload.idempotencyKey
        )

        val created = api.createProjectProperty(projectServerId, request).data
        val refreshed = runCatching { api.getProperty(created.id).data }.getOrNull() ?: created
        val existing = localDataService.getProperty(payload.localPropertyId)
        persistProperty(payload.projectId, refreshed, payload.propertyTypeValue, existing = existing)
        return OperationOutcome.SUCCESS
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
        val updated = api.updateProperty(serverId, request).data
        val refreshed = runCatching { api.getProperty(updated.id).data }.getOrNull() ?: updated
        persistProperty(payload.projectId, refreshed, payload.propertyTypeValue, existing = property)
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

        var locations = localDataService.getLocations(payload.projectId)
        var levelServerId = payload.levelServerId
            ?: locations.firstOrNull { it.locationId == payload.levelLocalId }?.serverId
            ?: locations.firstOrNull { it.parentLocationId == null }?.serverId

        var locationServerId = payload.locationServerId
            ?: locations.firstOrNull { it.locationId == payload.locationLocalId }?.serverId
            ?: locations.firstOrNull { it.parentLocationId == levelServerId }?.serverId

        if (levelServerId == null || locationServerId == null) {
            // Try to refresh essentials to populate locations/levels
            syncProjectEssentials(payload.projectId)
            locations = localDataService.getLocations(payload.projectId)
            if (levelServerId == null) {
                levelServerId = locations.firstOrNull { it.parentLocationId == null }?.serverId
            }
            if (locationServerId == null) {
                locationServerId = locations.firstOrNull { it.parentLocationId == levelServerId }?.serverId
                    ?: locations.firstOrNull()?.serverId
            }
        }

        if (levelServerId == null || locationServerId == null) {
            Log.w("API", "⚠️ [handlePendingRoomCreation] Missing location/level for room ${payload.roomUuid}; will retry")
            return OperationOutcome.SKIP
        }

        restoreDeletedParents(
            mapOf(
                "projects" to listOf(projectServerId),
                "locations" to listOf(locationServerId),
                "levels" to listOf(levelServerId)
            )
        )

        val idempotencyKey = payload.idempotencyKey ?: payload.roomUuid
        val request = CreateRoomRequest(
            name = payload.roomName,
            roomTypeId = payload.roomTypeId,
            levelId = levelServerId,
            isSource = payload.isSource,
            idempotencyKey = idempotencyKey
        )

        val dto = api.createRoom(locationServerId, request)
        val existing = localDataService.getRoomByUuid(payload.roomUuid)
            ?: localDataService.getRoomByServerId(dto.id)
        val entity = dto.toEntity(
            existing = existing,
            projectId = payload.projectId,
            locationId = locationServerId
        ).copy(
            roomId = existing?.roomId ?: payload.localRoomId,
            uuid = existing?.uuid ?: payload.roomUuid,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = now()
        )
        localDataService.saveRooms(listOf(entity))
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

        val lockUpdatedAt = extractLockUpdatedAt(operation.payload) ?: note.updatedAt.toApiTimestamp()
        val request = CreateNoteRequest(
            projectId = projectServerId,
            roomId = roomServerId,
            body = note.content,
            photoId = note.photoId,
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
    }

    private suspend fun enqueueProjectCreation(
        project: OfflineProjectEntity,
        companyId: Long,
        statusId: Int,
        addressRequest: CreateAddressRequest,
        idempotencyKey: String? = null
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

    private suspend fun enqueuePropertyCreation(
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

    private suspend fun enqueueRoomCreation(
        room: OfflineRoomEntity,
        roomTypeId: Long,
        isSource: Boolean,
        levelServerId: Long?,
        locationServerId: Long?,
        levelLocalId: Long?,
        locationLocalId: Long?,
        idempotencyKey: String?
    ) {
        val payload = PendingRoomCreationPayload(
            localRoomId = room.roomId,
            roomUuid = room.uuid,
            projectId = room.projectId,
            roomName = room.title,
            roomTypeId = roomTypeId,
            isSource = isSource,
            levelServerId = levelServerId,
            locationServerId = locationServerId,
            levelLocalId = levelLocalId,
            locationLocalId = locationLocalId,
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

    private fun resolveEntityId(entityId: Long, uuid: String): Long =
        if (entityId != 0L) entityId else uuid.hashCode().toLong()

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

    private suspend fun enqueueProjectUpdate(
        project: OfflineProjectEntity,
        lockUpdatedAt: String? = null
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
                Log.w("API", "⚠️ [enqueueProjectUpdate] No pending create for project ${project.projectId}; skipping update")
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

    private suspend fun enqueueProjectDeletion(
        project: OfflineProjectEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueuePropertyUpdate(
        property: OfflinePropertyEntity,
        projectId: Long,
        request: PropertyMutationRequest,
        propertyTypeValue: String?,
        lockUpdatedAt: String? = null
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
                Log.w("API", "⚠️ [enqueuePropertyUpdate] No pending create for property ${property.propertyId}; skipping update")
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

    private suspend fun enqueueRoomDeletion(
        room: OfflineRoomEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueueNoteUpsert(
        note: OfflineNoteEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueueNoteDeletion(
        note: OfflineNoteEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueueEquipmentUpsert(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueueEquipmentDeletion(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueueMoistureLogUpsert(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueueMoistureLogDeletion(
        log: OfflineMoistureLogEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun enqueuePhotoDeletion(
        photo: OfflinePhotoEntity,
        lockUpdatedAt: String? = null
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

    private suspend fun createPendingProperty(
        project: OfflineProjectEntity,
        propertyTypeValue: String?,
        propertyTypeId: Int,
        idempotencyKey: String
    ): OfflinePropertyEntity {
        val timestamp = now()
        val localId = -System.currentTimeMillis()
        val resolvedAddress = listOfNotNull(
            project.addressLine1?.takeIf { it.isNotBlank() },
            project.title.takeIf { it.isNotBlank() },
            "Pending property"
        ).first()
        val pending = OfflinePropertyEntity(
            propertyId = localId,
            serverId = null,
            uuid = UUID.randomUUID().toString(),
            address = resolvedAddress,
            city = null,
            state = null,
            zipCode = null,
            latitude = null,
            longitude = null,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null
        )
        localDataService.saveProperty(pending)
        localDataService.attachPropertyToProject(
            projectId = project.projectId,
            propertyId = pending.propertyId,
            propertyType = propertyTypeValue
        )
        enqueuePropertyCreation(
            property = pending,
            projectId = project.projectId,
            propertyTypeId = propertyTypeId,
            propertyTypeValue = propertyTypeValue,
            idempotencyKey = idempotencyKey
        )
        return pending
    }

    private suspend fun createPendingRoom(
        projectId: Long,
        roomName: String,
        roomTypeId: Long,
        isSource: Boolean,
        idempotencyKey: String,
        forcedUuid: String? = null
    ): OfflineRoomEntity? {
        val project = localDataService.getProject(projectId)
            ?: throw IllegalStateException("Project not found locally")
        val locations = localDataService.getLocations(projectId)
        if (locations.isEmpty()) {
            Log.w("API", "📴 [createPendingRoom] No locations found for project $projectId, cannot queue room")
            return null
        }
        val level = locations.firstOrNull { it.parentLocationId == null } ?: locations.first()
        val location = locations.firstOrNull { it.parentLocationId == level.serverId } ?: level
        val timestamp = now()
        val localId = -System.currentTimeMillis()
        val pending = OfflineRoomEntity(
            roomId = localId,
            serverId = null,
            uuid = forcedUuid ?: UUID.randomUUID().toString(),
            projectId = project.projectId,
            locationId = location.locationId,
            title = roomName,
            roomTypeId = roomTypeId,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null
        )
        localDataService.saveRooms(listOf(pending))
        enqueueRoomCreation(
            room = pending,
            roomTypeId = roomTypeId,
            isSource = isSource,
            levelServerId = level.serverId,
            locationServerId = location.serverId,
            levelLocalId = level.locationId,
            locationLocalId = location.locationId,
            idempotencyKey = idempotencyKey
        )
        return pending
    }

    private data class LocationDefaults(
        val locationTypeId: Long,
        val type: String,
        val floorNumber: Int,
        val isCommon: Boolean,
        val isAccessible: Boolean,
        val isCommercial: Boolean
    )

    private fun resolveLocationDefaults(propertyTypeValue: String?): LocationDefaults {
        val normalized = propertyTypeValue
            ?.lowercase()
            ?.replace("-", "_")
            ?.replace("\\s+".toRegex(), "_")
            ?.trim('_')

        return when (normalized) {
            "exterior" -> LocationDefaults(
                locationTypeId = 3,
                type = "exterior",
                floorNumber = 0,
                isCommon = true,
                isAccessible = true,
                isCommercial = false
            )
            "commercial", "multi_unit", "multi-unit" -> LocationDefaults(
                locationTypeId = 2,
                type = "floor",
                floorNumber = 1,
                isCommon = true,
                isAccessible = true,
                isCommercial = normalized == "commercial"
            )
            else -> LocationDefaults(
                locationTypeId = 1,
                type = "unit",
                floorNumber = 1,
                isCommon = true,
                isAccessible = true,
                isCommercial = false
            )
        }
    }

    private suspend fun createPendingLocation(
        projectId: Long,
        locationName: String,
        config: LocationDefaults,
        idempotencyKey: String
    ): OfflineLocationEntity {
        val project = localDataService.getProject(projectId)
            ?: throw IllegalStateException("Project not found locally")
        val propertyLocalId = project.propertyId
            ?: throw IllegalStateException("Project has no property")

        val timestamp = now()
        val localId = -System.currentTimeMillis()
        val pending = OfflineLocationEntity(
            locationId = localId,
            serverId = null,
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            title = locationName,
            type = config.type,
            parentLocationId = null,
            isAccessible = config.isAccessible,
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null
        )
        localDataService.saveLocations(listOf(pending))
        enqueueLocationCreation(
            location = pending,
            propertyLocalId = propertyLocalId,
            config = config,
            idempotencyKey = idempotencyKey
        )
        return pending
    }

    private suspend fun enqueueLocationCreation(
        location: OfflineLocationEntity,
        propertyLocalId: Long,
        config: LocationDefaults,
        idempotencyKey: String?
    ) {
        val payload = PendingLocationCreationPayload(
            localLocationId = location.locationId,
            locationUuid = location.uuid,
            projectId = location.projectId,
            propertyLocalId = propertyLocalId,
            locationName = location.title,
            locationTypeId = config.locationTypeId,
            type = config.type,
            floorNumber = config.floorNumber,
            isCommon = config.isCommon,
            isAccessible = config.isAccessible,
            isCommercial = config.isCommercial,
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

    private suspend fun handlePendingLocationCreation(
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val payload = runCatching {
            gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingLocationCreationPayload::class.java)
        }.getOrNull() ?: return OperationOutcome.DROP

        val property = localDataService.getProperty(payload.propertyLocalId)
            ?: return OperationOutcome.SKIP
        val propertyServerId = property.serverId
            ?: return OperationOutcome.SKIP

        val request = CreateLocationRequest(
            name = payload.locationName,
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
            uuid = existing?.uuid ?: dto.uuid ?: UUID.randomUUID().toString(),
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = now()
        )
        localDataService.saveLocations(listOf(entity))
        return OperationOutcome.SUCCESS
    }
}

// region Mappers
private fun now(): Date = Date()

private fun ProjectDto.toEntity(existing: OfflineProjectEntity? = null, fallbackCompanyId: Long? = null): OfflineProjectEntity {
    if (id == 0L) {
        Log.e("OfflineSyncRepository", "🚨 BUG FOUND! ProjectDto.toEntity() called with id=0", Exception("Stack trace"))
        Log.e("OfflineSyncRepository", "   ProjectDto: id=$id, uuid=$uuid, uid=$uid, title=$title, propertyId=$propertyId")
    }
    val timestamp = now()
    val addressLine1 = address?.address?.takeIf { it.isNotBlank() } ?: existing?.addressLine1
    val addressLine2 = address?.address2?.takeIf { it.isNotBlank() } ?: existing?.addressLine2
    val resolvedTitle = listOfNotNull(
        addressLine1,
        title?.takeIf { it.isNotBlank() },
        existing?.title?.takeIf { it.isNotBlank() },
        alias?.takeIf { it.isNotBlank() },
        projectNumber?.takeIf { it.isNotBlank() },
        uid?.takeIf { it.isNotBlank() }
    ).firstOrNull() ?: "Project $id"
    val resolvedUuid = uuid ?: uid ?: existing?.uuid ?: "project-$id"
    val resolvedStatus = status?.takeIf { it.isNotBlank() } ?: "unknown"
    val resolvedPropertyId = propertyId
        ?: properties?.firstOrNull()?.id
        ?: address?.id
        ?: existing?.propertyId
    val resolvedPropertyType = propertyType ?: existing?.propertyType
    val normalizedAlias = alias?.takeIf { it.isNotBlank() }
    val normalizedUid = uid?.takeIf { it.isNotBlank() }
    return OfflineProjectEntity(
        projectId = id,
        serverId = id,
        uuid = resolvedUuid,
        title = resolvedTitle,
        projectNumber = projectNumber,
        uid = normalizedUid,
        alias = normalizedAlias,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        status = resolvedStatus,
        propertyType = resolvedPropertyType,
        companyId = companyId ?: existing?.companyId ?: fallbackCompanyId,
        propertyId = resolvedPropertyId,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp
    )
}

private fun com.example.rocketplan_android.data.model.offline.ProjectDetailDto.toEntity(
    existing: OfflineProjectEntity? = null,
    fallbackCompanyId: Long? = null
): OfflineProjectEntity {
    if (id == 0L) {
        Log.e("OfflineSyncRepository", "🚨 BUG FOUND! ProjectDetailDto.toEntity() called with id=0", Exception("Stack trace"))
        Log.e("OfflineSyncRepository", "   ProjectDetailDto: id=$id, uuid=$uuid, uid=$uid, title=$title, propertyId=$propertyId")
    }
    val timestamp = now()
    val addressLine1 = address?.address?.takeIf { it.isNotBlank() }
    val addressLine2 = address?.address2?.takeIf { it.isNotBlank() }
    val resolvedTitle = listOfNotNull(
        addressLine1,
        title?.takeIf { it.isNotBlank() },
        alias?.takeIf { it.isNotBlank() },
        projectNumber?.takeIf { it.isNotBlank() },
        uid?.takeIf { it.isNotBlank() }
    ).firstOrNull() ?: "Project $id"
    val resolvedUuid = uuid ?: uid ?: existing?.uuid ?: "project-$id"
    val resolvedStatus = status?.takeIf { it.isNotBlank() } ?: "unknown"
    val resolvedPropertyId = propertyId
        ?: properties?.firstOrNull()?.id
        ?: address?.id
        ?: existing?.propertyId
    val resolvedPropertyType = propertyType
        ?: properties?.firstOrNull()?.propertyType
        ?: existing?.propertyType
    val normalizedAlias = alias?.takeIf { it.isNotBlank() }
    val normalizedUid = uid?.takeIf { it.isNotBlank() }
    return OfflineProjectEntity(
        projectId = id,
        serverId = id,
        uuid = resolvedUuid,
        title = resolvedTitle,
        projectNumber = projectNumber,
        uid = normalizedUid,
        alias = normalizedAlias,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        status = resolvedStatus,
        propertyType = resolvedPropertyType,
        companyId = companyId ?: existing?.companyId ?: fallbackCompanyId,
        propertyId = resolvedPropertyId,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp
    )
}

private fun UserDto.toEntity(): OfflineUserEntity {
    val timestamp = now()
    return OfflineUserEntity(
        userId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        email = email,
        firstName = firstName,
        lastName = lastName,
        role = role,
        companyId = companyId,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp
    )
}

private fun PropertyDto.toEntity(
    existing: OfflinePropertyEntity? = null,
    projectAddress: com.example.rocketplan_android.data.model.offline.ProjectAddressDto? = null
): OfflinePropertyEntity {
    val timestamp = now()
    // Use property address fields if available, otherwise fall back to project address
    val resolvedAddress = address?.takeIf { it.isNotBlank() } ?: projectAddress?.address ?: ""
    val resolvedCity = city?.takeIf { it.isNotBlank() } ?: projectAddress?.city
    val resolvedState = state?.takeIf { it.isNotBlank() } ?: projectAddress?.state
    val resolvedZip = postalCode?.takeIf { it.isNotBlank() } ?: projectAddress?.zip
    val resolvedLat = latitude ?: projectAddress?.latitude?.toDoubleOrNull()
    val resolvedLng = longitude ?: projectAddress?.longitude?.toDoubleOrNull()
    val resolvedUuid = existing?.uuid ?: uuid ?: UUID.randomUUID().toString()
    val resolvedId = existing?.propertyId ?: id

    return OfflinePropertyEntity(
        propertyId = resolvedId,
        serverId = id,
        uuid = resolvedUuid,
        address = resolvedAddress,
        city = resolvedCity,
        state = resolvedState,
        zipCode = resolvedZip,
        latitude = resolvedLat,
        longitude = resolvedLng,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = (existing?.syncVersion ?: 0) + 1,
        createdAt = existing?.createdAt ?: DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp
    )
}

private fun LocationDto.toEntity(defaultProjectId: Long? = null): OfflineLocationEntity {
    val timestamp = now()
    val resolvedTitle = listOfNotNull(
        title?.takeIf { it.isNotBlank() },
        name?.takeIf { it.isNotBlank() }
    ).firstOrNull() ?: "Location $id"
    val resolvedType = listOfNotNull(
        type?.takeIf { it.isNotBlank() },
        locationType?.takeIf { it.isNotBlank() }
    ).firstOrNull() ?: "location"
    val resolvedProjectId = projectId ?: defaultProjectId
        ?: throw IllegalStateException("Location $id has no projectId")
    return OfflineLocationEntity(
        locationId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = resolvedProjectId,
        title = resolvedTitle,
        type = resolvedType,
        parentLocationId = parentLocationId,
        isAccessible = isAccessible ?: true,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp
    )
}

private fun RoomDto.toEntity(
    existing: OfflineRoomEntity?,
    projectId: Long,
    locationId: Long? = this.locationId
): OfflineRoomEntity {
    val timestamp = now()
    val resolvedUuid = uuid ?: existing?.uuid ?: UUID.randomUUID().toString()
    val createdAtValue = DateUtils.parseApiDate(createdAt) ?: existing?.createdAt ?: timestamp
    val updatedAtValue = DateUtils.parseApiDate(updatedAt) ?: timestamp

    val base = existing ?: OfflineRoomEntity(
        roomId = 0,
        serverId = id,
        uuid = resolvedUuid,
        projectId = projectId,
        locationId = locationId,
        title = "",
        roomType = null,
        roomTypeId = roomType?.id,
        level = null,
        squareFootage = null,
        isAccessible = isAccessible ?: true,
        photoCount = photosCount,
        thumbnailUrl = thumbnailUrl ?: thumbnail?.thumbnailUrl ?: thumbnail?.remoteUrl,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        createdAt = createdAtValue,
        updatedAt = updatedAtValue,
        lastSyncedAt = timestamp
    )

    val resolvedTitle = when {
        !roomType?.name.isNullOrBlank() && (typeOccurrence ?: 1) > 1 ->
            "${roomType.name} $typeOccurrence"
        !title.isNullOrBlank() -> title
        !name.isNullOrBlank() -> name
        !roomType?.name.isNullOrBlank() -> roomType.name
        else -> base.title.ifBlank { "Room $id" }
    }

    return base.copy(
        serverId = id,
        projectId = projectId,
        locationId = locationId,
        title = resolvedTitle,
        roomType = roomType?.name,
        roomTypeId = roomType?.id ?: existing?.roomTypeId,
        level = level?.name ?: level?.title,
        squareFootage = squareFootage,
        isAccessible = isAccessible ?: true,
        photoCount = photosCount ?: existing?.photoCount,
        thumbnailUrl = thumbnailUrl
            ?: thumbnail?.thumbnailUrl
            ?: thumbnail?.remoteUrl
            ?: existing?.thumbnailUrl,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = (existing?.syncVersion ?: 0) + 1,
        isDirty = false,
        isDeleted = false,
        createdAt = createdAtValue,
        updatedAt = updatedAtValue,
        lastSyncedAt = timestamp
    )
}

private fun RoomPhotoDto.toPhotoDto(defaultProjectId: Long, defaultRoomId: Long): PhotoDto {
    val nested = photo
    // IMPORTANT: Always use defaults (sync context) over nested values to prevent
    // photos from being assigned to wrong project/room when API returns stale data
    val resolvedProjectId = defaultProjectId
    val resolvedRoomId = defaultRoomId
    val resolvedRemoteUrl = nested?.remoteUrl
        ?: sizes?.raw
        ?: sizes?.gallery
        ?: sizes?.large
        ?: sizes?.medium
        ?: sizes?.small
    val resolvedThumbnail = nested?.thumbnailUrl ?: sizes?.medium ?: sizes?.small
    val combinedAlbums = when {
        nested?.albums != null && albums != null -> (nested.albums + albums).distinctBy { it.id }
        nested?.albums != null -> nested.albums
        else -> albums
    }

    return PhotoDto(
        id = nested?.id ?: id,
        uuid = nested?.uuid ?: uuid,
        projectId = resolvedProjectId,
        roomId = resolvedRoomId,
        logId = nested?.logId,
        moistureLogId = nested?.moistureLogId,
        fileName = nested?.fileName ?: fileName,
        localPath = nested?.localPath,
        remoteUrl = resolvedRemoteUrl,
        thumbnailUrl = resolvedThumbnail,
        assemblyId = nested?.assemblyId,
        tusUploadId = nested?.tusUploadId,
        fileSize = nested?.fileSize,
        width = nested?.width,
        height = nested?.height,
        mimeType = nested?.mimeType ?: contentType,
        capturedAt = nested?.capturedAt ?: createdAt,
        createdAt = nested?.createdAt ?: createdAt,
        updatedAt = nested?.updatedAt ?: updatedAt,
        albums = combinedAlbums
    )
}

private fun PhotoDto.toEntity(
    defaultRoomId: Long? = this.roomId,
    defaultProjectId: Long? = this.projectId
): OfflinePhotoEntity {
    val timestamp = now()
    val hasRemote = !remoteUrl.isNullOrBlank()
    val localCachePath = localPath?.takeIf { it.isNotBlank() }

    // Normalize capturedAt: fall back to createdAt to ensure consistent ordering
    val parsedCapturedAt = DateUtils.parseApiDate(capturedAt)
    val parsedCreatedAt = DateUtils.parseApiDate(createdAt) ?: timestamp
    val normalizedCapturedAt = parsedCapturedAt ?: parsedCreatedAt

    // Use provided defaults over DTO values to ensure consistency with sync context
    val resolvedProjectId = defaultProjectId ?: projectId

    return OfflinePhotoEntity(
        photoId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = resolvedProjectId,
        roomId = defaultRoomId,
        logId = logId,
        moistureLogId = moistureLogId,
        albumId = null,
        fileName = fileName ?: "photo_$id.jpg",
        localPath = localPath ?: "",
        remoteUrl = remoteUrl,
        thumbnailUrl = thumbnailUrl,
        uploadStatus = "completed",
        assemblyId = assemblyId,
        tusUploadId = tusUploadId,
        fileSize = fileSize ?: 0,
        width = width,
        height = height,
        mimeType = mimeType ?: "image/jpeg",
        capturedAt = normalizedCapturedAt,
        createdAt = parsedCreatedAt,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false,
        cacheStatus = when {
            localCachePath != null -> PhotoCacheStatus.READY
            hasRemote -> PhotoCacheStatus.PENDING
            else -> PhotoCacheStatus.NONE
        },
        cachedOriginalPath = localCachePath,
        cachedThumbnailPath = null,
        lastAccessedAt = timestamp.takeIf { localCachePath != null }
    )
}

private fun PhotoDto.serverBackedTimestamp(): String? =
    updatedAt ?: capturedAt ?: createdAt

private fun ProjectPhotoListingDto.toPhotoDto(defaultProjectId: Long): PhotoDto {
    val resolvedSizes = sizes
    val resolvedRemoteUrl = resolvedSizes?.gallery
        ?: resolvedSizes?.large
        ?: resolvedSizes?.medium
        ?: resolvedSizes?.raw
    val resolvedThumbnail = resolvedSizes?.small ?: resolvedSizes?.medium
    return PhotoDto(
        id = id,
        uuid = uuid,
        projectId = projectId ?: defaultProjectId,
        roomId = roomId,
        logId = null,
        moistureLogId = null,
        fileName = fileName,
        localPath = null,
        remoteUrl = resolvedRemoteUrl,
        thumbnailUrl = resolvedThumbnail,
        assemblyId = null,
        tusUploadId = null,
        fileSize = null,
        width = null,
        height = null,
        mimeType = contentType,
        capturedAt = createdAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        albums = null // ProjectPhotoListingDto doesn't include album info
    )
}

private fun SyncCheckpointStore.updatedSinceParam(key: String): String? =
    getCheckpoint(key)?.let { DateUtils.formatApiDate(it) }

private fun <T> Iterable<T>.latestTimestamp(extractor: (T) -> String?): Date? =
    this.mapNotNull { DateUtils.parseApiDate(extractor(it)) }.maxOrNull()

private fun AtmosphericLogDto.toEntity(defaultRoomId: Long? = roomId): OfflineAtmosphericLogEntity {
    val timestamp = now()
    return OfflineAtmosphericLogEntity(
        logId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = defaultRoomId,
        date = DateUtils.parseApiDate(date) ?: timestamp,
        relativeHumidity = relativeHumidity ?: 0.0,
        temperature = temperature ?: 0.0,
        dewPoint = dewPoint,
        gpp = gpp,
        pressure = pressure,
        windSpeed = windSpeed,
        isExternal = isExternal ?: false,
        isInlet = isInlet ?: false,
        inletId = inletId,
        outletId = outletId,
        photoUrl = photoUrl,
        photoLocalPath = photoLocalPath,
        photoUploadStatus = photoUploadStatus ?: "completed",
        photoAssemblyId = photoAssemblyId,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false
    )
}

private fun MoistureLogDto.toEntity(): OfflineMoistureLogEntity? {
    val material = materialId ?: damageMaterial?.id ?: return null
    val resolvedReading = reading ?: moistureContent
    val timestamp = now()
    return OfflineMoistureLogEntity(
        logId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = roomId,
        materialId = material,
        date = DateUtils.parseApiDate(date) ?: timestamp,
        moistureContent = resolvedReading ?: 0.0,
        location = location,
        depth = depth,
        photoUrl = photoUrl,
        photoLocalPath = photoLocalPath,
        photoUploadStatus = photoUploadStatus ?: "completed",
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false
    )
}

private fun OfflineMoistureLogEntity.toRequest(
    updatedAtOverride: String? = null
): MoistureLogRequest =
    MoistureLogRequest(
        reading = moistureContent,
        location = location,
        idempotencyKey = uuid,
        updatedAt = updatedAtOverride ?: updatedAt.toApiTimestamp()
    )

private fun DamageMaterialDto.toMaterialEntity(): OfflineMaterialEntity {
    val timestamp = now()
    return OfflineMaterialEntity(
        materialId = id,
        serverId = id,
        uuid = uuid ?: UUID.nameUUIDFromBytes("damage-material-$id".toByteArray()).toString(),
        name = title ?: "Material $id",
        description = description,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp
    )
}

private fun EquipmentDto.toEntity(): OfflineEquipmentEntity {
    val timestamp = now()
    return OfflineEquipmentEntity(
        equipmentId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = roomId,
        type = type ?: "equipment",
        brand = brand,
        model = model,
        serialNumber = serialNumber,
        quantity = quantity ?: 1,
        status = status ?: "active",
        startDate = DateUtils.parseApiDate(startDate),
        endDate = DateUtils.parseApiDate(endDate),
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false
    )
}

private fun OfflineEquipmentEntity.toRequest(
    projectServerId: Long,
    roomServerId: Long?,
    updatedAtOverride: String? = null
): EquipmentRequest =
    EquipmentRequest(
        projectId = projectServerId,
        roomId = roomServerId,
        type = type,
        brand = brand,
        model = model,
        serialNumber = serialNumber,
        quantity = quantity,
        status = status,
        startDate = startDate.toApiTimestamp(),
        endDate = endDate.toApiTimestamp(),
        idempotencyKey = uuid,
        updatedAt = updatedAtOverride ?: updatedAt.toApiTimestamp()
    )

private fun NoteDto.toEntity(): OfflineNoteEntity? {
    val timestamp = now()
    return OfflineNoteEntity(
        noteId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = roomId,
        userId = userId,
        content = body,
        photoId = photoId,
        categoryId = categoryId,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false
    )
}

private fun DamageMaterialDto.toEntity(defaultProjectId: Long? = projectId, defaultRoomId: Long? = null): OfflineDamageEntity? {
    val project = defaultProjectId ?: projectId ?: return null
    val resolvedRoomId = roomId ?: defaultRoomId
    val timestamp = now()
    return OfflineDamageEntity(
        damageId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = project,
        roomId = resolvedRoomId,
        title = title ?: "Damage $id",
        description = description,
        severity = severity,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false
    )
}

private fun WorkScopeDto.toEntity(defaultProjectId: Long? = null, defaultRoomId: Long? = null): OfflineWorkScopeEntity? {
    val resolvedProjectId = defaultProjectId ?: projectId
    val resolvedRoomId = roomId ?: defaultRoomId
    val timestamp = now()
    val numericRate = rate
        ?.replace(Regex("[^0-9.\\-]"), "")
        ?.toDoubleOrNull()
    val numericQuantity = quantity
    val numericLineTotal = lineTotal
        ?.replace(Regex("[^0-9.\\-]"), "")
        ?.toDoubleOrNull() ?: numericRate?.let { rateValue ->
        val qty = numericQuantity ?: 1.0
        rateValue * qty
    }
    val resolvedName = name?.takeIf { it.isNotBlank() }
        ?: description?.takeIf { it.isNotBlank() }
        ?: "Work Scope $id"
    val resolvedDescription = description?.takeIf { it.isNotBlank() } ?: name
    return OfflineWorkScopeEntity(
        workScopeId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = resolvedProjectId,
        roomId = resolvedRoomId,
        name = resolvedName,
        description = resolvedDescription,
        tabName = tabName,
        category = category,
        codePart1 = codePart1,
        codePart2 = codePart2,
        unit = unit,
        rate = numericRate,
        quantity = numericQuantity,
        lineTotal = numericLineTotal,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false
    )
}

// Moisture logs reference materials, ensure placeholders exist
private fun MoistureLogDto.toMaterialEntity(): OfflineMaterialEntity? {
    val material = materialId ?: return null
    val timestamp = now()
    return OfflineMaterialEntity(
        materialId = material,
        serverId = material,
        uuid = UUID.nameUUIDFromBytes("material-$material".toByteArray()).toString(),
        name = "Material $material",
        description = null,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        createdAt = timestamp,
        updatedAt = timestamp,
        lastSyncedAt = timestamp
    )
}

private fun List<MoistureLogDto>.extractMaterials(): List<OfflineMaterialEntity> =
    mapNotNull { it.toMaterialEntity() }

private fun AlbumDto.toEntity(defaultProjectId: Long, defaultRoomId: Long? = null): OfflineAlbumEntity {
    val timestamp = now()
    val normalizedName = name?.trim().takeUnless { it.isNullOrBlank() }
    val isCategoryAlbum = CategoryAlbums.isCategory(normalizedName)
    val projectId: Long
    val roomId: Long?
    when (albumableType) {
        "App\\Models\\Project" -> {
            projectId = albumableId ?: defaultProjectId
            roomId = null
        }
        "App\\Models\\Room" -> {
            projectId = defaultProjectId
            roomId = albumableId ?: defaultRoomId
        }
        else -> {
            // Embedded albums from photo responses don't have albumableType set
            // Use defaultRoomId if provided (means it came from a room photo response)
            projectId = defaultProjectId
            roomId = defaultRoomId
        }
    }
    val resolvedRoomId = if (isCategoryAlbum) null else roomId
    return OfflineAlbumEntity(
        albumId = id,
        projectId = projectId,
        roomId = resolvedRoomId,
        name = normalizedName ?: "Album $id",
        albumableType = albumableType,
        albumableId = albumableId,
        photoCount = 0, // Will be calculated from database via LEFT JOIN with offline_album_photos
        thumbnailUrl = null, // Could be calculated from first photo in database
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp
    )
}
// endregion

private data class PendingProjectCreationPayload(
    val localProjectId: Long,
    val projectUuid: String,
    val companyId: Long,
    val projectStatusId: Int,
    val addressRequest: CreateAddressRequest,
    val idempotencyKey: String?
)

private data class PendingPropertyCreationPayload(
    val localPropertyId: Long,
    val propertyUuid: String,
    val projectId: Long,
    val propertyTypeId: Int,
    val propertyTypeValue: String?,
    val idempotencyKey: String?
)

private data class PendingPropertyUpdatePayload(
    val projectId: Long,
    val propertyId: Long,
    val request: PropertyMutationRequest,
    val propertyTypeValue: String?,
    val lockUpdatedAt: String?
)

private data class PendingLockPayload(
    val lockUpdatedAt: String?
)

private data class PendingLocationCreationPayload(
    val localLocationId: Long,
    val locationUuid: String,
    val projectId: Long,
    val propertyLocalId: Long,
    val locationName: String,
    val locationTypeId: Long,
    val type: String,
    val floorNumber: Int,
    val isCommon: Boolean,
    val isAccessible: Boolean,
    val isCommercial: Boolean,
    val idempotencyKey: String?
)

private data class PendingRoomCreationPayload(
    val localRoomId: Long,
    val roomUuid: String,
    val projectId: Long,
    val roomName: String,
    val roomTypeId: Long,
    val isSource: Boolean,
    val levelServerId: Long?,
    val locationServerId: Long?,
    val levelLocalId: Long?,
    val locationLocalId: Long?,
    val idempotencyKey: String?
)
