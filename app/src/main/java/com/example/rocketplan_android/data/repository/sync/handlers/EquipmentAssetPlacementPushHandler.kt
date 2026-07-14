package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.repository.mapper.toDeployRequest
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
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
            val dto = ctx.api.deployEquipmentAsset(assetServerId, placement.toDeployRequest(roomServerId)).data
            val synced = dto.toEntity(
                existing = placement,
                assetLocalId = placement.assetId,
                roomLocalId = placement.roomId,
                projectLocalId = placement.projectId
            )
            ctx.localDataService.saveEquipmentPlacements(listOf(synced))
            // Review #8: refresh the asset so its optimistic-lock token (serverUpdatedAt),
            // status and current_placement_id reflect the check-in — otherwise the next
            // move/check-out begins with a stale lock. Skip when the asset has unsynced
            // local edits (its own op carries them; a blind refresh would clobber them).
            if (!asset.isDirty) {
                runCatching {
                    val assetDto = ctx.api.getEquipmentAsset(assetServerId).data
                    ctx.localDataService.saveEquipmentAssets(listOf(assetDto.toEntity(asset)))
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    Log.w(SYNC_TAG, "Deploy succeeded but asset refresh failed for ${asset.uuid}", err)
                }
            }
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.isValidationError()) resolve422(placement, e)
            else {
                Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler deploy error; retrying", e)
                OperationOutcome.RETRY
            }
        }
    }

    /**
     * RP-CD-019: on a terminal 422 (room not on project / already deployed / overlap),
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
