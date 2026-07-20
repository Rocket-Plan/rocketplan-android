package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.feature.SerializedEquipmentMode
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toRegisterRequest
import com.example.rocketplan_android.data.repository.mapper.toUpdateRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.util.UuidUtils
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

/**
 * RP-FR-019 — pushes serialized-asset register/update/retire operations.
 *
 * `companyId` on the asset is the SERVER company id (companies are
 * server-authoritative, never created offline), so it is used directly for the
 * register URL — no SKIP-until-ready needed for the parent company.
 */
class EquipmentAssetPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val asset = ctx.localDataService.getEquipmentAssetByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (asset.isDeleted) return OperationOutcome.DROP
        gateOrSkip(asset.companyId)?.let { return it }

        val lockUpdatedAt = DateUtils.formatApiDate(asset.serverUpdatedAt ?: asset.updatedAt)
        return try {
            val dto = if (asset.serverId == null) {
                ctx.api.registerEquipmentAsset(asset.companyId, asset.toRegisterRequest()).data
            } else {
                ctx.api.updateEquipmentAsset(asset.serverId, asset.toUpdateRequest(lockUpdatedAt)).data
            }
            ctx.localDataService.saveEquipmentAssets(listOf(dto.toEntity(asset)))
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when {
                e.isConflict() && asset.serverId != null ->
                    handle409Conflict(e as HttpException, asset, operation)
                e.isValidationError() -> resolve422(asset, e)
                asset.serverId != null && e.isMissingOnServer() -> reconcileMissing(asset)
                else -> {
                    Log.w(SYNC_TAG, "EquipmentAssetPushHandler unknown error; retrying", e)
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Equipment asset upsert retry (unexpected error)",
                        mapOf("assetUuid" to asset.uuid,
                              "serverId" to (asset.serverId?.toString() ?: "null"),
                              "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
                    )
                    OperationOutcome.RETRY
                }
            }
        }
    }

    /**
     * RP-CD-019: on a terminal 422, drain the error body for diagnosis and resolve
     * the local row (→ FAILED, clean) so it is not stranded PENDING with no queue op.
     */
    private suspend fun resolve422(asset: OfflineEquipmentAssetEntity, error: Throwable): OperationOutcome {
        val body = (error as? HttpException)?.response()?.errorBody()?.string()
        Log.w(SYNC_TAG, "Dropping equipment asset ${asset.uuid}: 422 - $body")
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Equipment asset dropped - 422 validation error",
            mapOf(
                "assetUuid" to asset.uuid,
                "serverId" to (asset.serverId?.toString() ?: "null"),
                "body" to (body ?: "")
            )
        )
        val now = ctx.now()
        ctx.localDataService.saveEquipmentAssets(
            listOf(asset.copy(isDirty = false, syncStatus = SyncStatus.FAILED, lastSyncedAt = now))
        )
        // Review round-9 (H4): if REGISTER failed (asset never got a serverId), its optimistic
        // placement ops can never sync (they SKIP forever waiting for asset.serverId). Collapse the
        // whole unsynced graph — drop placement ops + soft-delete the placements.
        if (asset.serverId == null) {
            val placements = ctx.localDataService.getAllPlacementsForAsset(asset.assetId)
            placements.forEach {
                ctx.localDataService.removeSyncOperationsForEntity("equipment_asset_placement", it.placementId)
            }
            if (placements.isNotEmpty()) {
                ctx.localDataService.saveEquipmentPlacements(
                    placements.map {
                        it.copy(isDeleted = true, isOpen = false, isDirty = false, syncStatus = SyncStatus.FAILED, lastSyncedAt = now)
                    }
                )
            }
        }
        return OperationOutcome.DROP
    }

    /**
     * Review #4: a 404/410 on an existing serverId means the asset was retired/removed
     * by another client — reconcile it as deleted locally. Do NOT re-register (that
     * would resurrect a retired unit as a duplicate).
     */
    private suspend fun reconcileMissing(asset: OfflineEquipmentAssetEntity): OperationOutcome {
        Log.w(SYNC_TAG, "Equipment asset ${asset.uuid} missing on server (404/410); reconciling as deleted")
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Equipment asset reconciled as deleted (missing on server)",
            mapOf("assetUuid" to asset.uuid, "serverId" to (asset.serverId?.toString() ?: "null"))
        )
        ctx.localDataService.saveEquipmentAssets(listOf(deletedCopy(asset)))
        return OperationOutcome.SUCCESS
    }

    /** Retire — DELETE /equipment-assets/{id} (no lock; returns 204). */
    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val asset = ctx.localDataService.getEquipmentAssetByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        gateOrSkip(asset.companyId)?.let { return it }
        val serverId = asset.serverId
        if (serverId == null) {
            ctx.localDataService.saveEquipmentAssets(listOf(deletedCopy(asset)))
            return OperationOutcome.SUCCESS
        }
        val response = try {
            ctx.api.retireEquipmentAsset(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(SYNC_TAG, "EquipmentAssetPushHandler retire error; retrying", e)
            ctx.remoteLogger?.log(
                LogLevel.WARN, SYNC_TAG, "Equipment asset retire retry (unexpected error)",
                mapOf("assetUuid" to asset.uuid, "serverId" to serverId.toString(),
                      "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
            )
            return OperationOutcome.RETRY
        }
        return when {
            response.isSuccessful || response.code() in listOf(404, 410) -> {
                DeletionTombstoneCache.clearTombstone("equipment_asset", serverId)
                ctx.localDataService.saveEquipmentAssets(listOf(deletedCopy(asset)))
                OperationOutcome.SUCCESS
            }
            // 422 = already retired or an open placement exists; not retryable as-is.
            response.code() == 422 -> {
                val body = response.errorBody()?.string()
                Log.w(SYNC_TAG, "Dropping equipment asset retire ${asset.uuid}: 422 - $body")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment asset retire dropped - 422",
                    mapOf("assetUuid" to asset.uuid, "serverId" to serverId.toString(), "body" to (body ?: ""))
                )
                // Review #5: the asset is still active server-side (e.g. still deployed) — restore
                // it from the server rather than leaving it stranded locally-deleted. RP-CD-019:
                // resolve the row so it isn't stranded PENDING.
                runCatching {
                    val fresh = ctx.api.getEquipmentAsset(serverId).data
                    ctx.localDataService.saveEquipmentAssets(listOf(fresh.toEntity(asset).copy(isDeleted = false)))
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    ctx.localDataService.saveEquipmentAssets(
                        listOf(asset.copy(isDeleted = false, isDirty = false, syncStatus = SyncStatus.FAILED, lastSyncedAt = ctx.now()))
                    )
                }
                OperationOutcome.DROP
            }
            else -> OperationOutcome.RETRY
        }
    }

    /**
     * Review #2: hard write boundary. A serialized op may only reach the server when
     * ITS company is in serialized mode. Company is derived from the entity, so a
     * company switch can't push work under the wrong context. OFF/UNKNOWN → SKIP
     * (hold and retry) — never push, never drop.
     */
    private suspend fun gateOrSkip(companyId: Long): OperationOutcome? =
        if (ctx.serializedModeFor(companyId) == SerializedEquipmentMode.ON) null
        else OperationOutcome.SKIP

    private fun deletedCopy(asset: OfflineEquipmentAssetEntity) = asset.copy(
        isDeleted = true,
        isDirty = false,
        syncStatus = SyncStatus.SYNCED,
        lastSyncedAt = ctx.now()
    )

    /**
     * Review #5: on a 409 the server holds a NEWER version. Do NOT blind-resubmit
     * the stale local data (that would clobber the other client's change — the exact
     * thing optimistic locking prevents). Record the conflict for user resolution
     * immediately and resolve the row out of PENDING so it doesn't re-enqueue.
     */
    private suspend fun handle409Conflict(
        error: HttpException,
        asset: OfflineEquipmentAssetEntity,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        // RP-CD-005: read the 409 body exactly once, here, for the remote version.
        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Equipment asset update 409 conflict → CONFLICT_PENDING",
            mapOf(
                "assetServerId" to (asset.serverId?.toString() ?: "null"),
                "assetUuid" to asset.uuid,
                "remoteUpdatedAt" to (freshUpdatedAt ?: "unknown")
            )
        )
        val conflict = OfflineConflictResolutionEntity(
            conflictId = UuidUtils.generateUuidV7(),
            entityType = "equipment_asset",
            entityId = asset.assetId,
            entityUuid = asset.uuid,
            localVersion = ctx.gson.toJson(
                mapOf<String, Any?>(
                    "name" to asset.name,
                    "manufacturer" to asset.manufacturer,
                    "model" to asset.model,
                    "serialNumber" to asset.serialNumber,
                    "assetTag" to asset.assetTag,
                    "status" to asset.status
                )
            ).toByteArray(Charsets.UTF_8),
            remoteVersion = ctx.gson.toJson(mapOf<String, Any?>("updatedAt" to freshUpdatedAt))
                .toByteArray(Charsets.UTF_8),
            conflictType = "UPDATE_CONFLICT",
            detectedAt = ctx.now(),
            originalOperationId = operation.operationId
        )
        ctx.recordConflict(conflict)
        // Resolve the row out of PENDING (→ CONFLICT) so it isn't re-enqueued in a loop;
        // the conflict record retains the local version for resolution.
        ctx.localDataService.saveEquipmentAssets(
            listOf(asset.copy(isDirty = false, syncStatus = SyncStatus.CONFLICT))
        )
        return OperationOutcome.CONFLICT_PENDING
    }
}
