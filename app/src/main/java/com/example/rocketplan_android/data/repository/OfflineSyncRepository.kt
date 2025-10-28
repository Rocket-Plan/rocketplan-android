package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.PhotoCacheStatus
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
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.PaginationMeta
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomPhotoDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.example.rocketplan_android.util.DateUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

private const val ROOM_PHOTO_INCLUDE = "photo,albums,notes_count,creator"
private const val ROOM_PHOTO_PAGE_LIMIT = 30

class OfflineSyncRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val photoCacheScheduler: PhotoCacheScheduler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val gson = Gson()
    private val roomPhotoListType = object : TypeToken<List<RoomPhotoDto>>() {}.type

    suspend fun syncCompanyProjects(companyId: Long) = withContext(ioDispatcher) {
        val projects = fetchAllPages { page ->
            api.getCompanyProjects(companyId = companyId, page = page)
        }
        localDataService.saveProjects(projects.map { it.toEntity() })
    }

    suspend fun syncUserProjects(userId: Long) = withContext(ioDispatcher) {
        val projects = fetchAllPages { page ->
            api.getUserProjects(userId = userId, page = page)
        }
        localDataService.saveProjects(projects.map { it.toEntity() })
    }

    suspend fun syncProjectGraph(projectId: Long) = withContext(ioDispatcher) {
        Log.d("API", "🔄 [syncProjectGraph] Starting sync for project $projectId")

        val detail = runCatching { api.getProjectDetail(projectId) }
            .onSuccess { Log.d("API", "✅ [syncProjectGraph] Fetched project detail for $projectId") }
            .onFailure { Log.e("API", "❌ [syncProjectGraph] Failed to fetch project detail for $projectId", it) }
            .getOrNull()

        var didFetchPhotos = false
        val collectedLocationIds = mutableSetOf<Long>()
        val collectedRoomIds = mutableSetOf<Long>()

        detail?.let { dto ->
            val projectEntity = dto.toEntity()
            localDataService.saveProjects(listOf(projectEntity))
            Log.d("API", "📦 [syncProjectGraph] Saved project $projectId")

            dto.notes?.let {
                localDataService.saveNotes(it.mapNotNull { note -> note.toEntity() })
                Log.d("API", "📝 [syncProjectGraph] Saved ${it.size} notes")
            }
            dto.users?.let {
                localDataService.saveUsers(it.map { user -> user.toEntity() })
                Log.d("API", "👥 [syncProjectGraph] Saved ${it.size} users")
            }
            dto.locations?.let {
                localDataService.saveLocations(it.map { loc -> loc.toEntity(defaultProjectId = dto.id) })
                collectedLocationIds += it.map { loc -> loc.id }
                Log.d("API", "📍 [syncProjectGraph] Saved ${it.size} locations from project detail")
            }
            dto.rooms?.let {
                localDataService.saveRooms(it.map { room -> room.toEntity(projectId = dto.id) })
                collectedRoomIds += it.map { room -> room.id }
                collectedLocationIds += it.mapNotNull { room -> room.locationId }
                Log.d("API", "🏠 [syncProjectGraph] Saved ${it.size} rooms from project detail")
            }
            dto.photos?.let {
                if (persistPhotos(it)) {
                    didFetchPhotos = true
                    Log.d("API", "📸 [syncProjectGraph] Saved ${it.size} photos from project detail")
                }
            }
            dto.atmosphericLogs?.let {
                localDataService.saveAtmosphericLogs(it.map { log -> log.toEntity() })
                Log.d("API", "🌡️ [syncProjectGraph] Saved ${it.size} atmospheric logs")
            }
            dto.moistureLogs?.let {
                val materials = it.extractMaterials()
                if (materials.isNotEmpty()) {
                    localDataService.saveMaterials(materials)
                }
                val moistureLogs = it.mapNotNull { log -> log.toEntity() }
                if (moistureLogs.isNotEmpty()) {
                    localDataService.saveMoistureLogs(moistureLogs)
                    Log.d("API", "💧 [syncProjectGraph] Saved ${moistureLogs.size} moisture logs")
                }
            }
            dto.equipment?.let {
                localDataService.saveEquipment(it.map { eq -> eq.toEntity() })
                Log.d("API", "🔧 [syncProjectGraph] Saved ${it.size} equipment")
            }
            dto.damages?.let {
                localDataService.saveDamages(it.mapNotNull { dmg -> dmg.toEntity() })
                Log.d("API", "⚠️ [syncProjectGraph] Saved ${it.size} damages")
            }
            dto.workScopes?.let {
                localDataService.saveWorkScopes(it.mapNotNull { scope -> scope.toEntity() })
                Log.d("API", "📋 [syncProjectGraph] Saved ${it.size} work scopes")
            }
        }

        // Property metadata
        Log.d("API", "🏢 [syncProjectGraph] Fetching property for project $projectId")
        val property = fetchProjectProperty(projectId)
        if (property != null) {
            localDataService.saveProperty(property.toEntity())
            Log.d("API", "✅ [syncProjectGraph] Fetched and saved property ${property.id}")
        } else {
            Log.w("API", "⚠️ [syncProjectGraph] No property found for project $projectId")
        }

        val propertyLocations = property?.id?.let { propertyId ->
            Log.d("API", "📍 [syncProjectGraph] Fetching locations for property $propertyId")
            val levels = runCatching { api.getPropertyLevels(propertyId) }
                .onSuccess { Log.d("API", "✅ [syncProjectGraph] Fetched ${it.data.size} levels") }
                .onFailure { Log.e("API", "❌ [syncProjectGraph] Failed to fetch levels", it) }
                .getOrNull()?.data ?: emptyList()
            val nestedLocations = runCatching { api.getPropertyLocations(propertyId) }
                .onSuccess { Log.d("API", "✅ [syncProjectGraph] Fetched ${it.data.size} nested locations") }
                .onFailure { Log.e("API", "❌ [syncProjectGraph] Failed to fetch nested locations", it) }
                .getOrNull()?.data ?: emptyList()
            levels + nestedLocations
        } ?: emptyList()

        if (propertyLocations.isNotEmpty()) {
            localDataService.saveLocations(propertyLocations.map { it.toEntity(defaultProjectId = projectId) })
            collectedLocationIds += propertyLocations.map { it.id }
            Log.d("API", "📍 [syncProjectGraph] Saved ${propertyLocations.size} property locations, total collected: ${collectedLocationIds.size}")
        }

        // Rooms
        val locationIds = collectedLocationIds.toList()
        Log.d("API", "🏠 [syncProjectGraph] Fetching rooms for ${locationIds.size} locations")
        locationIds.distinct().forEach { locationId ->
            val rooms = fetchRoomsForLocation(locationId)
            Log.d("API", "🔍 [syncProjectGraph] fetchRoomsForLocation($locationId) returned ${rooms.size} rooms")

            if (rooms.isNotEmpty()) {
                localDataService.saveRooms(
                    rooms.map { room -> room.toEntity(projectId = projectId, locationId = room.locationId ?: locationId) }
                )
                Log.d("API", "💾 [syncProjectGraph] Saved ${rooms.size} rooms for location $locationId to database")
                collectedRoomIds += rooms.map { room -> room.id }
            } else {
                Log.d("API", "⚠️ [syncProjectGraph] No rooms to save for location $locationId (empty list)")
            }
        }
        Log.d("API", "🏠 [syncProjectGraph] Total rooms collected: ${collectedRoomIds.size}")

        // Room photos (paginated)
        val roomIds = collectedRoomIds.distinct()
        if (roomIds.isNotEmpty()) {
            Log.d("API", "📸 [syncProjectGraph] Fetching photos for ${roomIds.size} rooms")
            roomIds.forEach { roomId ->
                val photos = runCatching {
                    fetchRoomPhotoPages(roomId = roomId, projectId = projectId)
                }.onSuccess { converted ->
                    if (converted.isEmpty()) {
                        Log.d("API", "INFO [syncProjectGraph] No photos returned for room $roomId")
                    } else {
                        Log.d("API", "✅ [syncProjectGraph] Fetched ${converted.size} photos for room $roomId (paginated)")
                    }
                }.getOrElse { error ->
                    if (error is retrofit2.HttpException && error.code() == 404) {
                        Log.d("API", "INFO [syncProjectGraph] Room $roomId has no photos (404)")
                        return@forEach
                    } else {
                        Log.e("API", "❌ [syncProjectGraph] Failed to fetch photos for room $roomId", error)
                        return@forEach
                    }
                }

                if (photos.isNotEmpty()) {
                    val saved = persistPhotos(photos, defaultRoomId = roomId)
                    if (saved) {
                        didFetchPhotos = true
                        Log.d("API", "💾 [syncProjectGraph] Saved ${photos.size} photos for room $roomId")
                    }
                }
            }
        }

        // Project scoped logs/photos/equipment/damages/work scope to ensure coverage
        runCatching { api.getProjectAtmosphericLogs(projectId) }.onSuccess { logs ->
            localDataService.saveAtmosphericLogs(logs.map { it.toEntity(defaultRoomId = null) })
        }

        val floorPhotos = runCatching {
            fetchAllPages { page -> api.getProjectFloorPhotos(projectId, page) }
                .map { it.toPhotoDto(projectId) }
        }.getOrDefault(emptyList())
        if (persistPhotos(floorPhotos)) {
            didFetchPhotos = true
        }

        val locationPhotos = runCatching {
            fetchAllPages { page -> api.getProjectLocationPhotos(projectId, page) }
                .map { it.toPhotoDto(projectId) }
        }.getOrDefault(emptyList())
        if (persistPhotos(locationPhotos)) {
            didFetchPhotos = true
        }

        val unitPhotos = runCatching {
            fetchAllPages { page -> api.getProjectUnitPhotos(projectId, page) }
                .map { it.toPhotoDto(projectId) }
        }.getOrDefault(emptyList())
        if (persistPhotos(unitPhotos)) {
            didFetchPhotos = true
        }

        runCatching { api.getProjectEquipment(projectId) }.onSuccess { equipment ->
            localDataService.saveEquipment(equipment.map { it.toEntity() })
        }

        runCatching { api.getProjectDamageMaterials(projectId) }.onSuccess { damages ->
            localDataService.saveDamages(damages.mapNotNull { it.toEntity(defaultProjectId = projectId) })
        }

        runCatching { api.getProjectNotes(projectId) }.onSuccess { notes ->
            localDataService.saveNotes(notes.mapNotNull { it.toEntity() })
        }

        runCatching { api.getProjectUsers(projectId) }.onSuccess { users ->
            localDataService.saveUsers(users.map { it.toEntity() })
        }

        // Albums (album-photo relationships are synced via photo.albums when fetching room photos)
        runCatching {
            fetchAllPages { page -> api.getProjectAlbums(projectId, page) }
        }.onSuccess { albums ->
            Log.d("API", "📚 [syncProjectGraph] Fetched ${albums.size} albums from API")
            albums.forEach { album ->
                Log.d("API", "   Album[${album.id}] '${album.name}' type=${album.albumableType}")
            }
            val albumEntities = albums.map { it.toEntity(defaultProjectId = projectId) }
            localDataService.saveAlbums(albumEntities)
            Log.d("API", "📚 [syncProjectGraph] Saved ${albums.size} albums (photo counts will be calculated from database)")
        }.onFailure {
            Log.e("API", "❌ [syncProjectGraph] Failed to fetch albums", it)
        }

        if (didFetchPhotos) {
            photoCacheScheduler.schedulePrefetch()
        }

        Log.d("API", "✅ [syncProjectGraph] Sync completed for project $projectId - Locations: ${collectedLocationIds.size}, Rooms: ${collectedRoomIds.size}")
    }

    suspend fun refreshRoomPhotos(projectId: Long, roomId: Long) = withContext(ioDispatcher) {
        Log.d("API", "🔄 [refreshRoomPhotos] Requesting photos for room $roomId (project $projectId)")

        val photos = runCatching {
            fetchRoomPhotoPages(roomId = roomId, projectId = projectId)
        }.onFailure { error ->
            if (error is retrofit2.HttpException && error.code() == 404) {
                Log.d("API", "INFO [refreshRoomPhotos] Room $roomId has no photos (404)")
            } else {
                Log.e("API", "❌ [refreshRoomPhotos] Failed to fetch photos for room $roomId", error)
            }
        }.getOrElse { emptyList() }

        if (photos.isEmpty()) {
            Log.d("API", "ℹ️ [refreshRoomPhotos] No photos returned for room $roomId")
            return@withContext
        }

        if (persistPhotos(photos, defaultRoomId = roomId)) {
            Log.d("API", "💾 [refreshRoomPhotos] Saved ${photos.size} photos for room $roomId")
            photoCacheScheduler.schedulePrefetch()
        }
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

    private suspend fun fetchRoomPhotoPages(roomId: Long, projectId: Long): List<PhotoDto> {
        val collected = mutableListOf<PhotoDto>()
        var page = 1

        while (true) {
            val json = api.getRoomPhotos(
                roomId = roomId,
                page = page,
                limit = ROOM_PHOTO_PAGE_LIMIT,
                include = ROOM_PHOTO_INCLUDE
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
        defaultRoomId: Long? = null
    ): Boolean {
        if (photos.isEmpty()) {
            return false
        }

        val entities = mutableListOf<OfflinePhotoEntity>()
        for (photo in photos) {
            val existing = localDataService.getPhotoByServerId(photo.id)
            val preservedRoom = existing?.roomId
            val resolvedRoomId = defaultRoomId ?: photo.roomId ?: preservedRoom
            entities += photo.toEntity(defaultRoomId = resolvedRoomId)
        }
        localDataService.savePhotos(entities)

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

        return true
    }

    private data class RoomPhotoPageResult(
        val photos: List<PhotoDto>,
        val hasMore: Boolean,
        val nextPage: Int?
    )

    private suspend fun fetchProjectProperty(projectId: Long): PropertyDto? {
        val response = runCatching { api.getProjectProperties(projectId) }.getOrNull()
        return response?.data?.firstOrNull()
    }

    private suspend fun fetchRoomsForLocation(locationId: Long): List<RoomDto> {
        val collected = mutableListOf<RoomDto>()
        var page = 1
        while (true) {
            val response = runCatching { api.getRoomsForLocation(locationId, page = page) }
                .onSuccess { result ->
                    val size = result.data.size
                    if (size == 0 && page == 1) {
                        Log.d("API", "INFO [syncProjectGraph] No rooms returned for location $locationId")
                    } else if (size > 0) {
                        Log.d("API", "✅ [syncProjectGraph] Fetched $size rooms for location $locationId (page $page)")
                    }
                }
                .onFailure { error ->
                    if (error is retrofit2.HttpException && error.code() == 404) {
                        Log.d("API", "INFO [syncProjectGraph] Location $locationId has no rooms (404)")
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
}

// region Mappers
private fun now(): Date = Date()

private fun ProjectDto.toEntity(): OfflineProjectEntity {
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
    val resolvedUuid = uuid ?: uid ?: "project-$id"
    val resolvedStatus = status?.takeIf { it.isNotBlank() } ?: "unknown"
    val resolvedPropertyId = propertyId ?: address?.id
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
        propertyType = propertyType,
        companyId = companyId,
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

private fun com.example.rocketplan_android.data.model.offline.ProjectDetailDto.toEntity(): OfflineProjectEntity {
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
    val resolvedUuid = uuid ?: uid ?: "project-$id"
    val resolvedStatus = status?.takeIf { it.isNotBlank() } ?: "unknown"
    val resolvedPropertyId = propertyId ?: address?.id
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
        propertyType = propertyType,
        companyId = companyId,
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

private fun PropertyDto.toEntity(): OfflinePropertyEntity {
    val timestamp = now()
    return OfflinePropertyEntity(
        propertyId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        address = address ?: "",
        city = city,
        state = state,
        zipCode = postalCode,
        latitude = latitude,
        longitude = longitude,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
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

private fun RoomDto.toEntity(projectId: Long, locationId: Long? = this.locationId): OfflineRoomEntity {
    val timestamp = now()
    return OfflineRoomEntity(
        roomId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,  // Use passed projectId instead of DTO projectId
        locationId = locationId,
        title = title ?: name ?: roomType?.name ?: "Room $id",  // Match iOS: title -> name -> roomType.name
        roomType = roomType?.name,  // Extract name from RoomTypeDto object for category
        level = level?.name ?: level?.title,  // Extract name from LocationDto object
        squareFootage = squareFootage,
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

private fun RoomPhotoDto.toPhotoDto(defaultProjectId: Long, defaultRoomId: Long): PhotoDto {
    val nested = photo
    val resolvedProjectId = nested?.projectId ?: defaultProjectId
    val resolvedRoomId = nested?.roomId ?: photoableId ?: defaultRoomId
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

private fun PhotoDto.toEntity(defaultRoomId: Long? = this.roomId): OfflinePhotoEntity {
    val timestamp = now()
    val hasRemote = !remoteUrl.isNullOrBlank()
    val localCachePath = localPath?.takeIf { it.isNotBlank() }
    return OfflinePhotoEntity(
        photoId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
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
        capturedAt = DateUtils.parseApiDate(capturedAt),
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
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
    val material = materialId ?: return null
    val timestamp = now()
    return OfflineMoistureLogEntity(
        logId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = roomId,
        materialId = material,
        date = DateUtils.parseApiDate(date) ?: timestamp,
        moistureContent = moistureContent ?: 0.0,
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

private fun NoteDto.toEntity(): OfflineNoteEntity? {
    val timestamp = now()
    return OfflineNoteEntity(
        noteId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = roomId,
        userId = userId,
        content = content,
        createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = 1,
        isDirty = false,
        isDeleted = false
    )
}

private fun DamageMaterialDto.toEntity(defaultProjectId: Long? = projectId): OfflineDamageEntity? {
    val project = defaultProjectId ?: projectId ?: return null
    val timestamp = now()
    return OfflineDamageEntity(
        damageId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = project,
        roomId = roomId,
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

private fun WorkScopeDto.toEntity(): OfflineWorkScopeEntity? {
    val timestamp = now()
    return OfflineWorkScopeEntity(
        workScopeId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = roomId,
        name = name ?: "Work Scope $id",
        description = description,
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

private fun AlbumDto.toEntity(defaultProjectId: Long): OfflineAlbumEntity {
    val timestamp = now()
    val projectId: Long
    val roomId: Long?
    when (albumableType) {
        "App\\Models\\Project" -> {
            projectId = albumableId ?: defaultProjectId
            roomId = null
        }
        "App\\Models\\Room" -> {
            projectId = defaultProjectId
            roomId = albumableId
        }
        else -> {
            projectId = defaultProjectId
            roomId = null
        }
    }
    return OfflineAlbumEntity(
        albumId = id,
        projectId = projectId,
        roomId = roomId,
        name = name ?: "Album $id",
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
