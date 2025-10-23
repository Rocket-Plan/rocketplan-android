package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
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
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.example.rocketplan_android.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

class OfflineSyncRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val photoCacheScheduler: PhotoCacheScheduler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun syncCompanyProjects(companyId: Long) = withContext(ioDispatcher) {
        val projects = api.getCompanyProjects(companyId)
        localDataService.saveProjects(projects.map { it.toEntity() })
    }

    suspend fun syncUserProjects(userId: Long) = withContext(ioDispatcher) {
        val projects = api.getUserProjects(userId)
        localDataService.saveProjects(projects.map { it.toEntity() })
    }

    suspend fun syncProjectGraph(projectId: Long) = withContext(ioDispatcher) {
        val detail = runCatching { api.getProjectDetail(projectId) }.getOrNull()
        var didFetchPhotos = false
        detail?.let { dto ->
            val projectEntity = dto.toEntity()
            localDataService.saveProjects(listOf(projectEntity))

            dto.notes?.let { localDataService.saveNotes(it.mapNotNull { note -> note.toEntity() }) }
            dto.users?.let { localDataService.saveUsers(it.map { user -> user.toEntity() }) }
            dto.locations?.let { localDataService.saveLocations(it.map { loc -> loc.toEntity() }) }
            dto.rooms?.let { localDataService.saveRooms(it.map { room -> room.toEntity() }) }
            dto.photos?.let {
                localDataService.savePhotos(it.map { photo -> photo.toEntity() })
                if (it.isNotEmpty()) {
                    didFetchPhotos = true
                }
            }
            dto.atmosphericLogs?.let { localDataService.saveAtmosphericLogs(it.map { log -> log.toEntity() }) }
            dto.moistureLogs?.let {
                val materials = it.extractMaterials()
                if (materials.isNotEmpty()) {
                    localDataService.saveMaterials(materials)
                }
                val moistureLogs = it.mapNotNull { log -> log.toEntity() }
                if (moistureLogs.isNotEmpty()) {
                    localDataService.saveMoistureLogs(moistureLogs)
                }
            }
            dto.equipment?.let { localDataService.saveEquipment(it.map { eq -> eq.toEntity() }) }
            dto.damages?.let { localDataService.saveDamages(it.mapNotNull { dmg -> dmg.toEntity() }) }
            dto.workScopes?.let { localDataService.saveWorkScopes(it.mapNotNull { scope -> scope.toEntity() }) }
        }

        // Property metadata
        val property = fetchProjectProperty(projectId)
        property?.let { localDataService.saveProperty(it.toEntity()) }

        val propertyLocations = property?.id?.let { propertyId ->
            val levels = runCatching { api.getPropertyLevels(propertyId) }.getOrDefault(emptyList())
            val nestedLocations = runCatching { api.getPropertyLocations(propertyId) }.getOrDefault(emptyList())
            levels + nestedLocations
        } ?: emptyList()

        if (propertyLocations.isNotEmpty()) {
            localDataService.saveLocations(propertyLocations.map { it.toEntity() })
        } else {
            val fallbackLocations = runCatching { api.getProjectLocations(projectId) }.getOrDefault(emptyList())
            if (fallbackLocations.isNotEmpty()) {
                localDataService.saveLocations(fallbackLocations.map { it.toEntity() })
            }
        }

        // Rooms
        val projectRooms = runCatching { api.getProjectRooms(projectId) }.getOrDefault(emptyList())
        if (projectRooms.isNotEmpty()) {
            localDataService.saveRooms(projectRooms.map { it.toEntity() })
        }

        // Additional per-location rooms
        val locationIds = (detail?.locations ?: emptyList()).map { it.id } +
            propertyLocations.map { it.id }
        locationIds.distinct().forEach { locationId ->
            val rooms = runCatching { api.getRoomsForLocation(locationId) }.getOrNull()
            rooms?.let {
                localDataService.saveRooms(it.map { room -> room.toEntity(locationId = room.locationId ?: locationId) })
            }
        }

        // Room detail enrichment
        val roomIds = (detail?.rooms ?: emptyList()).map { it.id } +
            projectRooms.map { it.id }
        roomIds.distinct().forEach { roomId ->

            // Room detail
            runCatching { api.getRoomDetail(roomId) }.onSuccess { dto ->
                localDataService.saveRooms(listOf(dto.toEntity()))
            }

            // Room scoped photos/logs/damages/equipment/work scope
            runCatching { api.getRoomPhotos(roomId) }.onSuccess { photos ->
                localDataService.savePhotos(photos.map { it.toEntity() })
                if (photos.isNotEmpty()) {
                    didFetchPhotos = true
                }
            }

            runCatching { api.getRoomAtmosphericLogs(roomId) }.onSuccess { logs ->
                localDataService.saveAtmosphericLogs(logs.map { it.toEntity() })
            }

            runCatching { api.getRoomMoistureLogs(roomId) }.onSuccess { logs ->
                val materials = logs.extractMaterials()
                if (materials.isNotEmpty()) {
                    localDataService.saveMaterials(materials)
                }
                val mappedLogs = logs.mapNotNull { it.toEntity() }
                if (mappedLogs.isNotEmpty()) {
                    localDataService.saveMoistureLogs(mappedLogs)
                }
            }

            runCatching { api.getRoomDamageMaterials(roomId) }.onSuccess { damages ->
                localDataService.saveDamages(damages.mapNotNull { it.toEntity(defaultProjectId = projectId) })
            }

            runCatching { api.getRoomWorkScope(roomId) }.onSuccess { scopes ->
                localDataService.saveWorkScopes(scopes.mapNotNull { it.toEntity() })
            }

            runCatching { api.getRoomEquipment(roomId) }.onSuccess { equipment ->
                localDataService.saveEquipment(equipment.map { it.toEntity() })
            }
        }

        // Project scoped logs/photos/equipment/damages/work scope to ensure coverage
        runCatching { api.getProjectAtmosphericLogs(projectId) }.onSuccess { logs ->
            localDataService.saveAtmosphericLogs(logs.map { it.toEntity(defaultRoomId = null) })
        }

        runCatching { api.getProjectMoistureLogs(projectId) }.onSuccess { logs ->
            val materials = logs.extractMaterials()
            if (materials.isNotEmpty()) {
                localDataService.saveMaterials(materials)
            }
            val mappedLogs = logs.mapNotNull { it.toEntity() }
            if (mappedLogs.isNotEmpty()) {
                localDataService.saveMoistureLogs(mappedLogs)
            }
        }

        runCatching { api.getProjectPhotos(projectId) }.onSuccess { photos ->
            localDataService.savePhotos(photos.map { it.toEntity() })
            if (photos.isNotEmpty()) {
                didFetchPhotos = true
            }
        }

        runCatching { api.getProjectPhotoShares(projectId) }.onSuccess { photos ->
            localDataService.savePhotos(photos.map { it.toEntity() })
            if (photos.isNotEmpty()) {
                didFetchPhotos = true
            }
        }

        runCatching { api.getProjectResourcePhotos(projectId) }.onSuccess { photos ->
            localDataService.savePhotos(photos.map { it.toEntity() })
            if (photos.isNotEmpty()) {
                didFetchPhotos = true
            }
        }

        runCatching { api.getProjectEquipment(projectId) }.onSuccess { equipment ->
            localDataService.saveEquipment(equipment.map { it.toEntity() })
        }

        runCatching { api.getProjectDamageMaterials(projectId) }.onSuccess { damages ->
            localDataService.saveDamages(damages.mapNotNull { it.toEntity(defaultProjectId = projectId) })
        }

        runCatching { api.getProjectWorkScope(projectId) }.onSuccess { scopes ->
            localDataService.saveWorkScopes(scopes.mapNotNull { it.toEntity() })
        }

        runCatching { api.getProjectNotes(projectId) }.onSuccess { notes ->
            localDataService.saveNotes(notes.mapNotNull { it.toEntity() })
        }

        runCatching { api.getProjectUsers(projectId) }.onSuccess { users ->
            localDataService.saveUsers(users.map { it.toEntity() })
        }

        if (didFetchPhotos) {
            photoCacheScheduler.schedulePrefetch()
        }
    }

    private suspend fun fetchProjectProperty(projectId: Long): PropertyDto? {
        val properties = runCatching { api.getProjectProperties(projectId) }.getOrDefault(emptyList())
        return properties.firstOrNull()
    }
}

