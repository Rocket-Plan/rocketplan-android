package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.feature.SerializedEquipmentMode
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.EquipmentAssetDto
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementDto
import com.example.rocketplan_android.data.repository.mapper.PendingLockPayload
import com.example.rocketplan_android.data.repository.mapper.toCheckOutRequest
import com.example.rocketplan_android.data.repository.mapper.toDeployRequest
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toMoveRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.util.UuidUtils
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

/**
 * RP-FR-019 — pushes serialized placement operations.
 *
 * Phase 1b implements DEPLOY (check-in) only: CREATE a placement → POST
 * /equipment-assets/{id}/placements. MOVE and CHECK-OUT are Phase 1c — their
 * endpoints return the asset (not the placement) and mint new server-side
 * placement ids, needing a refresh-and-reconcile design not shipped here.
 *
 * Parent readiness (SKIP-until-ready): the asset must be registered
 * (asset.serverId != null) and the room must be synced (room.serverId != null)
 * before a deploy can be pushed.
 */
class EquipmentAssetPlacementPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleDeploy(operation: OfflineSyncQueueEntity): OperationOutcome {
        val placement = ctx.localDataService.getEquipmentPlacementByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (placement.isDeleted) return OperationOutcome.DROP
        // Already pushed — nothing more to do for a deploy.
        if (placement.serverId != null) return OperationOutcome.SUCCESS

