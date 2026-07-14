package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
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
                e.isValidationError() -> {
                    Log.w(SYNC_TAG, "Dropping equipment asset ${asset.uuid}: 422 validation error")
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Equipment asset dropped - 422 validation error",
                        mapOf("assetUuid" to asset.uuid, "serverId" to (asset.serverId?.toString() ?: "null"))
                    )
                    OperationOutcome.DROP
                }
                asset.serverId != null && e.isMissingOnServer() -> {
                    // Row vanished server-side — re-register.
                    val recreated = ctx.api.registerEquipmentAsset(asset.companyId, asset.toRegisterRequest()).data
                    ctx.localDataService.saveEquipmentAssets(listOf(recreated.toEntity(asset)))
                    OperationOutcome.SUCCESS
                }
                else -> {
                    Log.w(SYNC_TAG, "EquipmentAssetPushHandler unknown error; retrying", e)
                    OperationOutcome.RETRY
                }
            }
        }
    }

    /** Retire — DELETE /equipment-assets/{id} (no lock; returns 204). */
    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val asset = ctx.localDataService.getEquipmentAssetByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
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
                Log.w(SYNC_TAG, "Dropping equipment asset retire ${asset.uuid}: 422 (already retired / open placement)")
                OperationOutcome.DROP
            }
            else -> OperationOutcome.RETRY
        }
    }

    private fun deletedCopy(asset: OfflineEquipmentAssetEntity) = asset.copy(
        isDeleted = true,
        isDirty = false,
        syncStatus = SyncStatus.SYNCED,
        lastSyncedAt = ctx.now()
    )

    private suspend fun handle409Conflict(
        error: HttpException,
        asset: OfflineEquipmentAssetEntity,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Equipment asset update 409 conflict",
            mapOf("assetServerId" to (asset.serverId?.toString() ?: "null"), "assetUuid" to asset.uuid)
        )
        // RP-CD-005: do NOT drain the 409 body before extractUpdatedAt consumes it.
        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
            ?: return OperationOutcome.SKIP

        val retryResult = runCatching {
            ctx.api.updateEquipmentAsset(asset.serverId!!, asset.toUpdateRequest(freshUpdatedAt)).data
        }.onFailure { if (it is CancellationException) throw it }

        retryResult.onFailure { retryError ->
            if (retryError.isConflict()) {
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
                return OperationOutcome.CONFLICT_PENDING
            }
            if (retryError.isValidationError()) return OperationOutcome.DROP
            throw retryError
        }

        ctx.localDataService.saveEquipmentAssets(listOf(retryResult.getOrThrow().toEntity(asset)))
        return OperationOutcome.SUCCESS
    }
}