// region Mappers
private fun now(): Date = Date()

private fun ProjectDto.toEntity(): OfflineProjectEntity {
    val timestamp = now()
    return OfflineProjectEntity(
        projectId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        title = title,
        projectNumber = projectNumber,
        status = status,
        propertyType = propertyType,
        companyId = companyId,
        propertyId = propertyId,
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
    return OfflineProjectEntity(
        projectId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        title = title,
        projectNumber = projectNumber,
        status = status,
        propertyType = propertyType,
        companyId = companyId,
        propertyId = propertyId,
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

private fun LocationDto.toEntity(): OfflineLocationEntity {
    val timestamp = now()
    return OfflineLocationEntity(
        locationId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        title = title,
        type = type,
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

private fun RoomDto.toEntity(locationId: Long? = this.locationId): OfflineRoomEntity {
    val timestamp = now()
    return OfflineRoomEntity(
        roomId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        locationId = locationId,
        title = title,
        roomType = roomType,
        level = level,
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

private fun PhotoDto.toEntity(): OfflinePhotoEntity {
    val timestamp = now()
    val hasRemote = !remoteUrl.isNullOrBlank()
    val localCachePath = localPath?.takeIf { it.isNotBlank() }
    return OfflinePhotoEntity(
        photoId = id,
        serverId = id,
        uuid = uuid ?: UUID.randomUUID().toString(),
        projectId = projectId,
        roomId = roomId,
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
// endregion
