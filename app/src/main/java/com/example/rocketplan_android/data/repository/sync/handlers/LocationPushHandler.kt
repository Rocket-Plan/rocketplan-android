package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.CreateLocationRequest
import com.example.rocketplan_android.data.model.UpdateLocationRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLocationCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingLocationUpdatePayload
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Handles pushing location create/update/delete operations to the server.
 */
class LocationPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleCreate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingLocationCreationPayload::class.java
            )
        }.getOrNull() ?: return OperationOutcome.DROP

        // Try to find property by the stored local ID first
        var property = ctx.localDataService.getProperty(payload.propertyLocalId)

        // If property not found, the pending property may have been replaced with a synced one
        if (property == null) {
            val project = ctx.localDataService.getProject(payload.projectId)
            if (project == null || project.isDeleted) {
                Log.d(
                    SYNC_TAG,
                    "🗑️ [handlePendingLocationCreation] Project ${payload.projectId} not found or deleted; " +
                        "dropping operation"
                )
                return OperationOutcome.DROP
            }
            val currentPropertyId = project.propertyId
            if (currentPropertyId == null) {
                // Project exists but has no property yet - wait for property creation
                return OperationOutcome.SKIP
            }
            property = ctx.localDataService.getProperty(currentPropertyId)
            if (property == null) {
                Log.w(
                    SYNC_TAG,
                    "⚠️ [handlePendingLocationCreation] Project ${payload.projectId} has " +
                        "propertyId=$currentPropertyId but property not found"
                )
                return OperationOutcome.SKIP
            }
            Log.d(
                SYNC_TAG,
                "🔄 [handlePendingLocationCreation] Property ${payload.propertyLocalId} not found; " +
                    "using project's current property ${property.propertyId}"
            )
        }

        val propertyServerId = property.serverId
            ?: return OperationOutcome.SKIP

        // Check if location already synced (by UUID or local ID)
        val pendingLocation = ctx.localDataService.getLocationByUuid(payload.locationUuid)
            ?: ctx.localDataService.getLocations(payload.projectId)
                .firstOrNull { it.locationId == payload.localLocationId }
        if (pendingLocation?.serverId != null) {
            Log.d(SYNC_TAG, "✅ [handlePendingLocationCreation] Location ${payload.locationUuid} already synced")
            return OperationOutcome.SUCCESS
        }

        val request = CreateLocationRequest(
            name = payload.locationName,
            uuid = payload.locationUuid,
            floorNumber = payload.floorNumber,
            locationTypeId = payload.locationTypeId,
            isCommon = payload.isCommon,
            isAccessible = payload.isAccessible,
            isCommercial = payload.isCommercial,
            idempotencyKey = payload.idempotencyKey
        )

        val dto = try {
            ctx.api.createLocation(propertyServerId, request)
        } catch (e: Exception) {
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping location creation '${payload.locationName}': server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Location creation dropped - 422 validation error",
                    mapOf("locationName" to payload.locationName, "propertyServerId" to propertyServerId.toString())
                )
                return OperationOutcome.DROP
            }
            throw e
        }
        val existing = ctx.localDataService.getLocations(payload.projectId)
            .firstOrNull { it.uuid == payload.locationUuid || it.locationId == payload.localLocationId }
        val entity = dto.toEntity(defaultProjectId = payload.projectId).copy(
            locationId = existing?.locationId ?: dto.id,
            uuid = existing?.uuid?.takeIf { it.isNotBlank() }
                ?: dto.uuid?.takeIf { it.isNotBlank() }
                ?: UuidUtils.generateUuidV7(),
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveLocations(listOf(entity))
        return OperationOutcome.SUCCESS
    }

    suspend fun handleUpdate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingLocationUpdatePayload::class.java
            )
        }.getOrNull() ?: return OperationOutcome.DROP

        val location = ctx.localDataService.getLocationByUuid(payload.locationUuid)
            ?: ctx.localDataService.getLocation(payload.locationId)
            ?: return OperationOutcome.DROP

        val serverId = location.serverId ?: return OperationOutcome.SKIP

        val request = UpdateLocationRequest(
            name = payload.name,
            floorNumber = payload.floorNumber,
            isAccessible = payload.isAccessible,
            updatedAt = (location.serverUpdatedAt ?: location.updatedAt).toApiTimestamp()
        )

        var responseDto: com.example.rocketplan_android.data.model.offline.LocationDto? = null
        try {
            responseDto = ctx.api.updateLocation(serverId, request)
        } catch (error: Throwable) {
            if (error.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ [handlePendingLocationUpdate] 409 conflict for location $serverId; fetching fresh and retrying")
                ctx.remoteLogger?.log(
                    LogLevel.WARN,
                    SYNC_TAG,
                    "Location update 409 conflict",
                    mapOf(
                        "locationServerId" to serverId.toString(),
                        "locationUuid" to location.uuid,
                        "lockUpdatedAt" to (payload.lockUpdatedAt ?: "null"),
                        "usedServerTimestamp" to (location.serverUpdatedAt != null).toString(),
                        "localUpdatedAt" to (location.updatedAt.time.toString()),
                        "serverUpdatedAt" to (location.serverUpdatedAt?.time?.toString() ?: "null")
                    )
                )
                // Fetch fresh location data from server
                val freshLocation = fetchFreshLocation(location, serverId)
                if (freshLocation == null) {
                    Log.e(SYNC_TAG, "❌ [handlePendingLocationUpdate] Failed to fetch fresh location $serverId; will retry later")
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Location update 409 recovery deferred - fresh fetch failed",
                        mapOf("locationServerId" to serverId.toString())
                    )
                    return OperationOutcome.SKIP
                }

                // Retry with fresh updatedAt
                val retryRequest = UpdateLocationRequest(
                    name = payload.name,
                    floorNumber = payload.floorNumber,
                    isAccessible = payload.isAccessible,
                    updatedAt = freshLocation.updatedAt
                )
                val retryResult = runCatching { ctx.api.updateLocation(serverId, retryRequest) }
                    .onFailure { if (it is CancellationException) throw it }
                retryResult.onFailure { retryError ->
                    if (retryError.isConflict()) {
                        Log.w(
                            SYNC_TAG,
                            "⚠️ [handlePendingLocationUpdate] Retry still got 409; recording conflict for user resolution"
                        )
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Location update double-409 - recording conflict",
                            mapOf("locationServerId" to serverId.toString(), "locationUuid" to location.uuid)
                        )
                        // Record conflict for user resolution instead of silent server restore
                        // Note: floorNumber is included in local version from payload but server DTO
                        // doesn't return it, so we can only show what the user tried to set
                        val conflict = OfflineConflictResolutionEntity(
                            conflictId = UuidUtils.generateUuidV7(),
                            entityType = "location",
                            entityId = location.locationId,
                            entityUuid = location.uuid,
                            localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "name" to payload.name,
                                "title" to location.title,
                                "floorNumber" to payload.floorNumber,
                                "isAccessible" to payload.isAccessible
                            )).toByteArray(Charsets.UTF_8),
                            remoteVersion = ctx.gson.toJson(mapOf<String, Any?>(
                                "name" to freshLocation.name,
                                "title" to freshLocation.title,
                                "isAccessible" to freshLocation.isAccessible
                            )).toByteArray(Charsets.UTF_8),
                            conflictType = "UPDATE_CONFLICT",
                            detectedAt = ctx.now(),
                            originalOperationId = operation.operationId
                        )
                        ctx.recordConflict(conflict)
                        return OperationOutcome.CONFLICT_PENDING
                    }
                    if (retryError.isValidationError()) {
                        Log.w(SYNC_TAG, "Dropping location update $serverId: server validation error (422)")
                        ctx.remoteLogger?.log(
                            LogLevel.WARN, SYNC_TAG, "Location update dropped - 422 validation error",
                            mapOf("locationServerId" to serverId.toString(), "locationUuid" to location.uuid)
                        )
                        return OperationOutcome.DROP
                    }
                    throw retryError
                }
                responseDto = retryResult.getOrNull()
                Log.d(SYNC_TAG, "✅ [handlePendingLocationUpdate] Retry update succeeded for location $serverId")
            } else if (error.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping location update $serverId: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Location update dropped - 422 validation error",
                    mapOf("locationServerId" to serverId.toString(), "locationUuid" to location.uuid)
                )
                return OperationOutcome.DROP
            } else {
                throw error
            }
        }

        val freshServerUpdatedAt = responseDto?.updatedAt?.let { DateUtils.parseApiDate(it) }
        val synced = location.copy(
            serverUpdatedAt = freshServerUpdatedAt ?: location.serverUpdatedAt,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveLocations(listOf(synced))
        return OperationOutcome.SUCCESS
    }

    /**
     * Fetches fresh location data from server via property locations endpoint.
     * Returns null if unable to fetch (project/property not found, or API error).
     */
    private suspend fun fetchFreshLocation(
        location: OfflineLocationEntity,
        serverId: Long
    ): com.example.rocketplan_android.data.model.offline.LocationDto? {
        // Get property server ID via project → property chain
        val project = ctx.localDataService.getProject(location.projectId) ?: return null
        val propertyId = project.propertyId ?: return null
        val property = ctx.localDataService.getProperty(propertyId) ?: return null
        val propertyServerId = property.serverId ?: return null

        return runCatching {
            ctx.api.getPropertyLocations(propertyServerId).data
                .firstOrNull { it.id == serverId }
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val location = ctx.localDataService.getLocation(operation.entityId)
            ?: return OperationOutcome.DROP
        val serverId = location.serverId
        if (serverId == null) {
            // Never reached server; cascade delete locally
            cascadeDeleteLocation(location)
            return OperationOutcome.SUCCESS
        }
        val lockUpdatedAt = (location.serverUpdatedAt ?: location.updatedAt).toApiTimestamp()
        try {
            ctx.api.deleteLocation(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        } catch (error: Throwable) {
            if (error.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping location delete $serverId: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Location delete dropped - 422 validation error",
                    mapOf("locationServerId" to serverId.toString(), "locationUuid" to location.uuid)
                )
                return OperationOutcome.DROP
            }
            if (!error.isMissingOnServer()) {
                throw error
            }
        }
        cascadeDeleteLocation(location)
        // Clear tombstone now that server confirmed deletion
        DeletionTombstoneCache.clearTombstone("location", serverId)
        return OperationOutcome.SUCCESS
    }

    private suspend fun cascadeDeleteLocation(location: OfflineLocationEntity) {
        ctx.remoteLogger?.log(
            LogLevel.INFO,
            SYNC_TAG,
            "Cascade deleting location",
            mapOf(
                "locationId" to location.locationId.toString(),
                "locationUuid" to location.uuid
            )
        )
        // Full cascade: delete room children, clear sync ops, mark rooms deleted
        val photosToCleanup = ctx.localDataService.cascadeDeleteRoomsByLocation(location.locationId)
        if (photosToCleanup.isNotEmpty()) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Cleaning up cached photos from cascade delete",
                mapOf("photo_count" to photosToCleanup.size.toString())
            )
        }
        // Clean up cached photo files
        photosToCleanup.forEach { photo ->
            photo.cachedOriginalPath?.let { runCatching { File(it).delete() } }
            photo.cachedThumbnailPath?.let { runCatching { File(it).delete() } }
        }
        // Mark location as deleted and synced
        val cleaned = location.copy(
            isDirty = false,
            isDeleted = true,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveLocations(listOf(cleaned))
    }
}
