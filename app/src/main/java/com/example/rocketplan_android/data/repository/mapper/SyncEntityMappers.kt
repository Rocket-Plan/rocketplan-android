package com.example.rocketplan_android.data.repository.mapper

import android.util.Log
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
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
import com.example.rocketplan_android.data.model.CategoryAlbums
import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.EquipmentRequest
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.MoistureLogRequest
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomPhotoDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.util.DateUtils
import java.util.Date
import java.util.UUID

/**
 * Extension functions and mappers for converting DTOs to offline entities.
 * Extracted from OfflineSyncRepository to reduce file size and improve maintainability.
 */

internal fun now(): Date = Date()

internal fun Date?.toApiTimestamp(): String? = this?.let(DateUtils::formatApiDate)

internal fun SyncCheckpointStore.updatedSinceParam(key: String): String? =
    getCheckpoint(key)?.let { DateUtils.formatApiDate(it) }

internal fun <T> Iterable<T>.latestTimestamp(extractor: (T) -> String?): Date? =
    this.mapNotNull { DateUtils.parseApiDate(extractor(it)) }.maxOrNull()

/**
 * Shared helper for building OfflineProjectEntity from common project fields.
 * Used by both ProjectDto.toEntity() and ProjectDetailDto.toEntity() to reduce duplication.
 */
