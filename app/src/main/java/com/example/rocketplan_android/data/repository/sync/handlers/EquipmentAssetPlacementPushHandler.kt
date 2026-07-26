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
import com.example.rocketplan_android.data.repository.mapper.toCorrectPlacementRequest
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
            // Review #3/#4/#8: refresh the asset from the server to pick up authoritative status /
            // current_placement / updated_at. preserveDirty=true so a genuine pending METADATA
            // edit (rename/serial) is merged-preserved rather than clobbered by older server
            // values; a clean asset adopts the server lifecycle fields. If the refresh fails the
            // deploy still succeeded — the asset is clean (status is optimistic + protected by the
            // pending placement op in the pull) and reconciles on the next successful refresh.
            runCatching {
                val assetDto = ctx.api.getEquipmentAsset(assetServerId).data
                ctx.localDataService.saveEquipmentAssets(listOf(mergeAfterLifecycleSuccess(asset, assetDto)), preserveDirty = true)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w(SYNC_TAG, "Deploy succeeded but asset refresh failed for ${asset.uuid}", err)
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Deploy ok but asset refresh failed",
                    mapOf("assetServerId" to assetServerId.toString(),
                          "error" to (err::class.java.simpleName + ": " + (err.message ?: "")))
                )
            }
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when {
                // Review round-9 (H2): a 409 on deploy (e.g. the server already has an open placement
                // for the asset) must NOT blind-retry forever — record a conflict and hold.
                e.isConflict() -> recordAssetConflict(e as HttpException, asset, operation, "DEPLOY_CONFLICT")
                e.isValidationError() -> resolveDeploy422(asset, placement, e)
                else -> {
                    Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler deploy error; retrying", e)
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Equipment deploy retry (unexpected error)",
                        mapOf("placementUuid" to placement.uuid,
                              "assetServerId" to assetServerId.toString(),
                              "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
                    )
                    OperationOutcome.RETRY
                }
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
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Equipment move retry (unexpected error)",
                        mapOf("placementUuid" to placement.uuid,
                              "assetServerId" to assetServerId.toString(),
                              "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
                    )
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
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Equipment check-out retry (unexpected error)",
                        mapOf("placementUuid" to placement.uuid,
                              "assetServerId" to assetServerId.toString(),
                              "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
                    )
                    OperationOutcome.RETRY
                }
            }
        }
    }

    /**
     * RP-FR-030 — CORRECT (PATCH /equipment-asset-placements/{id}). Unlike move/check-out this
     * locks on the PLACEMENT's own `serverUpdatedAt ?: updatedAt` (the asset's timestamp is
     * irrelevant here). SKIP-until-ready when the placement isn't server-known yet (can't correct
     * a placement the server has never seen). The 200 response is authoritative for this write, so
     * the returned placement is adopted (not preserveDirty).
     */
    suspend fun handleCorrect(operation: OfflineSyncQueueEntity): OperationOutcome {
        val placement = ctx.localDataService.getEquipmentPlacementByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (placement.isDeleted) return OperationOutcome.DROP
        val asset = ctx.localDataService.getEquipmentAsset(placement.assetId)
            ?: return OperationOutcome.DROP
        if (ctx.serializedModeFor(asset.companyId) != SerializedEquipmentMode.ON) return OperationOutcome.SKIP
        // Can't correct a placement the server has never seen — wait for the deploy to sync.
        val placementServerId = placement.serverId ?: return OperationOutcome.SKIP
        // Lock on the PLACEMENT's own updated_at (NOT the asset's). serverUpdatedAt is the
        // last server-known token; updatedAt was just bumped locally by the staging write, so
        // it is only a fallback for a never-reconciled row.
        val lockUpdatedAt = DateUtils.formatApiDate(placement.serverUpdatedAt ?: placement.updatedAt)
        return try {
            val dto = ctx.api.correctEquipmentPlacement(
                placementServerId,
                placement.toCorrectPlacementRequest(
                    dateIn = placement.dateIn,
                    dateOut = placement.dateOut,
                    lockUpdatedAt = lockUpdatedAt,
                    idempotencyKey = idempotencyKeyOf(operation)
                )
            ).data
            val localRoom = dto.roomId?.let { ctx.localDataService.getRoomByServerId(it) }
            val synced = dto.toEntity(
                existing = placement,
                assetLocalId = placement.assetId,
                roomLocalId = localRoom?.roomId ?: placement.roomId,
                projectLocalId = localRoom?.projectId ?: placement.projectId
            )
            ctx.localDataService.saveEquipmentPlacements(listOf(synced))
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when {
                e.isConflict() -> recordPlacementConflict(e as HttpException, placement, operation, "CORRECT_CONFLICT")
                e.isValidationError() -> resolve422(placement, e)
                else -> {
                    Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler correct error; retrying", e)
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Equipment placement correction retry (unexpected error)",
                        mapOf("placementUuid" to placement.uuid,
                              "placementServerId" to placementServerId.toString(),
                              "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
                    )
                    OperationOutcome.RETRY
                }
            }
        }
    }

    /**
     * RP-FR-031 — DELETE (DELETE /equipment-asset-placements/{id}). Soft-delete server-side;
     * only CLOSED placements are deletable (the backend 422s an active one — that's a check-out).
     * Client-side guard refuses to send for an open placement. 204 → mark the local row deleted
     * (SYNCED); 404/410 → already-gone → SUCCESS; 422 (active) → drain + DROP + WARN.
     */
    suspend fun handleDeletePlacement(operation: OfflineSyncQueueEntity): OperationOutcome {
        val placement = ctx.localDataService.getEquipmentPlacementByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val asset = ctx.localDataService.getEquipmentAsset(placement.assetId)
            ?: return OperationOutcome.DROP
        if (ctx.serializedModeFor(asset.companyId) != SerializedEquipmentMode.ON) return OperationOutcome.SKIP
        // Client-side guard: never delete an OPEN placement — that's a check-out, not a delete
        // (the backend would 422 anyway). Resolve as a no-op DROP with a WARN.
        if (placement.isOpen || placement.dateOut == null) {
            Log.w(SYNC_TAG, "Refusing to delete OPEN placement ${placement.uuid} — use check-out")
            ctx.remoteLogger?.log(
                LogLevel.WARN, SYNC_TAG, "Placement delete refused - placement is open (use check-out)",
                mapOf("placementUuid" to placement.uuid, "assetUuid" to asset.uuid)
            )
            return OperationOutcome.DROP
        }
        // Server never saw this placement. It's already collapsed locally by the service, so if
        // it's marked deleted there is nothing to send → DROP; otherwise wait for it to sync.
        val placementServerId = placement.serverId
        if (placementServerId == null) {
            return if (placement.isDeleted) OperationOutcome.DROP else OperationOutcome.SKIP
        }
        return try {
            val response = ctx.api.deleteEquipmentPlacement(placementServerId)
            when {
                response.isSuccessful || response.code() in listOf(404, 410) -> {
                    ctx.localDataService.saveEquipmentPlacements(listOf(placement.markReconciledDeleted()))
                    OperationOutcome.SUCCESS
                }
                response.code() == 422 -> {
                    val body = runCatching { response.errorBody()?.string() }.getOrNull()
                    Log.w(SYNC_TAG, "Placement delete rejected ${placement.uuid}: 422 - $body")
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Placement delete dropped - 422 (active placement)",
                        mapOf("placementUuid" to placement.uuid, "body" to (body ?: ""))
                    )
                    OperationOutcome.DROP
                }
                else -> throw HttpException(response)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when {
                e.isMissingOnServer() -> {
                    ctx.localDataService.saveEquipmentPlacements(listOf(placement.markReconciledDeleted()))
                    OperationOutcome.SUCCESS
                }
                e.isValidationError() -> {
                    val body = (e as? HttpException)?.response()?.errorBody()?.string()
                    Log.w(SYNC_TAG, "Placement delete rejected ${placement.uuid}: 422 - $body")
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Placement delete dropped - 422 (active placement)",
                        mapOf("placementUuid" to placement.uuid, "body" to (body ?: ""))
                    )
                    OperationOutcome.DROP
                }
                else -> {
                    Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler delete error; retrying", e)
                    ctx.remoteLogger?.log(
                        LogLevel.WARN, SYNC_TAG, "Equipment placement delete retry (unexpected error)",
                        mapOf("placementUuid" to placement.uuid,
                              "placementServerId" to placementServerId.toString(),
                              "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
                    )
                    OperationOutcome.RETRY
                }
            }
        }
    }

    /** Mirror of the pull service's reconcile-delete shape (isDeleted + closed + clean/SYNCED). */
    private fun OfflineEquipmentPlacementEntity.markReconciledDeleted() =
        copy(isDeleted = true, isOpen = false, isDirty = false, syncStatus = SyncStatus.SYNCED, lastSyncedAt = ctx.now())

    /** Save the returned asset + reconcile its placements (server-authoritative). */
    private suspend fun applyAssetResponse(existingAsset: OfflineEquipmentAssetEntity, assetDto: EquipmentAssetDto) {
        ctx.localDataService.saveEquipmentAssets(listOf(mergeAfterLifecycleSuccess(existingAsset, assetDto)), preserveDirty = true)
        assetDto.placements?.let { reconcilePlacements(existingAsset.assetId, it) }
    }

    /**
     * Review round-8 blocker: after a KNOWN-SUCCESSFUL local lifecycle op (deploy/move/check-out)
     * the server timestamp advanced because of OUR change — so we DO re-stamp (adopt the server's
     * fresh serverUpdatedAt + authoritative lifecycle), while still preserving any pending METADATA
     * edit's fields. This differs from the pull (which preserves the edit-base lock): here the new
     * timestamp is the correct base for the pending metadata update, so it won't false-conflict on
     * our own preceding lifecycle op.
     */
    private fun mergeAfterLifecycleSuccess(
        local: OfflineEquipmentAssetEntity,
        assetDto: EquipmentAssetDto
    ): OfflineEquipmentAssetEntity {
        val server = assetDto.toEntity(local) // adopts local identity; server fields incl. fresh serverUpdatedAt
        if (!local.isDirty) return server
        // Preserve dirty metadata fields; keep server lifecycle + fresh lock token.
        return server.copy(
            name = local.name,
            manufacturer = local.manufacturer,
            model = local.model,
            serialNumber = local.serialNumber,
            assetTag = local.assetTag,
            vendor = local.vendor,
            note = local.note,
            purchaseDate = local.purchaseDate,
            purchasePrice = local.purchasePrice,
            warrantyExpiresAt = local.warrantyExpiresAt,
            rentalDayRate = local.rentalDayRate,
            isDirty = true,
            syncStatus = local.syncStatus
        )
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
        val conflict409 = error.parse409(ctx.gson)
        val freshUpdatedAt = conflict409?.updatedAt
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
        // Review round-9 (H7): reflect the conflict on the asset row (preserve isDirty so a pending
        // metadata edit isn't clobbered) so its state isn't misreported as still-PENDING.
        ctx.localDataService.saveEquipmentAssets(listOf(asset.copy(syncStatus = SyncStatus.CONFLICT)))
        return OperationOutcome.CONFLICT_PENDING
    }

    /**
     * RP-FR-030 — placement-scoped optimistic-lock 409 (the correction locks on the placement's
     * own updated_at, so the conflict is recorded against the placement, not the asset). Extracts
     * the fresh server timestamp, records a conflict for user resolution, flips the placement to
     * CONFLICT (preserving isDirty so the staged edit isn't lost), and holds.
     */
    private suspend fun recordPlacementConflict(
        error: HttpException,
        placement: OfflineEquipmentPlacementEntity,
        operation: OfflineSyncQueueEntity,
        conflictType: String
    ): OperationOutcome {
        val conflict409 = error.parse409(ctx.gson)
        val freshUpdatedAt = conflict409?.updatedAt
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Serialized placement $conflictType (409)",
            mapOf("placementUuid" to placement.uuid, "remoteUpdatedAt" to (freshUpdatedAt ?: "unknown"))
        )
        ctx.recordConflict(
            OfflineConflictResolutionEntity(
                conflictId = UuidUtils.generateUuidV7(),
                entityType = "equipment_asset_placement",
                entityId = placement.placementId,
                entityUuid = placement.uuid,
                localVersion = ctx.gson.toJson(
                    mapOf<String, Any?>("dateIn" to placement.dateIn?.time, "dateOut" to placement.dateOut?.time)
                ).toByteArray(Charsets.UTF_8),
                remoteVersion = ctx.gson.toJson(mapOf<String, Any?>("updatedAt" to freshUpdatedAt)).toByteArray(Charsets.UTF_8),
                conflictType = conflictType,
                detectedAt = ctx.now(),
                originalOperationId = operation.operationId
            )
        )
        ctx.localDataService.saveEquipmentPlacements(listOf(placement.copy(syncStatus = SyncStatus.CONFLICT)))
        return OperationOutcome.CONFLICT_PENDING
    }

    /**
     * Review #1: the operation-scoped idempotency key persisted in the queue payload. Review round-9
     * (H3): the fallback (only for a legacy/missing payload) must stay stable across retries AND be
     * distinct per operation type — the bare placement uuid would collide across deploy/move/check-out
     * and the backend ledger could treat a move as a replay of the deploy.
     */
    private fun idempotencyKeyOf(operation: OfflineSyncQueueEntity): String =
        runCatching {
            ctx.gson.fromJson(String(operation.payload, Charsets.UTF_8), PendingLockPayload::class.java)?.idempotencyKey
        }.getOrNull() ?: "${operation.entityUuid}:${operation.operationType}"

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
