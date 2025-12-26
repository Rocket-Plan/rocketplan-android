package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineCatalogLevelEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.repository.RoomDeletionResult
import com.example.rocketplan_android.data.repository.RoomTypeRepository
import com.example.rocketplan_android.data.repository.RoomTypeRepository.RequestType
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.util.Date
import java.util.UUID

/**
 * Service responsible for room, location, and default catalog handling.
 * Extracted from OfflineSyncRepository to keep room logic focused.
 */
class RoomSyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val roomTypeRepository: RoomTypeRepository,
    private val syncQueueEnqueuer: () -> SyncQueueEnqueuer,
    private val syncProjectEssentials: suspend (Long) -> SyncResult,
    private val logLocalDeletion: (String, Long, String?) -> Unit,
    private val removePhotoFiles: (OfflinePhotoEntity) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val isNetworkAvailable: () -> Boolean = { false } // Default to offline for safety
) {
    private fun now() = Date()

    suspend fun resolveExistingRoomForSync(
        projectId: Long,
        room: RoomDto
    ): OfflineRoomEntity? {
        val byServerId = localDataService.getRoomByServerId(room.id)
        if (byServerId != null) return byServerId
        val uuid = room.uuid?.takeIf { it.isNotBlank() }
        val byUuid = uuid?.let { localDataService.getRoomByUuid(it) }
        if (byUuid != null) return byUuid
        val candidateTitle = when {
            !room.title.isNullOrBlank() -> room.title
            !room.name.isNullOrBlank() -> room.name
            !room.roomType?.name.isNullOrBlank() && (room.typeOccurrence ?: 1) > 1 ->
                "${room.roomType?.name} ${room.typeOccurrence}"
            !room.roomType?.name.isNullOrBlank() -> room.roomType?.name
            else -> null
        }
        return candidateTitle?.let { title ->
            val pendingCount = localDataService.countPendingRoomsForProjectTitle(projectId, title)
            when {
                pendingCount == 1 -> localDataService.getPendingRoomForProject(projectId, title)
                pendingCount > 1 -> {
                    Log.w(
                        TAG,
                        "[resolveExistingRoomForSync] Multiple pending rooms named '$title' for project=$projectId; skipping title match"
                    )
                    null
                }
                else -> null
            }
        }
    }

    suspend fun createRoom(
        projectId: Long,
        roomName: String,
        roomTypeId: Long,
        roomTypeName: String? = null,
        isSource: Boolean = false,
        isExterior: Boolean = false,
        idempotencyKey: String? = null
    ): Result<OfflineRoomEntity> = withContext(ioDispatcher) {
        val pendingRoom = localDataService.getPendingRoomForProject(projectId, roomName)
        val roomUuid = pendingRoom?.uuid ?: UUID.randomUUID().toString()
        Log.d(
            TAG,
            "[createRoom] Using roomUuid=$roomUuid (pending=${pendingRoom != null}) projectId=$projectId"
        )
        val resolvedIdempotencyKey = idempotencyKey ?: roomUuid
        val localRoom = pendingRoom ?: createPendingRoom(
            projectId = projectId,
            roomName = roomName,
            roomTypeId = roomTypeId,
            roomTypeName = roomTypeName,
            isSource = isSource,
            isExterior = isExterior,
            idempotencyKey = resolvedIdempotencyKey,
            forcedUuid = roomUuid
        )
        if (localRoom == null) {
            Log.e(TAG, "[createRoom] Unable to create pending room for project $projectId - no valid location available (offline?)")
            return@withContext Result.failure(
                IllegalStateException("Unable to create room. Please sync the project first or check your network connection.")
            )
        }
        Result.success(localRoom)
    }

    suspend fun createDefaultLocationAndRoom(
        projectId: Long,
        propertyTypeValue: String?,
        locationName: String,
        seedDefaultRoom: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        val config = resolveLocationDefaults(propertyTypeValue)
        val fallbackName = locationName.ifBlank { "Unit" }

        runCatching {
            runCatching { roomTypeRepository.ensureOfflineCatalogCached() }
                .onFailure { Log.w(TAG, "[createDefaultLocationAndRoom] Unable to hydrate offline catalog", it) }
            val existingLocations = localDataService.getLocations(projectId)
            val defaultLevels = resolveDefaultCatalogLevels(propertyTypeValue)
            if (defaultLevels.isNotEmpty()) {
                defaultLevels.forEach { level ->
                    val levelName = level.name?.takeIf { it.isNotBlank() } ?: fallbackName
                    val alreadyExists = existingLocations.any { it.title.equals(levelName, ignoreCase = true) }
                    if (!alreadyExists) {
                        createPendingLocation(
                            projectId = projectId,
                            locationName = levelName,
                            config = config,
                            idempotencyKey = UUID.randomUUID().toString()
                        )
                    }
                }
            } else {
                val alreadyExists = existingLocations.any { it.title.equals(fallbackName, ignoreCase = true) }
                if (!alreadyExists) {
                    createPendingLocation(
                        projectId = projectId,
                        locationName = fallbackName,
                        config = config,
                        idempotencyKey = UUID.randomUUID().toString()
                    )
                }
            }

            if (seedDefaultRoom) {
                val roomType = roomTypeRepository
                    .getRoomTypes(projectId, RequestType.INTERIOR, forceRefresh = false)
                    .getOrNull()
                    ?.firstOrNull()
                    ?: roomTypeRepository
                        .getRoomTypes(projectId, RequestType.INTERIOR, forceRefresh = true)
                        .getOrNull()
                        ?.firstOrNull()

                if (roomType != null) {
                    createRoom(
                        projectId = projectId,
                        roomName = roomType.name ?: "Room",
                        roomTypeId = roomType.id,
                        roomTypeName = roomType.name,
                        isSource = true
                    )
                } else {
                    Log.w(
                        TAG,
                        "[createDefaultLocationAndRoom] No room types available; skipping default room creation"
                    )
                }
            }
            Unit
        }.onFailure { error ->
            Log.e(
                TAG,
                "[createDefaultLocationAndRoom] Failed to seed default location/room for project=$projectId",
                error
            )
        }
    }

    suspend fun deleteRoom(projectId: Long, roomId: Long): RoomDeletionResult = withContext(ioDispatcher) {
        val room = localDataService.getRoom(roomId)
            ?: throw IllegalArgumentException("Room not found: $roomId")

        Log.d(
            TAG,
            "[deleteRoom] Marking room for deletion (projectId=$projectId, localId=${room.roomId}, serverId=${room.serverId})"
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
                Log.w(TAG, "[deleteRoom] Failed to clear photo snapshot for roomId=$snapshotRoomId", it)
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
        syncQueueEnqueuer().enqueueRoomDeletion(marked, lockUpdatedAt)
        RoomDeletionResult(synced = false)
    }

    suspend fun fetchRoomsForLocation(locationId: Long): List<RoomDto> {
        if (locationId <= 0) {
            Log.w(TAG, "[syncProjectGraph] Skipping invalid locationId=$locationId")
            return emptyList()
        }
        val collected = mutableListOf<RoomDto>()
        var page = 1
        val updatedSince = localDataService.getLatestRoomUpdateForLocation(locationId)
            ?.let { DateUtils.formatApiDate(it) }

        if (updatedSince != null) {
            Log.d(TAG, "[FAST] Requesting rooms for location $locationId since $updatedSince (incremental)")
        } else {
            Log.d(TAG, "[FAST] Requesting rooms for location $locationId (full sync - first run)")
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
                        Log.d(TAG, "INFO [FAST] No rooms returned for location $locationId")
                    } else if (size > 0) {
                        Log.d(TAG, "[FAST] Fetched $size rooms for location $locationId (page $page)")
                    }
                }
                .onFailure { error ->
                    if (error is HttpException && error.code() == 404) {
                        Log.d(TAG, "INFO [syncProjectGraph] Location $locationId has no rooms (404)")
                        runCatching { localDataService.markLocationsDeleted(listOf(locationId)) }
                            .onFailure { Log.w(TAG, "[syncProjectGraph] Failed to mark missing location $locationId as deleted", it) }
                    } else {
                        Log.e(TAG, "[syncProjectGraph] Failed to fetch rooms for location $locationId", error)
                    }
                }
                .getOrNull()

            if (response == null) {
                if (collected.isNotEmpty()) {
                    Log.d(TAG, "[syncProjectGraph] Returning ${collected.size} rooms collected before error for location $locationId")
                }
                return collected
            }

            val data = response.data

            if (data.isNotEmpty() && page == 1) {
                val firstRoom = data.first()
                Log.d(
                    TAG,
                    "[DEBUG] Room payload - id: ${firstRoom.id}, name: ${firstRoom.name}, title: ${firstRoom.title}, " +
                        "roomType.name: ${firstRoom.roomType?.name}, level.name: ${firstRoom.level?.name}"
                )
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

    private suspend fun resolveDefaultCatalogLevels(propertyTypeValue: String?): List<OfflineCatalogLevelEntity> {
        val levels = localDataService.getOfflineCatalogLevels()
        if (levels.isEmpty()) return emptyList()
        val propertyTypeId = roomTypeRepository.resolveCatalogPropertyTypeId(propertyTypeValue)
        val filtered = if (propertyTypeId == null) {
            levels
        } else {
            levels.filter { it.propertyTypeIds.isEmpty() || it.propertyTypeIds.contains(propertyTypeId) }
        }
        val defaults = filtered.filter { it.isDefault == true }
        return when {
            defaults.isNotEmpty() -> defaults
            filtered.any { it.isStandard == true } -> filtered.filter { it.isStandard == true }
            else -> filtered
        }
    }

    private suspend fun createPendingRoom(
        projectId: Long,
        roomName: String,
        roomTypeId: Long,
        roomTypeName: String?,
        isSource: Boolean,
        isExterior: Boolean,
        idempotencyKey: String,
        forcedUuid: String? = null
    ): OfflineRoomEntity? {
        val project = localDataService.getProject(projectId)
        if (project == null) {
            Log.w(TAG, "[createPendingRoom] Project $projectId not found locally")
            return null
        }
        val resolvedLocationPair = resolveLocationForRoom(projectId, isExterior)
        val (level, location) = resolvedLocationPair
            ?: run {
                Log.w(TAG, "[createPendingRoom] No valid locations for project $projectId after refresh")
                return null
            }
        val timestamp = now()
        val localId = -System.currentTimeMillis()
        val pending = OfflineRoomEntity(
            roomId = localId,
            serverId = null,
            uuid = forcedUuid ?: UUID.randomUUID().toString(),
            projectId = project.projectId,
            locationId = location.locationId,
            title = roomName,
            roomType = roomTypeName,
            roomTypeId = roomTypeId,
            // Filter out purely numeric/dash strings to prevent IDs from becoming level names
            level = (level.title ?: level.type)?.takeIf {
                !it.all { c -> c.isDigit() || c == '-' }
            } ?: "Level",
            syncStatus = SyncStatus.PENDING,
            syncVersion = 0,
            isDirty = true,
            isDeleted = false,
            createdAt = timestamp,
            updatedAt = timestamp,
            lastSyncedAt = null
        )
        localDataService.saveRooms(listOf(pending))
        syncQueueEnqueuer().enqueueRoomCreation(
            room = pending,
            roomTypeId = roomTypeId,
            roomTypeName = roomTypeName,
            isSource = isSource,
            isExterior = isExterior,
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
    ): OfflineLocationEntity? {
        var project = localDataService.getProject(projectId)
        if (project == null) {
            Log.w(TAG, "[createPendingLocation] Project $projectId not found locally")
            return null
        }
        var propertyLocalId = project.propertyId
        if (propertyLocalId == null) {
            // Create a pending property on-the-fly for offline support
            Log.d(TAG, "[createPendingLocation] Creating pending property for project $projectId (offline mode)")
            val pendingProperty = createPendingPropertyForProject(project)
            propertyLocalId = pendingProperty.propertyId
            // Refresh project to get updated propertyId
            project = localDataService.getProject(projectId) ?: project
        }

        // Locations created here are top-level (levels/floors), not children
        // The level vs location distinction is handled by resolveLocationForRoom
        val timestamp = now()
        val localId = -System.currentTimeMillis()
        val pending = OfflineLocationEntity(
            locationId = localId,
            serverId = null,
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            title = locationName,
            type = config.type,
            parentLocationId = null,  // Top-level location (level/floor)
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
        syncQueueEnqueuer().enqueueLocationCreation(
            location = pending,
            propertyLocalId = propertyLocalId,
            locationName = locationName,
            locationTypeId = config.locationTypeId,
            type = config.type,
            floorNumber = config.floorNumber,
            isCommon = config.isCommon,
            isAccessible = config.isAccessible,
            isCommercial = config.isCommercial,
            idempotencyKey = idempotencyKey
        )
        return pending
    }

    private suspend fun resolveLocationForRoom(
        projectId: Long,
        isExterior: Boolean
    ): Pair<OfflineLocationEntity, OfflineLocationEntity>? {
        suspend fun currentLocations(): List<OfflineLocationEntity> =
            localDataService.getLocations(projectId)

        var locations = currentLocations()
        if (locations.isEmpty() && isNetworkAvailable()) {
            // Only try to sync from network if online
            runCatching { syncProjectEssentials(projectId) }
                .onFailure {
                    Log.w(TAG, "[createRoom] Failed to refresh locations for project $projectId", it)
                }
            locations = currentLocations()
        }

        if (locations.isEmpty()) {
            val project = localDataService.getProject(projectId)
            // Filter out purely numeric/dash strings to prevent IDs from becoming location names
            val defaultName = project?.title?.takeIf {
                it.isNotBlank() && !it.all { c -> c.isDigit() || c == '-' }
            } ?: "Unit"
            val propertyTypeValue = project?.propertyType
            runCatching {
                createDefaultLocationAndRoom(
                    projectId = projectId,
                    propertyTypeValue = propertyTypeValue,
                    locationName = defaultName,
                    seedDefaultRoom = false
                )
            }.onFailure {
                Log.w(TAG, "[createRoom] Failed to create default location for project $projectId", it)
            }
            locations = currentLocations()
        }

        // When offline, skip server validation and trust all locations
        val validated = if (isNetworkAvailable()) {
            locations.filter { it.serverId == null || validateLocationOnServer(it) }
        } else {
            Log.d(TAG, "[resolveLocationForRoom] Offline mode - skipping server validation for ${locations.size} locations")
            locations
        }
        if (validated.isEmpty()) return null

        val preferredTitlesRaw = if (isExterior) {
            listOf("Ground Level", "North", "South", "East", "West", "Rooftop")
        } else {
            listOf("Main Level", "Upper Level", "Loft", "Attic", "Basement")
        }
        val preferredTitles = preferredTitlesRaw.map { it.lowercase() }

        fun pickPreferred(locations: List<OfflineLocationEntity>): OfflineLocationEntity? =
            locations.firstOrNull { it.title.lowercase() in preferredTitles }

        var location = pickPreferred(validated)

        if (location == null) {
            val project = localDataService.getProject(projectId)
            val defaultName = preferredTitlesRaw.firstOrNull()
                ?: project?.title
                ?: "Unit"
            val propertyTypeValue = project?.propertyType
            runCatching {
                createDefaultLocationAndRoom(
                    projectId = projectId,
                    propertyTypeValue = propertyTypeValue,
                    locationName = defaultName,
                    seedDefaultRoom = false
                )
            }.onFailure {
                Log.w(TAG, "[createRoom] Failed to seed default level '$defaultName' for project $projectId", it)
            }
            val refreshed = localDataService.getLocations(projectId)
            val refreshedValidated = if (isNetworkAvailable()) {
                refreshed.filter { it.serverId == null || validateLocationOnServer(it) }
            } else {
                refreshed
            }
            location = pickPreferred(refreshedValidated) ?: refreshedValidated.firstOrNull()
        }

        location = location ?: validated.first()

        val level = validated.firstOrNull { it.serverId != null && it.serverId == location.parentLocationId }
            ?: validated.firstOrNull { it.locationId == location.parentLocationId }
            // If no parent found, look for any synced top-level location to use as the level
            ?: validated.firstOrNull { it.serverId != null && it.parentLocationId == null && it.locationId != location.locationId }
            // Also check for any pending top-level location (fully offline scenario)
            ?: validated.firstOrNull { it.parentLocationId == null && it.locationId != location.locationId }
            // Last resort: use location itself (valid for single-unit properties)
            ?: location

        return level to location
    }

    private suspend fun validateLocationOnServer(location: OfflineLocationEntity): Boolean {
        val serverId = location.serverId ?: return false
        return runCatching {
            api.getRoomsForLocation(
                locationId = serverId,
                page = 1,
                updatedSince = null
            )
        }.map {
            true
        }.getOrElse { error ->
            if (error is HttpException && error.code() == 404) {
                // 404 from getRoomsForLocation means "no rooms", not "location doesn't exist"
                // The location is still valid, just empty
                true
            } else {
                Log.w(TAG, "[createRoom] Failed to validate location $serverId", error)
                false
            }
        }
    }

    /**
     * Creates a pending property for a project that doesn't have one yet.
     * This enables fully offline room creation even when the property hasn't been synced.
     */
    private suspend fun createPendingPropertyForProject(
        project: com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
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
            propertyType = project.propertyType
        )

        // Enqueue for sync when back online
        // Resolve the correct property type ID from catalog, or use fallback
        val propertyTypeId = (roomTypeRepository.resolveCatalogPropertyTypeId(project.propertyType)
            ?: RoomTypeRepository.fallbackPropertyTypeId(
                RoomTypeRepository.normalizePropertyType(project.propertyType) ?: "single_unit"
            )
            ?: 1L).toInt() // Convert to Int for enqueue API
        syncQueueEnqueuer().enqueuePropertyCreation(
            property = pending,
            projectId = project.projectId,
            propertyTypeId = propertyTypeId,
            propertyTypeValue = project.propertyType,
            idempotencyKey = pending.uuid
        )

        Log.d(TAG, "[createPendingPropertyForProject] Created pending property ${pending.propertyId} for project ${project.projectId}")
        return pending
    }

    companion object {
        private const val TAG = "API"
    }
}