internal fun buildProjectEntity(
    id: Long,
    uuid: String?,
    uid: String?,
    title: String?,
    alias: String?,
    projectNumber: String?,
    status: String?,
    addressLine1: String?,
    addressLine2: String?,
    propertyId: Long?,
    embeddedPropertyId: Long?,
    propertyType: String?,
    companyId: Long?,
    createdAt: String?,
    updatedAt: String?,
    existing: OfflineProjectEntity?,
    fallbackCompanyId: Long?,
    includeExistingTitle: Boolean,
    dtoName: String
): OfflineProjectEntity {
    if (id == 0L) {
        Log.e("SyncEntityMappers", "BUG FOUND! $dtoName.toEntity() called with id=0", Exception("Stack trace"))
        Log.e("SyncEntityMappers", "   $dtoName: id=$id, uuid=$uuid, uid=$uid, title=$title, propertyId=$propertyId")
    }
    val timestamp = now()

    val titleCandidates = buildList {
        add(addressLine1)
        add(title?.takeIf { it.isNotBlank() })
        if (includeExistingTitle) add(existing?.title?.takeIf { it.isNotBlank() })
        add(alias?.takeIf { it.isNotBlank() })
        add(projectNumber?.takeIf { it.isNotBlank() })
        add(uid?.takeIf { it.isNotBlank() })
    }
    val resolvedTitle = titleCandidates.firstOrNull { it != null } ?: "Project $id"
    val resolvedUuid = uuid ?: uid ?: existing?.uuid ?: "project-$id"
    val resolvedStatus = status?.takeIf { it.isNotBlank() } ?: "unknown"

    // Preserve existing propertyId - only update if we have no local property
    // This prevents project list sync from overwriting with wrong propertyId
    val resolvedPropertyId = existing?.propertyId
        ?: propertyId
        ?: embeddedPropertyId
    if (existing?.propertyId != resolvedPropertyId) {
        Log.d("SyncEntityMappers", "⚠️ [buildProjectEntity] id=$id existing.propertyId=${existing?.propertyId} dto.propertyId=$propertyId -> resolved=$resolvedPropertyId")
    }

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

internal fun ProjectDto.toEntity(existing: OfflineProjectEntity? = null, fallbackCompanyId: Long? = null): OfflineProjectEntity =
    buildProjectEntity(
        id = id,
        uuid = uuid,
        uid = uid,
        title = title,
        alias = alias,
        projectNumber = projectNumber,
        status = status,
        addressLine1 = address?.address?.takeIf { it.isNotBlank() } ?: existing?.addressLine1,
        addressLine2 = address?.address2?.takeIf { it.isNotBlank() } ?: existing?.addressLine2,
        propertyId = propertyId,
        embeddedPropertyId = properties?.firstOrNull()?.id,
        propertyType = propertyType ?: existing?.propertyType,
        companyId = companyId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        existing = existing,
        fallbackCompanyId = fallbackCompanyId,
        includeExistingTitle = true,
        dtoName = "ProjectDto"
    )

internal fun ProjectDetailDto.toEntity(
    existing: OfflineProjectEntity? = null,
    fallbackCompanyId: Long? = null
): OfflineProjectEntity =
    buildProjectEntity(
        id = id,
        uuid = uuid,
        uid = uid,
        title = title,
        alias = alias,
        projectNumber = projectNumber,
        status = status,
        addressLine1 = address?.address?.takeIf { it.isNotBlank() },
        addressLine2 = address?.address2?.takeIf { it.isNotBlank() },
        propertyId = propertyId,
        embeddedPropertyId = properties?.firstOrNull()?.id,
        propertyType = propertyType ?: properties?.firstOrNull()?.resolvedPropertyType() ?: existing?.propertyType,
        companyId = companyId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        existing = existing,
        fallbackCompanyId = fallbackCompanyId,
        includeExistingTitle = false,
        dtoName = "ProjectDetailDto"
    )

internal fun UserDto.toEntity(): OfflineUserEntity {
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

internal fun PropertyDto.toEntity(
    existing: OfflinePropertyEntity? = null,
    projectAddress: ProjectAddressDto? = null
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
    // When existing is a pending property (negative ID) but server returned a positive ID,
    // use the server ID to complete the ID resolution
    val resolvedId = if (existing?.propertyId != null && existing.propertyId < 0 && id > 0) {
        id
    } else {
        existing?.propertyId ?: id
    }

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

internal fun LocationDto.toEntity(defaultProjectId: Long? = null): OfflineLocationEntity {
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

internal fun RoomDto.toEntity(
    existing: OfflineRoomEntity?,
    projectId: Long,
    locationId: Long? = this.locationId
): OfflineRoomEntity {
    val timestamp = now()
    val serverId = id.takeIf { it > 0 }
    val resolvedUuid = uuid ?: existing?.uuid ?: UUID.randomUUID().toString()
    val createdAtValue = DateUtils.parseApiDate(createdAt) ?: existing?.createdAt ?: timestamp
    val updatedAtValue = DateUtils.parseApiDate(updatedAt) ?: timestamp

    val base = existing ?: OfflineRoomEntity(
        roomId = existing?.roomId ?: serverId ?: -System.currentTimeMillis(),
        serverId = serverId,
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
        roomId = existing?.roomId ?: base.roomId,
        serverId = serverId ?: existing?.serverId,
        projectId = projectId,
        locationId = locationId,
        title = resolvedTitle,
        roomType = roomType?.name,
        roomTypeId = roomType?.id ?: existing?.roomTypeId,
        level = level?.name ?: level?.title ?: existing?.level,
        squareFootage = squareFootage,
        isAccessible = isAccessible ?: true,
        photoCount = photosCount ?: existing?.photoCount,
        thumbnailUrl = thumbnailUrl
            ?: thumbnail?.thumbnailUrl
            ?: thumbnail?.remoteUrl
            ?: existing?.thumbnailUrl,
        syncStatus = if (serverId != null) SyncStatus.SYNCED else existing?.syncStatus ?: SyncStatus.PENDING,
        syncVersion = (existing?.syncVersion ?: 0) + 1,
        isDirty = if (serverId != null) false else existing?.isDirty ?: true,
        isDeleted = false,
        createdAt = createdAtValue,
        updatedAt = updatedAtValue,
        lastSyncedAt = if (serverId != null) timestamp else existing?.lastSyncedAt
    )
}

internal fun RoomPhotoDto.toPhotoDto(defaultProjectId: Long, defaultRoomId: Long): PhotoDto {
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

internal fun PhotoDto.toEntity(
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

internal fun PhotoDto.serverBackedTimestamp(): String? =
    updatedAt ?: capturedAt ?: createdAt

internal fun ProjectPhotoListingDto.toPhotoDto(defaultProjectId: Long): PhotoDto {
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

internal fun AtmosphericLogDto.toEntity(defaultRoomId: Long? = roomId): OfflineAtmosphericLogEntity {
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

internal fun MoistureLogDto.toEntity(): OfflineMoistureLogEntity? {
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

internal fun OfflineMoistureLogEntity.toRequest(
    updatedAtOverride: String? = null
): MoistureLogRequest =
    MoistureLogRequest(
        reading = moistureContent,
        location = location,
        idempotencyKey = uuid,
        updatedAt = updatedAtOverride ?: updatedAt.toApiTimestamp()
    )

internal fun DamageMaterialDto.toMaterialEntity(): OfflineMaterialEntity {
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

internal fun EquipmentDto.toEntity(): OfflineEquipmentEntity {
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

internal fun OfflineEquipmentEntity.toRequest(
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

internal fun NoteDto.toEntity(): OfflineNoteEntity? {
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

internal fun DamageMaterialDto.toEntity(defaultProjectId: Long? = projectId, defaultRoomId: Long? = null): OfflineDamageEntity? {
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

internal fun WorkScopeDto.toEntity(defaultProjectId: Long? = null, defaultRoomId: Long? = null): OfflineWorkScopeEntity? {
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
internal fun MoistureLogDto.toMaterialEntity(): OfflineMaterialEntity? {
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

internal fun List<MoistureLogDto>.extractMaterials(): List<OfflineMaterialEntity> =
    mapNotNull { it.toMaterialEntity() }

internal fun AlbumDto.toEntity(defaultProjectId: Long, defaultRoomId: Long? = null): OfflineAlbumEntity {
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
