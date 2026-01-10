package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.CreateLocationRequest
import com.example.rocketplan_android.data.model.UpdateLocationRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLocationCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingLocationUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
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

        val dto = ctx.api.createLocation(propertyServerId, request)
        val existing = ctx.localDataService.getLocations(payload.projectId)
            .firstOrNull { it.uuid == payload.locationUuid || it.locationId == payload.localLocationId }
        val entity = dto.toEntity(defaultProjectId = payload.projectId).copy(
            locationId = existing?.locationId ?: dto.id,
            uuid = existing?.uuid ?: dto.uuid ?: UuidUtils.generateUuidV7(),
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
            updatedAt = payload.lockUpdatedAt
        )

        try {
            ctx.api.updateLocation(serverId, request)
        } catch (error: Throwable) {
            if (error.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ [handlePendingLocationUpdate] Conflict for location $serverId, will retry")
                return OperationOutcome.SKIP
            }
            throw error
        }

        val synced = location.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveLocations(listOf(synced))
        return OperationOutcome.SUCCESS
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
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: location.updatedAt.toApiTimestamp()
        try {
            ctx.api.deleteLocation(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        } catch (error: Throwable) {
            if (!error.isMissingOnServer()) {
                throw error
            }
        }
        cascadeDeleteLocation(location)
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

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
