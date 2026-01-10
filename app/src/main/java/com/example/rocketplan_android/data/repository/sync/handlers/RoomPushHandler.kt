package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.CreateRoomRequest
import com.example.rocketplan_android.data.model.UpdateRoomRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingRoomUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
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

        // Handle Single Unit properties where level == location
        if (levelServerId != null && locationServerId == null && payload.levelUuid == payload.locationUuid) {
            locationServerId = levelServerId
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
            if (AppConfig.isLoggingEnabled) {
                val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                Log.w(
                    SYNC_TAG,
                    "❌ [handlePendingRoomCreation] createRoom failed: code=${error.code()} " +
                        "body=${errorBody ?: "null"}"
                )
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
            updatedAt = payload.lockUpdatedAt
        )

        try {
            ctx.api.updateRoom(serverId, request)
        } catch (error: Throwable) {
            if (error.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ [handlePendingRoomUpdate] Conflict for room $serverId, will retry")
                return OperationOutcome.SKIP
            }
            throw error
        }

        val synced = room.copy(
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
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: room.updatedAt.toApiTimestamp()
        try {
            ctx.api.deleteRoom(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        } catch (error: Throwable) {
            if (!error.isMissingOnServer()) {
                throw error
            }
        }
        val cleaned = room.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveRooms(listOf(cleaned))
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

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
