package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyCreationPayload
import com.example.rocketplan_android.data.repository.mapper.PendingPropertyUpdatePayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.DateUtils
import retrofit2.HttpException

/**
 * Handles pushing property create/update/delete operations to the server.
 */
class PropertyPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleCreate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingPropertyCreationPayload::class.java
            )
        }.getOrNull() ?: return OperationOutcome.DROP

        val project = ctx.localDataService.getProject(payload.projectId)
            ?: return OperationOutcome.SKIP
        val projectServerId = project.serverId
            ?: return OperationOutcome.SKIP

        val request = PropertyMutationRequest(
            uuid = payload.propertyUuid,
            propertyTypeId = payload.propertyTypeId,
            projectUuid = project.uuid,
            idempotencyKey = payload.idempotencyKey
        )

        Log.d(
            SYNC_TAG,
            "📤 [handlePendingPropertyCreation] createProperty request: " +
                "projectServerId=$projectServerId localProjectId=${payload.projectId} " +
                "propertyTypeId=${payload.propertyTypeId} propertyTypeValue=${payload.propertyTypeValue ?: "null"} " +
                "idempotencyKey=${payload.idempotencyKey ?: "null"} localPropertyId=${payload.localPropertyId}"
        )

        val created = try {
            ctx.api.createProjectProperty(projectServerId, request).data
        } catch (error: HttpException) {
            val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            Log.w(
                SYNC_TAG,
                "❌ [handlePendingPropertyCreation] createProperty failed: code=${error.code()} " +
                    "body=${errorBody ?: "null"}"
            )
            throw error
        }

        Log.d(
            SYNC_TAG,
            "📥 [handlePendingPropertyCreation] createProperty response: id=${created.id} " +
                "uuid=${created.uuid} address=${created.address} city=${created.city} " +
                "state=${created.state} zip=${created.postalCode} " +
                "propertyTypeId=${created.propertyTypeId} propertyType=${created.propertyType} " +
                "createdAt=${created.createdAt} updatedAt=${created.updatedAt}"
        )

        // Validate the property was actually created recently (within last 5 minutes)
        val createdAtDate = created.createdAt?.let { DateUtils.parseApiDate(it) }
        val nowMillis = System.currentTimeMillis()
        val ageMinutes = createdAtDate?.let { (nowMillis - it.time) / 60_000 }
        if (ageMinutes != null && ageMinutes > 5) {
            Log.e(
                SYNC_TAG,
                "🚨 [handlePendingPropertyCreation] STALE PROPERTY DETECTED: " +
                    "Property ${created.id} was created ${ageMinutes}min ago (createdAt=${created.createdAt}). " +
                    "Server may have returned cached/wrong property data. Fetching fresh data."
            )
            ctx.remoteLogger?.log(
                LogLevel.ERROR,
                SYNC_TAG,
                "Stale property detected during creation",
                mapOf(
                    "propertyId" to created.id.toString(),
                    "createdAt" to (created.createdAt ?: "null"),
                    "ageMinutes" to ageMinutes.toString(),
                    "projectServerId" to projectServerId.toString()
                )
            )
        }

        // Always fetch fresh property data to ensure we have the latest state
        val refreshed = runCatching { ctx.api.getProperty(created.id).data }
            .onFailure {
                Log.w(
                    SYNC_TAG,
                    "⚠️ [handlePendingPropertyCreation] getProperty failed for id=${created.id}",
                    it
                )
            }
            .getOrNull()
        val resolved = refreshed ?: created
        if (AppConfig.isLoggingEnabled) {
            val source = if (refreshed != null) "getProperty" else "createResponse"
            Log.d(
                SYNC_TAG,
                "📥 [handlePendingPropertyCreation] property resolved from $source: id=${resolved.id} " +
                    "address=${resolved.address} city=${resolved.city} state=${resolved.state} " +
                    "zip=${resolved.postalCode} propertyTypeId=${resolved.propertyTypeId} " +
                    "propertyType=${resolved.propertyType}"
            )
        }
        val existing = ctx.localDataService.getProperty(payload.localPropertyId)
        ctx.persistProperty(payload.projectId, resolved, payload.propertyTypeValue, existing, true)

        // Sync local level serverIds after property creation
        runCatching {
            syncLocalLevelServerIds(payload.projectId, resolved.id)
        }.onFailure {
            Log.w(SYNC_TAG, "⚠️ [handlePendingPropertyCreation] Failed to sync level serverIds", it)
        }

        return OperationOutcome.SUCCESS
    }

    suspend fun handleUpdate(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = runCatching {
            ctx.gson.fromJson(
                String(operation.payload, Charsets.UTF_8),
                PendingPropertyUpdatePayload::class.java
            )
        }.getOrNull() ?: return OperationOutcome.DROP

        val property = ctx.localDataService.getProperty(payload.propertyId)
            ?: return OperationOutcome.DROP
        val serverId = property.serverId
            ?: return OperationOutcome.SKIP
        val lockUpdatedAt = payload.lockUpdatedAt ?: property.updatedAt.toApiTimestamp()
        val request = payload.request.copy(
            updatedAt = lockUpdatedAt,
            idempotencyKey = null
        )
        if (AppConfig.isLoggingEnabled) {
            Log.d(
                SYNC_TAG,
                "📤 [handlePendingPropertyUpdate] updateProperty payload: " +
                    "projectId=${payload.projectId} propertyId=${payload.propertyId} serverId=$serverId " +
                    "propertyTypeId=${request.propertyTypeId} name=${request.name ?: "null"} " +
                    "lockUpdatedAt=$lockUpdatedAt"
            )
        }

        val updated = try {
            ctx.api.updateProperty(serverId, request).data
        } catch (error: HttpException) {
            if (AppConfig.isLoggingEnabled) {
                val errorBody = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
                Log.w(
                    SYNC_TAG,
                    "❌ [handlePendingPropertyUpdate] updateProperty failed: code=${error.code()} " +
                        "body=${errorBody ?: "null"}"
                )
            }
            throw error
        }

        if (AppConfig.isLoggingEnabled) {
            Log.d(
                SYNC_TAG,
                "📥 [handlePendingPropertyUpdate] updateProperty response: id=${updated.id} " +
                    "address=${updated.address} city=${updated.city} state=${updated.state} " +
                    "zip=${updated.postalCode} propertyTypeId=${updated.propertyTypeId} " +
                    "propertyType=${updated.propertyType}"
            )
        }

        val refreshed = runCatching { ctx.api.getProperty(updated.id).data }
            .onFailure {
                Log.w(
                    SYNC_TAG,
                    "⚠️ [handlePendingPropertyUpdate] getProperty failed for id=${updated.id}",
                    it
                )
            }
            .getOrNull()
        val resolved = refreshed ?: updated
        if (AppConfig.isLoggingEnabled) {
            val source = if (refreshed != null) "getProperty" else "updateResponse"
            Log.d(
                SYNC_TAG,
                "📥 [handlePendingPropertyUpdate] property resolved from $source: id=${resolved.id} " +
                    "address=${resolved.address} city=${resolved.city} state=${resolved.state} " +
                    "zip=${resolved.postalCode} propertyTypeId=${resolved.propertyTypeId} " +
                    "propertyType=${resolved.propertyType}"
            )
        }

        ctx.persistProperty(payload.projectId, resolved, payload.propertyTypeValue, property, false)
        return OperationOutcome.SUCCESS
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val property = ctx.localDataService.getProperty(operation.entityId)
            ?: return OperationOutcome.DROP
        val serverId = property.serverId
        if (serverId == null) {
            // Never reached server; cascade delete locally
            cascadeDeleteProperty(property.propertyId)
            return OperationOutcome.SUCCESS
        }
        val lockUpdatedAt = extractLockUpdatedAt(operation.payload)
            ?: property.updatedAt.toApiTimestamp()
        try {
            ctx.api.deleteProperty(serverId, DeleteWithTimestampRequest(updatedAt = lockUpdatedAt))
        } catch (error: Throwable) {
            if (!error.isMissingOnServer()) {
                throw error
            }
        }
        cascadeDeleteProperty(property.propertyId)
        return OperationOutcome.SUCCESS
    }

    /**
     * Syncs local pending levels/locations with their server counterparts by name-matching.
     * Called after property creation to populate serverId on local level entities.
     */
    private suspend fun syncLocalLevelServerIds(projectId: Long, propertyServerId: Long) {
        val localLocations = ctx.localDataService.getLocations(projectId)
        val pendingLocations = localLocations.filter { it.serverId == null }
        if (AppConfig.isLoggingEnabled) {
            Log.d(
                SYNC_TAG,
                "🔍 [syncLocalLevelServerIds] projectId=$projectId propertyServerId=$propertyServerId " +
                    "localLocations=${localLocations.size} pendingLocations=${pendingLocations.size}"
            )
        }
        if (pendingLocations.isEmpty()) return

        val remoteLevels = runCatching { ctx.api.getPropertyLevels(propertyServerId).data }.getOrNull()
        val remoteLocations = runCatching { ctx.api.getPropertyLocations(propertyServerId).data }.getOrNull()
        if (remoteLevels == null && remoteLocations == null) return

        var updatedCount = 0
        for (pending in pendingLocations) {
            val pendingName = pending.title?.takeIf { it.isNotBlank() }
                ?: pending.type?.takeIf { it.isNotBlank() }
                ?: continue

            // Try levels first, then locations
            val matchedServerId = remoteLevels?.firstOrNull { level ->
                val remoteName = listOfNotNull(
                    level.title?.takeIf { it.isNotBlank() },
                    level.name?.takeIf { it.isNotBlank() }
                ).firstOrNull()
                remoteName?.equals(pendingName, ignoreCase = true) == true
            }?.id ?: remoteLocations?.firstOrNull { location ->
                val remoteName = listOfNotNull(
                    location.title?.takeIf { it.isNotBlank() },
                    location.name?.takeIf { it.isNotBlank() }
                ).firstOrNull()
                remoteName?.equals(pendingName, ignoreCase = true) == true
            }?.id ?: continue

            val updated = pending.copy(
                serverId = matchedServerId,
                syncStatus = SyncStatus.SYNCED,
                isDirty = false,
                lastSyncedAt = ctx.now()
            )
            ctx.localDataService.saveLocations(listOf(updated))
            updatedCount++
        }

        if (AppConfig.isLoggingEnabled && updatedCount > 0) {
            Log.d(SYNC_TAG, "✅ [syncLocalLevelServerIds] Updated $updatedCount local levels with serverIds")
        }
    }

    private suspend fun cascadeDeleteProperty(propertyId: Long) {
        ctx.remoteLogger?.log(
            LogLevel.INFO,
            SYNC_TAG,
            "Cascade deleting property",
            mapOf("propertyId" to propertyId.toString())
        )
        ctx.localDataService.clearProjectPropertyId(propertyId)
        ctx.localDataService.deleteProperty(propertyId)
    }

    private fun extractLockUpdatedAt(payload: ByteArray): String? =
        runCatching {
            ctx.gson.fromJson(
                String(payload, Charsets.UTF_8),
                PendingLockPayload::class.java
            ).lockUpdatedAt
        }.getOrNull()
}
