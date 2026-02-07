package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.CreateLocationRequest
import com.example.rocketplan_android.data.model.CreateRoomRequest
import com.example.rocketplan_android.data.model.UpdateRoomRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import com.example.rocketplan_android.data.repository.mapper.PendingRoomCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * Handles pushing room create/update/delete operations to the server.
 */
class RoomPushHandler(
    private val ctx: PushHandlerContext,
    private val isNetworkAvailable: () -> Boolean
) {

    suspend fun handleCreate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingRoomCreationPayload::class.java
            )
        }.getOrNull() ?: return OperationOutcome.DROP

        val project = ctx.localDataService.getProject(payload.projectId)
            ?: return OperationOutcome.SKIP
        val projectServerId = project.serverId
            ?: return OperationOutcome.SKIP

        fun normalizeServerId(value: Long?): Long? = value?.takeIf { it > 0 }

        var refreshedEssentials = false
        suspend fun refreshEssentialsOnce() {
            if (!refreshedEssentials && isNetworkAvailable()) {
                ctx.syncProjectEssentials(payload.projectId)
                refreshedEssentials = true
            }
        }

        suspend fun resolvePropertyServerId(): Long? {
            val propertyId = project.propertyId ?: return null
            return normalizeServerId(ctx.localDataService.getProperty(propertyId)?.serverId)
        }

        var propertyServerId = resolvePropertyServerId()
        if (propertyServerId == null) {
            refreshEssentialsOnce()
            propertyServerId = resolvePropertyServerId()
        }
        if (propertyServerId == null) {
            Log.w(
                SYNC_TAG,
                "⚠️ [handlePendingRoomCreation] Property not synced for project ${payload.projectId}; will retry"
            )
            return OperationOutcome.SKIP
        }

        // Resolve level and location by UUID
        var level = ctx.localDataService.getLocationByUuid(payload.levelUuid)
        var location = ctx.localDataService.getLocationByUuid(payload.locationUuid)
        var levelServerId = normalizeServerId(payload.levelServerId) ?: normalizeServerId(level?.serverId)
        var locationServerId = normalizeServerId(payload.locationServerId) ?: normalizeServerId(location?.serverId)

        Log.d(
            SYNC_TAG,
            "🔍 [handlePendingRoomCreation] Resolving IDs for room '${payload.roomName}': " +
                "levelUuid=${payload.levelUuid} → serverId=$levelServerId, " +
                "locationUuid=${payload.locationUuid} → serverId=$locationServerId"
        )

        // If IDs are missing, refresh essentials and try again
        if (levelServerId == null || locationServerId == null) {
            refreshEssentialsOnce()
            level = ctx.localDataService.getLocationByUuid(payload.levelUuid)
            location = ctx.localDataService.getLocationByUuid(payload.locationUuid)
            if (levelServerId == null) {
                levelServerId = normalizeServerId(level?.serverId)
            }
            if (locationServerId == null) {
                locationServerId = normalizeServerId(location?.serverId)
            }
        }

        // Handle Single Unit properties where level == location (no nested locations)
        // When levelUuid == locationUuid, the app is using a level as if it were a location.
        // We need to find or create a real location first, then create the room under it.
        if (levelServerId != null && payload.levelUuid == payload.locationUuid) {
            Log.d(
                SYNC_TAG,
                "🏗️ [handlePendingRoomCreation] Single-unit property detected (levelUuid == locationUuid); " +
                    "finding or creating location for property $propertyServerId"
            )
            val resolvedLocation = getOrCreateLocationForProperty(
                propertyServerId = propertyServerId,
                projectTitle = project.title,
                projectId = payload.projectId
            )
            if (resolvedLocation != null) {
                locationServerId = resolvedLocation.serverId
                location = resolvedLocation
                Log.d(
                    SYNC_TAG,
                    "✅ [handlePendingRoomCreation] Using location ${resolvedLocation.serverId} for property $propertyServerId"
                )
            } else {
                Log.w(
                    SYNC_TAG,
                    "⚠️ [handlePendingRoomCreation] Failed to get/create location for property $propertyServerId; will retry"
                )
                return OperationOutcome.SKIP
            }
        }

        if (levelServerId == null || locationServerId == null) {
            Log.w(
                SYNC_TAG,
                "⚠️ [handlePendingRoomCreation] Missing location/level for room ${payload.roomName}; will retry"
            )
            ctx.remoteLogger?.log(
                LogLevel.WARN,
                SYNC_TAG,
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

        // Skip when offline
        if (!isNetworkAvailable()) {
            Log.d(SYNC_TAG, "⏭️ [handlePendingRoomCreation] No network available; will retry later")
            return OperationOutcome.SKIP
        }

        // Verify parent project exists on server
        val projectExists = runCatching { ctx.api.getProjectDetail(projectServerId) }
            .onFailure { if (it is CancellationException) throw it }
            .isSuccess
        if (!projectExists) {
            Log.e(
                SYNC_TAG,
                "❌ [handlePendingRoomCreation] Parent project $projectServerId not found or deleted; " +
                    "dropping room creation for '${payload.roomName}'"
            )
            ctx.remoteLogger?.log(
                LogLevel.ERROR,
                SYNC_TAG,
                "Room creation failed: parent project deleted",
                mapOf(
                    "roomName" to payload.roomName,
                    "projectServerId" to projectServerId.toString(),
                    "localProjectId" to payload.projectId.toString()
                )
            )
            return OperationOutcome.DROP
        }

        val finalLevelId = levelServerId
        val finalLocationId = locationServerId

        val payloadRoomUuid = payload.roomUuid?.takeIf { it.isNotBlank() }
        val idempotencyKey = payload.idempotencyKey
            ?: payloadRoomUuid
            ?: payload.localRoomId.takeIf { it != 0L }?.toString()
        val resolvedRoomTypeId = resolveRoomTypeIdForPayload(payload)
        if (resolvedRoomTypeId == null) {
            Log.w(
                SYNC_TAG,
                "⚠️ [handlePendingRoomCreation] Unable to resolve roomTypeId for '${payload.roomName}' " +
                    "(name=${payload.roomTypeName}); will retry after room types sync"
            )
            return OperationOutcome.SKIP
        }

        // When level == location (single-level property), don't send level_id/level_uuid
        val isSingleLevel = payload.levelUuid == payload.locationUuid
        val request = CreateRoomRequest(
            name = payload.roomName,
            uuid = payloadRoomUuid,
            roomTypeId = resolvedRoomTypeId,
            levelId = if (isSingleLevel) null else finalLevelId,
            levelUuid = if (isSingleLevel) null else payload.levelUuid,
            locationUuid = payload.locationUuid,
            isSource = payload.isSource,
            isExterior = payload.isExterior,
            idempotencyKey = idempotencyKey
        )

        if (AppConfig.isLoggingEnabled) {
            Log.d(
                SYNC_TAG,
                "📤 [handlePendingRoomCreation] createRoom payload: " +
                    "locationId=$finalLocationId " +
                    "levelId=${if (isSingleLevel) "null (single-level)" else finalLevelId} " +
                    "roomTypeId=$resolvedRoomTypeId name='${payload.roomName}' " +
                    "typeName='${payload.roomTypeName}' isSource=${payload.isSource} " +
                    "idempotencyKey=${idempotencyKey ?: "null"} projectId=${payload.projectId}"
            )
        }

        val rawResponse = try {
            ctx.api.createRoom(finalLocationId, request)
        } catch (error: HttpException) {
            val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            if (AppConfig.isLoggingEnabled) {
                Log.w(
                    SYNC_TAG,
                    "❌ [handlePendingRoomCreation] createRoom failed: code=${error.code()} " +
                        "body=${errorBody ?: "null"}"
                )
            }
            // Handle 404 - location doesn't exist on server (was deleted)
            if (error.isMissingOnServer() && errorBody?.contains("Location") == true) {
                Log.w(
                    SYNC_TAG,
                    "⚠️ [handlePendingRoomCreation] Location $finalLocationId not found on server; " +
                        "marking as deleted and dropping room creation"
                )
                ctx.remoteLogger?.log(
                    LogLevel.WARN,
                    SYNC_TAG,
                    "Room creation failed: parent location deleted on server",
                    mapOf(
                        "roomName" to payload.roomName,
                        "locationServerId" to finalLocationId.toString(),
                        "projectId" to projectServerId.toString()
                    )
                )
                // Mark the stale location as deleted locally
                location?.let { loc ->
                    ctx.localDataService.markLocationsDeleted(listOf(finalLocationId))
                }
                // Also mark the pending room as deleted since it can't be created
                ctx.localDataService.getRoom(payload.localRoomId)?.let { room ->
                    val deleted = room.copy(isDeleted = true, isDirty = false, syncStatus = SyncStatus.SYNCED)
                    ctx.localDataService.saveRooms(listOf(deleted))
                }
                return OperationOutcome.DROP
            }
            if (error.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping room creation '${payload.roomName}': server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Room creation dropped - 422 validation error",
                    mapOf("roomName" to payload.roomName, "projectId" to payload.projectId.toString())
                )
                return OperationOutcome.DROP
            }
            throw error
        }

        if (AppConfig.isLoggingEnabled) {
            Log.d(SYNC_TAG, "📥 [handlePendingRoomCreation] createRoom response: $rawResponse")
        }

        val dto = when {
            rawResponse.isJsonObject && rawResponse.asJsonObject.has("data") ->
                ctx.gson.fromJson(rawResponse.asJsonObject.get("data"), RoomDto::class.java)
            rawResponse.isJsonObject && rawResponse.asJsonObject.has("room") ->
                ctx.gson.fromJson(rawResponse.asJsonObject.get("room"), RoomDto::class.java)
            else -> ctx.gson.fromJson(rawResponse, RoomDto::class.java)
        }

        if (dto.id <= 0) {
            Log.w(
                SYNC_TAG,
                "📴 [handlePendingRoomCreation] Server returned invalid room id=${dto.id} " +
                    "for ${payload.roomName}; keeping pending"
            )
            return OperationOutcome.RETRY
        }

        val preexistingServerRoom = ctx.localDataService.getRoomByServerId(dto.id)
        val existing = ctx.localDataService.getRoom(payload.localRoomId)
            ?: payloadRoomUuid?.let { uuid -> ctx.localDataService.getRoomByUuid(uuid) }
            ?: preexistingServerRoom
            ?: ctx.localDataService.getPendingRoomForProject(payload.projectId, payload.roomName)

        val resolvedRoomId = existing?.roomId
            ?: payload.localRoomId.takeIf { it != 0L }
            ?: dto.id
        val resolvedUuid = existing?.uuid?.takeIf { it.isNotBlank() }
            ?: payloadRoomUuid?.takeIf { it.isNotBlank() }
            ?: dto.uuid?.takeIf { it.isNotBlank() }
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
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveRooms(listOf(entity))

        preexistingServerRoom
            ?.takeIf { it.roomId != resolvedRoomId }
            ?.let { duplicate ->
                val cleaned = duplicate.copy(
                    isDeleted = true,
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = ctx.now()
                )
                ctx.localDataService.saveRooms(listOf(cleaned))
            }

        ctx.imageProcessorQueueManagerProvider()?.processNextQueuedAssembly()
        return OperationOutcome.SUCCESS
    }

    suspend fun handleUpdate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingRoomUpdatePayload::class.java
            )
        }.getOrNull() ?: return OperationOutcome.DROP

        val room = payload.roomUuid?.let { ctx.localDataService.getRoomByUuid(it) }
            ?: ctx.localDataService.getRoom(payload.roomId)
            ?: return OperationOutcome.DROP

        val serverId = room.serverId ?: return OperationOutcome.SKIP

        val request = UpdateRoomRequest(
            isSource = payload.isSource,
            levelId = payload.levelId,
            roomTypeId = payload.roomTypeId,
            updatedAt = (room.serverUpdatedAt ?: room.updatedAt).toApiTimestamp()
        )

        var responseDto: RoomDto? = null
        try {
            responseDto = ctx.api.updateRoom(serverId, request)
        } catch (error: Throwable) {
            if (error.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ [handlePendingRoomUpdate] 409 conflict for room $serverId; fetching fresh and retrying")
                ctx.remoteLogger?.log(
                    LogLevel.WARN,
                    SYNC_TAG,
                    "Room update 409 conflict",
                    mapOf(
                        "roomServerId" to serverId.toString(),
                        "roomUuid" to room.uuid,
                        "lockUpdatedAt" to (payload.lockUpdatedAt ?: "null"),
                        "usedServerTimestamp" to (room.serverUpdatedAt != null).toString(),
                        "localUpdatedAt" to room.updatedAt.time.toString(),
                        "serverUpdatedAt" to (room.serverUpdatedAt?.time?.toString() ?: "null")
                    )
                )
                // Fetch fresh room data from server
                val freshRoom = runCatching {
                    ctx.api.getRoomDetail(serverId)
                }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrElse { fetchError ->
                        Log.e(SYNC_TAG, "❌ [handlePendingRoomUpdate] Failed to fetch fresh room $serverId; will retry later", fetchError)
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Room update 409 recovery deferred - fresh fetch failed",
                            mapOf("roomServerId" to serverId.toString(), "error" to (fetchError.message ?: "unknown"))
                        )
                        return OperationOutcome.SKIP
                    }

                // Retry with fresh updatedAt
                val retryRequest = UpdateRoomRequest(
                    isSource = payload.isSource,
                    levelId = payload.levelId,
                    roomTypeId = payload.roomTypeId,
                    updatedAt = freshRoom.updatedAt
                )
                val retryResult = runCatching { ctx.api.updateRoom(serverId, retryRequest) }
                    .onFailure { if (it is CancellationException) throw it }
                retryResult.onFailure { retryError ->
                    if (retryError.isConflict()) {
                        Log.w(
                            SYNC_TAG,
                            "⚠️ [handlePendingRoomUpdate] Retry still got 409; recording conflict for user resolution"
                        )
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Room update double-409 - recording conflict",
                            mapOf("roomServerId" to serverId.toString(), "roomUuid" to room.uuid)
                        )
                        // Record conflict for user resolution instead of silent server restore
                        // Note: Server doesn't return is_source in room detail response, only is_accessible.
                        // We omit isSource from remote snapshot since we can't accurately represent it.
                        val conflict = OfflineConflictResolutionEntity(
                            conflictId = UuidUtils.generateUuidV7(),
                            entityType = "room",
                            entityId = room.roomId,
                            entityUuid = room.uuid,
                            localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "title" to room.title,
                                "roomTypeId" to room.roomTypeId,
                                "isSource" to payload.isSource,
                                "levelId" to payload.levelId
                            )).toByteArray(Charsets.UTF_8),
                            remoteVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "title" to (freshRoom.name ?: freshRoom.title),
                                "roomTypeId" to freshRoom.roomType?.id,
                                "levelId" to freshRoom.level?.id
                                // isSource omitted - server doesn't return this field
                            )).toByteArray(Charsets.UTF_8),
                            conflictType = "UPDATE_CONFLICT",
                            detectedAt = ctx.now(),
                            originalOperationId = operation.operationId
                        )
                        ctx.recordConflict(conflict)
                        return OperationOutcome.CONFLICT_PENDING
                    }
                    if (retryError.isValidationError()) {
                        Log.w(SYNC_TAG, "Dropping room update $serverId: server validation error (422)")
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Room update dropped - 422 validation error",
                            mapOf("roomServerId" to serverId.toString(), "roomUuid" to room.uuid)
                        )
                        return OperationOutcome.DROP
                    }
                    throw retryError
                }
                responseDto = retryResult.getOrNull()
                Log.d(SYNC_TAG, "✅ [handlePendingRoomUpdate] Retry update succeeded for room $serverId")
            } else if (error.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping room update $serverId: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Room update dropped - 422 validation error",
                    mapOf("roomServerId" to serverId.toString(), "roomUuid" to room.uuid)
                )
                return OperationOutcome.DROP
            } else {
                throw error
            }
        }

        val freshServerUpdatedAt = responseDto?.updatedAt?.let { DateUtils.parseApiDate(it) }
        val synced = room.copy(
            serverUpdatedAt = freshServerUpdatedAt ?: room.serverUpdatedAt,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveRooms(listOf(synced))
        return OperationOutcome.SUCCESS
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val room = ctx.localDataService.getRoom(operation.entityId)
            ?: return OperationOutcome.DROP
        val serverId = room.serverId
            ?: return OperationOutcome.SUCCESS
        val lockUpdatedAt = (room.serverUpdatedAt ?: room.updatedAt).toApiTimestamp()
        try {
            ctx.api.deleteRoom(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        } catch (error: Throwable) {
            if (error.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping room delete $serverId: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Room delete dropped - 422 validation error",
                    mapOf("roomServerId" to serverId.toString(), "roomUuid" to room.uuid)
                )
                return OperationOutcome.DROP
            }
            if (!error.isMissingOnServer()) {
                throw error
            }
        }
        val cleaned = room.copy(
            isDeleted = true,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveRooms(listOf(cleaned))
        // Clear tombstone now that server confirmed deletion
        serverId.let { DeletionTombstoneCache.clearTombstone("room", it) }
        return OperationOutcome.SUCCESS
    }

    private suspend fun resolveRoomTypeIdForPayload(payload: PendingRoomCreationPayload): Long? {
        val project = ctx.localDataService.getProject(payload.projectId)
            ?: return payload.roomTypeId.takeIf { it > 0 }
        val propertyId = project.propertyId
            ?: return payload.roomTypeId.takeIf { it > 0 }
        val property = ctx.localDataService.getProperty(propertyId)
            ?: return payload.roomTypeId.takeIf { it > 0 }
        val propertyServerId = property.serverId ?: return null

        val roomTypes = runCatching {
            ctx.api.getPropertyRoomTypes(propertyServerId, filterType = null).data
        }
            .onFailure { error ->
                Log.w(
                    SYNC_TAG,
                    "⚠️ [resolveRoomTypeId] Failed to fetch property room types " +
                        "for property=$propertyServerId",
                    error
                )
            }
            .getOrElse { return null }

        val match = pickPropertyRoomTypeMatch(payload, roomTypes)
        if (match == null) {
            Log.w(
                SYNC_TAG,
                "⚠️ [resolveRoomTypeId] No property room type match for " +
                    "roomTypeId=${payload.roomTypeId} name=${payload.roomTypeName} " +
                    "(property=$propertyServerId); falling back to payload id"
            )
            return payload.roomTypeId.takeIf { it > 0 }
        }

        Log.d(
            SYNC_TAG,
            "✅ [resolveRoomTypeId] Resolved room type '${payload.roomTypeName}' " +
                "to property id=${match.id} for property=$propertyServerId"
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

    /**
     * Gets an existing location for a property, or creates one if none exists.
     * This mirrors the iOS approach in ThisPropertyIsViewModel.createLocation().
     *
     * For single-unit properties, the server expects rooms to be created under a location,
     * not directly under a level. This function ensures a location exists before room creation.
     *
     * @param propertyServerId The server ID of the property
     * @param projectTitle The project title - used for naming if creating (matches iOS behavior)
     * @param projectId The local project ID for saving the location
     * @return The existing or newly created location entity with server ID, or null if failed
     */
    private suspend fun getOrCreateLocationForProperty(
        propertyServerId: Long,
        projectTitle: String,
        projectId: Long
    ): OfflineLocationEntity? {
        // First, check if locations already exist for this property (like iOS does)
        // iOS fails safe here - if we can't check, we don't create (to avoid duplicates)
        val locationsResult = runCatching {
            ctx.api.getPropertyLocations(propertyServerId)
        }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Log.w(
                    SYNC_TAG,
                    "⚠️ [getOrCreateLocationForProperty] Failed to fetch existing locations for property $propertyServerId; " +
                        "failing safe to avoid duplicates (will retry)",
                    error
                )
            }

        // Fail safe: if we couldn't check for existing locations, don't create (matches iOS behavior)
        if (locationsResult.isFailure) {
            return null
        }

        val existingLocations = locationsResult.getOrNull()?.data

        // If a location exists, use it (save locally and return)
        if (!existingLocations.isNullOrEmpty()) {
            val existingDto = existingLocations.first()
            Log.d(
                SYNC_TAG,
                "✅ [getOrCreateLocationForProperty] Found existing location ${existingDto.id} for property $propertyServerId"
            )
            val entity = existingDto.toEntity(defaultProjectId = projectId).copy(
                syncStatus = SyncStatus.SYNCED,
                isDirty = false,
                lastSyncedAt = ctx.now()
            )
            ctx.localDataService.saveLocations(listOf(entity))
            return entity
        }

        // No existing locations - create a new one
        Log.d(
            SYNC_TAG,
            "✨ [getOrCreateLocationForProperty] No existing locations found - creating new for property $propertyServerId"
        )

        // Use project title for location name (matches iOS behavior in createNewLocation)
        val locationName = projectTitle.takeIf { it.isNotBlank() } ?: "Unit"
        val locationUuid = UuidUtils.generateUuidV7()

        val request = CreateLocationRequest(
            name = locationName,
            uuid = locationUuid,
            floorNumber = 1,
            locationTypeId = 1, // Default to "unit" type
            isCommon = true,
            isAccessible = true,
            isCommercial = false,
            idempotencyKey = locationUuid
        )

        val dto = runCatching { ctx.api.createLocation(propertyServerId, request) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Log.e(
                    SYNC_TAG,
                    "❌ [getOrCreateLocationForProperty] Failed to create location for property $propertyServerId",
                    error
                )
            }
            .getOrNull() ?: return null

        // Save the created location locally
        val entity = dto.toEntity(defaultProjectId = projectId).copy(
            uuid = locationUuid,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveLocations(listOf(entity))

        return entity
    }
}
