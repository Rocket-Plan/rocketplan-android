package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.repository.mapper.toDeployRequest
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
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
            // Reflect the deploy on the (clean) asset so the pool view updates offline.
            if (!asset.isDirty) {
                ctx.localDataService.saveEquipmentAssets(
                    listOf(asset.copy(status = "deployed", currentPlacementServerId = dto.id))
                )
            }
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.isValidationError()) {
                // 422 = room not on project / already deployed / date overlap.
                Log.w(SYNC_TAG, "Dropping placement deploy ${placement.uuid}: 422 validation error")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Placement deploy dropped - 422",
                    mapOf("placementUuid" to placement.uuid, "assetServerId" to assetServerId.toString())
                )
                OperationOutcome.DROP
            } else {
                Log.w(SYNC_TAG, "EquipmentAssetPlacementPushHandler deploy error; retrying", e)
                OperationOutcome.RETRY
            }
        }
    }
}