        val asset = ctx.localDataService.getEquipmentAsset(placement.assetId)
            ?: return OperationOutcome.DROP
        // Review #2: gate on the asset's company mode; OFF/UNKNOWN → SKIP (hold).
        if (ctx.serializedModeFor(asset.companyId) != SerializedEquipmentMode.ON) {
            return OperationOutcome.SKIP
        }
        val assetServerId = asset.serverId
        if (assetServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG, SYNC_TAG, "Placement waiting for asset to register",
                mapOf("placementUuid" to placement.uuid, "assetId" to placement.assetId.toString())
            )
            return OperationOutcome.SKIP
        }

        val roomLocalId = placement.roomId ?: return OperationOutcome.DROP
        val roomServerId = ctx.localDataService.getRoom(roomLocalId)?.serverId
        if (roomServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG, SYNC_TAG, "Placement waiting for room to sync",
                mapOf("placementUuid" to placement.uuid, "roomId" to roomLocalId.toString())
            )
            return OperationOutcome.SKIP
        }

        return try {
            val dto = ctx.api.deployEquipmentAsset(
                assetServerId, placement.toDeployRequest(roomServerId, idempotencyKeyOf(operation))
            ).data
            val synced = dto.toEntity(
                existing = placement,
                assetLocalId = placement.assetId,
                roomLocalId = placement.roomId,
                projectLocalId = placement.projectId
            )
            ctx.localDataService.saveEquipmentPlacements(listOf(synced))
            // Review #3/#8: deploy always marks the asset dirty (round-2 #2), so refresh it
            // UNCONDITIONALLY from the server to clear that dirty flag and pick up the fresh
            // optimistic-lock token / status / current_placement_id. If the refresh itself
            // fails the deploy still succeeded (the asset stays dirty and is protected by the
            // next pull); it reconciles on a later successful refresh.
            runCatching {
                val assetDto = ctx.api.getEquipmentAsset(assetServerId).data
                ctx.localDataService.saveEquipmentAssets(listOf(assetDto.toEntity(asset)))
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w(SYNC_TAG, "Deploy succeeded but asset refresh failed for ${asset.uuid}", err)
            }
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.isValidationError()) resolveDeploy422(asset, placement, e)
            else {
                Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler deploy error; retrying", e)
                OperationOutcome.RETRY
            }
        }
    }

    /**
     * Phase 1c — MOVE (POST /equipment-assets/{id}/move). Locks on the ASSET's
     * updated_at. The response returns the asset with `current_placement` + the full
     * `placements` list, so we reconcile server-authoritatively (server closed the old
     * placement and opened a new one with new ids).
     */
    suspend fun handleMove(operation: OfflineSyncQueueEntity): OperationOutcome {
        val placement = ctx.localDataService.getEquipmentPlacementByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val asset = ctx.localDataService.getEquipmentAsset(placement.assetId)
            ?: return OperationOutcome.DROP
        if (ctx.serializedModeFor(asset.companyId) != SerializedEquipmentMode.ON) return OperationOutcome.SKIP
        val assetServerId = asset.serverId ?: return OperationOutcome.SKIP
        val toRoomLocalId = placement.roomId ?: return OperationOutcome.DROP
        val toRoomServerId = ctx.localDataService.getRoom(toRoomLocalId)?.serverId
            ?: return OperationOutcome.SKIP
        val lockUpdatedAt = DateUtils.formatApiDate(asset.serverUpdatedAt ?: asset.updatedAt)
        return try {
            val assetDto = ctx.api.moveEquipmentAsset(
                assetServerId, placement.toMoveRequest(toRoomServerId, lockUpdatedAt, idempotencyKeyOf(operation))
            ).data
            applyAssetResponse(asset, assetDto)
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when {
                e.isConflict() -> recordAssetConflict(e as HttpException, asset, operation, "MOVE_CONFLICT")
                e.isValidationError() -> resolve422(placement, e)
                else -> {
                    Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler move error; retrying", e)
                    OperationOutcome.RETRY
                }
            }
        }
    }

    /**
     * Phase 1c — CHECK-OUT (POST /equipment-assets/{id}/check-out). Locks on the
     * ASSET's updated_at; closes the open placement server-side and frees the asset.
     */
    suspend fun handleCheckOut(operation: OfflineSyncQueueEntity): OperationOutcome {
        val placement = ctx.localDataService.getEquipmentPlacementByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val asset = ctx.localDataService.getEquipmentAsset(placement.assetId)
            ?: return OperationOutcome.DROP
        if (ctx.serializedModeFor(asset.companyId) != SerializedEquipmentMode.ON) return OperationOutcome.SKIP
        val assetServerId = asset.serverId ?: return OperationOutcome.SKIP
        // Can't check out a placement the server has never seen — wait for the deploy to sync.
        if (placement.serverId == null) return OperationOutcome.SKIP
        val lockUpdatedAt = DateUtils.formatApiDate(asset.serverUpdatedAt ?: asset.updatedAt)
        return try {
            val assetDto = ctx.api.checkOutEquipmentAsset(
                assetServerId, placement.toCheckOutRequest(lockUpdatedAt, idempotencyKeyOf(operation))
            ).data
            applyAssetResponse(asset, assetDto)
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when {
                e.isConflict() -> recordAssetConflict(e as HttpException, asset, operation, "CHECKOUT_CONFLICT")
                e.isValidationError() -> resolve422(placement, e)
                else -> {
                    Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler check-out error; retrying", e)
                    OperationOutcome.RETRY
                }
            }
        }
    }

    /** Save the returned asset + reconcile its placements (server-authoritative). */
    private suspend fun applyAssetResponse(existingAsset: OfflineEquipmentAssetEntity, assetDto: EquipmentAssetDto) {
        ctx.localDataService.saveEquipmentAssets(listOf(assetDto.toEntity(existingAsset)))
        assetDto.placements?.let { reconcilePlacements(existingAsset.assetId, it) }
    }

    /**
     * Reconcile a server-authoritative placement list onto local rows: match by
     * serverId (toEntity adopts the local placementId + uuid), insert new rows,
     * resolve server room ids to local rooms. History is preserved (closed rows are
     * included in the response). Not preserveDirty — the server just accepted our
     * write and is the source of truth here.
     */
    private suspend fun reconcilePlacements(assetLocalId: Long, dtos: List<EquipmentAssetPlacementDto>) {
        if (dtos.isEmpty()) return
        val existing = ctx.localDataService
            .getEquipmentPlacementsByServerIds(dtos.map { it.id })
            .associateBy { it.serverId }
        val entities = dtos.map { dto ->
            val ex = existing[dto.id]
            val localRoom = dto.roomId?.let { ctx.localDataService.getRoomByServerId(it) }
            dto.toEntity(
                existing = ex,
                assetLocalId = assetLocalId,
                roomLocalId = localRoom?.roomId ?: ex?.roomId,
                projectLocalId = localRoom?.projectId ?: ex?.projectId
            )
        }
        ctx.localDataService.saveEquipmentPlacements(entities)
    }

    /** Asset-level optimistic-lock 409 for move/check-out → record conflict, hold. */
    private suspend fun recordAssetConflict(
        error: HttpException,
        asset: OfflineEquipmentAssetEntity,
        operation: OfflineSyncQueueEntity,
        conflictType: String
    ): OperationOutcome {
        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Serialized placement $conflictType (409)",
            mapOf("assetUuid" to asset.uuid, "remoteUpdatedAt" to (freshUpdatedAt ?: "unknown"))
        )
        ctx.recordConflict(
            OfflineConflictResolutionEntity(
                conflictId = UuidUtils.generateUuidV7(),
                entityType = "equipment_asset",
                entityId = asset.assetId,
                entityUuid = asset.uuid,
                localVersion = ctx.gson.toJson(mapOf<String, Any?>("status" to asset.status)).toByteArray(Charsets.UTF_8),
                remoteVersion = ctx.gson.toJson(mapOf<String, Any?>("updatedAt" to freshUpdatedAt)).toByteArray(Charsets.UTF_8),
                conflictType = conflictType,
                detectedAt = ctx.now(),
                originalOperationId = operation.operationId
            )
        )
        return OperationOutcome.CONFLICT_PENDING
    }

    /** Review #1: the operation-scoped idempotency key persisted in the queue payload. */
    private fun idempotencyKeyOf(operation: OfflineSyncQueueEntity): String =
        runCatching {
            ctx.gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingLockPayload::class.java)?.idempotencyKey
        }.getOrNull() ?: operation.entityUuid

    /**
     * Review #4: a terminal deploy 422 must roll back the optimistic lifecycle state,
     * not just mark the placement FAILED. The server rejected the check-in, so the
     * placement is soft-deleted and the (server-known) asset is returned to available
     * and cleaned — otherwise the UI shows a phantom deployment forever.
     */
    private suspend fun resolveDeploy422(
        asset: OfflineEquipmentAssetEntity,
        placement: OfflineEquipmentPlacementEntity,
        error: Throwable
    ): OperationOutcome {
        val body = (error as? HttpException)?.response()?.errorBody()?.string()
        Log.w(SYNC_TAG, "Dropping placement deploy ${placement.uuid}: 422 - $body")
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Placement deploy dropped - 422 (rolled back)",
            mapOf("placementUuid" to placement.uuid, "assetUuid" to asset.uuid, "body" to (body ?: ""))
        )
        val now = ctx.now()
        ctx.localDataService.saveEquipmentPlacements(
            listOf(placement.copy(isDeleted = true, isOpen = false, isDirty = false, syncStatus = SyncStatus.FAILED, lastSyncedAt = now))
        )
        ctx.localDataService.saveEquipmentAssets(
            listOf(asset.copy(status = "available", isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = now))
        )
        return OperationOutcome.DROP
    }

    /**
     * RP-CD-019: on a terminal 422 (move/check-out — no open placement / date overlap),
     * drain the body for diagnosis and resolve the local row (→ FAILED, clean) so it
     * is not stranded PENDING with no queue op.
     */
    private suspend fun resolve422(
        placement: OfflineEquipmentPlacementEntity,
        error: Throwable
    ): OperationOutcome {
        val body = (error as? HttpException)?.response()?.errorBody()?.string()
        Log.w(SYNC_TAG, "Dropping placement deploy ${placement.uuid}: 422 - $body")
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Placement deploy dropped - 422",
            mapOf("placementUuid" to placement.uuid, "body" to (body ?: ""))
        )
        ctx.localDataService.saveEquipmentPlacements(
            listOf(placement.copy(isDirty = false, syncStatus = SyncStatus.FAILED, lastSyncedAt = ctx.now()))
        )
        return OperationOutcome.DROP
    }
}
