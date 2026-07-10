package com.example.rocketplan_android.data.repository.sync.handlers

import android.util.Log
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.model.offline.AttachRoomEquipmentRequest
import com.example.rocketplan_android.data.model.offline.AttachRoomEquipmentItem
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.EquipmentMoveRequest
import com.example.rocketplan_android.data.model.offline.EquipmentRequest
import com.example.rocketplan_android.data.model.offline.EquipmentTransferRequest
import com.example.rocketplan_android.data.model.offline.UpdateEquipmentRoomRequest
import com.example.rocketplan_android.data.repository.mapper.PendingEquipmentMovePayload
import com.example.rocketplan_android.data.repository.mapper.PendingEquipmentTransferPayload
import com.example.rocketplan_android.data.repository.mapper.toApiTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.util.UuidUtils
import retrofit2.HttpException
import kotlin.coroutines.cancellation.CancellationException

class EquipmentPushHandler(private val ctx: PushHandlerContext) {

    suspend fun handleUpsert(operation: OfflineSyncQueueEntity): OperationOutcome {
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        if (equipment.isDeleted) return OperationOutcome.DROP

        val projectServerId = resolveServerProjectId(equipment.projectId)
        if (projectServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Equipment waiting for project to sync",
                mapOf(
                    "equipmentUuid" to equipment.uuid,
                    "projectId" to equipment.projectId.toString()
                )
            )
            return OperationOutcome.SKIP
        }

        val roomServerId = equipment.roomId?.let { roomId ->
            ctx.localDataService.getRoom(roomId)?.serverId
        }
        if (equipment.roomId != null && roomServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Equipment waiting for room to sync",
                mapOf(
                    "equipmentUuid" to equipment.uuid,
                    "roomId" to equipment.roomId.toString()
                )
            )
            return OperationOutcome.SKIP
        }

        val lockUpdatedAt = (equipment.serverUpdatedAt ?: equipment.updatedAt).toApiTimestamp()
        return try {
            val synced = pushPendingEquipmentUpsert(equipment, projectServerId, roomServerId, lockUpdatedAt)
            if (synced == null) {
                Log.w(SYNC_TAG, "⚠️ [handleUpsert] Pivot missing for equipment ${equipment.uuid}; marking as deleted (tombstone)")
                ctx.localDataService.saveEquipment(listOf(deletedCopy(equipment)))
                return OperationOutcome.DROP
            }
            ctx.localDataService.saveEquipment(listOf(synced))
            OperationOutcome.SUCCESS
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.isConflict() && equipment.serverId != null) {
                return handle409Conflict(e as HttpException, equipment, projectServerId, roomServerId, operation)
            }
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment dropped - 422 validation error",
                    mapOf("equipmentUuid" to equipment.uuid, "serverId" to (equipment.serverId?.toString() ?: "null"))
                )
                OperationOutcome.DROP
            } else {
                Log.w(SYNC_TAG, "EquipmentPushHandler unknown error; retrying", e)
                OperationOutcome.RETRY
            }
        }
    }

    suspend fun handleDelete(operation: OfflineSyncQueueEntity): OperationOutcome {
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP
        val serverId = equipment.serverId
        if (serverId == null) {
            ctx.localDataService.saveEquipment(listOf(deletedCopy(equipment)))
            return OperationOutcome.SUCCESS
        }
        val lockUpdatedAt = (equipment.serverUpdatedAt ?: equipment.updatedAt).toApiTimestamp()
        val outcome = try {
            resolveDeleteWithStaleRetry(lockUpdatedAt) { ts ->
                ctx.api.deleteEquipmentRoom(serverId, DeleteWithTimestampRequest(updatedAt = ts))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(SYNC_TAG, "EquipmentPushHandler delete error; retrying", e)
            return OperationOutcome.RETRY
        }
        outcome?.let {
            if (it == OperationOutcome.DROP) {
                Log.w(SYNC_TAG, "Dropping equipment delete ${equipment.uuid}: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment delete dropped - 422 validation error",
                    mapOf("equipmentUuid" to equipment.uuid, "serverId" to serverId.toString())
                )
            }
            return it
        }
        DeletionTombstoneCache.clearTombstone("equipment", serverId)
        ctx.localDataService.saveEquipment(listOf(deletedCopy(equipment)))
        return OperationOutcome.SUCCESS
    }

    suspend fun handleMove(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = parseMovePayload(operation)
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP

        val pivotServerId = payload.pivotServerId ?: equipment.serverId
        if (pivotServerId == null) {
            Log.w(SYNC_TAG, "⚠️ handleMove: pivot has no serverId yet, SKIP until created")
            return OperationOutcome.SKIP
        }

        val toRoomServerId = resolveToRoomServerId(payload)
        if (toRoomServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Equipment move waiting for destination room to sync",
                mapOf(
                    "equipmentUuid" to equipment.uuid,
                    "toRoomId" to (payload.toRoomId?.toString() ?: "null"),
                    "toRoomUuid" to (payload.toRoomUuid ?: "null")
                )
            )
            return OperationOutcome.SKIP
        }

        if (toRoomServerId < 0) {
            Log.w(SYNC_TAG, "⚠️ handleMove: destination room has negative serverId ${toRoomServerId}, SKIP")
            return OperationOutcome.SKIP
        }

        val updatedAt = payload.lockUpdatedAt
            ?: equipment.serverUpdatedAt?.toApiTimestamp()
            ?: equipment.updatedAt.toApiTimestamp()
        if (updatedAt == null) {
            Log.w(SYNC_TAG, "⚠️ handleMove: no valid updatedAt for pivot ${equipment.uuid}; SKIP until pivot is synced")
            return OperationOutcome.SKIP
        }

        val moveRequest = EquipmentMoveRequest(
            toRoomId = toRoomServerId,
            quantity = payload.quantity,
            movedAt = payload.movedAt,
            note = payload.note,
            idempotencyKey = payload.idempotencyKey,
            updatedAt = updatedAt
        )

        return try {
            val response = ctx.api.moveEquipmentRoom(pivotServerId, moveRequest)
            val returnedPivots = response.data
            val saved = reconcileMoveResult(equipment, toRoomServerId, payload.quantity, returnedPivots)
            saved?.let { ctx.localDataService.saveEquipment(listOf(it)) }
            Log.d(SYNC_TAG, "✅ handleMove succeeded for pivot $pivotServerId")
            OperationOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isConflict()) {
                return handleMoveConflict(e as HttpException, equipment, pivotServerId, moveRequest, operation)
            }
            if (e.isMissingOnServer()) {
                Log.w(SYNC_TAG, "⚠️ handleMove: pivot $pivotServerId missing on server, SKIP")
                return OperationOutcome.SKIP
            }
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid} move: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment move dropped - 422 validation error",
                    mapOf("equipmentUuid" to equipment.uuid, "pivotServerId" to pivotServerId.toString())
                )
                return OperationOutcome.DROP
            }
            Log.w(SYNC_TAG, "handleMove error for pivot $pivotServerId; retrying", e)
            OperationOutcome.RETRY
        }
    }

    suspend fun handleTransfer(operation: OfflineSyncQueueEntity): OperationOutcome {
        val payload = parseTransferPayload(operation)
        val equipment = ctx.localDataService.getEquipmentByUuid(operation.entityUuid)
            ?: return OperationOutcome.DROP

        val pivotServerId = payload.pivotServerId ?: equipment.serverId
        if (pivotServerId == null) {
            Log.w(SYNC_TAG, "⚠️ handleTransfer: pivot has no serverId yet, SKIP until created")
            return OperationOutcome.SKIP
        }

        val toRoomServerId = resolveToRoomServerId(payload)
        if (toRoomServerId == null) {
            ctx.remoteLogger?.log(
                LogLevel.DEBUG,
                SYNC_TAG,
                "Equipment transfer waiting for destination room to sync",
                mapOf(
                    "equipmentUuid" to equipment.uuid,
                    "toRoomId" to (payload.toRoomId?.toString() ?: "null"),
                    "toRoomUuid" to (payload.toRoomUuid ?: "null")
                )
            )
            return OperationOutcome.SKIP
        }

        if (toRoomServerId < 0) {
            Log.w(SYNC_TAG, "⚠️ handleTransfer: destination room has negative serverId ${toRoomServerId}, SKIP")
            return OperationOutcome.SKIP
        }

        val updatedAt = payload.lockUpdatedAt
            ?: equipment.serverUpdatedAt?.toApiTimestamp()
            ?: equipment.updatedAt.toApiTimestamp()
        if (updatedAt == null) {
            Log.w(SYNC_TAG, "⚠️ handleTransfer: no valid updatedAt for pivot ${equipment.uuid}; SKIP until pivot is synced")
            return OperationOutcome.SKIP
        }

        val transferRequest = EquipmentTransferRequest(
            toRoomId = toRoomServerId,
            quantity = payload.quantity,
            movedAt = payload.movedAt,
            note = payload.note,
            idempotencyKey = payload.idempotencyKey,
            updatedAt = updatedAt
        )

        return try {
            val response = ctx.api.transferEquipmentRoom(pivotServerId, transferRequest)
            val returnedPivots = response.data
            val saved = reconcileTransferResult(equipment, payload.quantity, returnedPivots)
            saved?.let { ctx.localDataService.saveEquipment(listOf(it)) }
            Log.d(SYNC_TAG, "✅ handleTransfer succeeded for pivot $pivotServerId")
            OperationOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isConflict()) {
                return handleTransferConflict(e as HttpException, equipment, pivotServerId, transferRequest, operation)
            }
            if (e.isMissingOnServer()) {
                Log.w(SYNC_TAG, "⚠️ handleTransfer: pivot $pivotServerId missing on server, SKIP")
                return OperationOutcome.SKIP
            }
            if (e.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid} transfer: server validation error (422)")
                ctx.remoteLogger?.log(
                    LogLevel.WARN, SYNC_TAG, "Equipment transfer dropped - 422 validation error",
                    mapOf("equipmentUuid" to equipment.uuid, "pivotServerId" to pivotServerId.toString())
                )
                return OperationOutcome.DROP
            }
            Log.w(SYNC_TAG, "handleTransfer error for pivot $pivotServerId; retrying", e)
            OperationOutcome.RETRY
        }
    }

    private fun parseMovePayload(operation: OfflineSyncQueueEntity): PendingEquipmentMovePayload {
        return ctx.gson.fromJson(
            String(operation.payload, Charsets.UTF_8),
            PendingEquipmentMovePayload::class.java
        )
    }

    private fun parseTransferPayload(operation: OfflineSyncQueueEntity): PendingEquipmentTransferPayload {
        return ctx.gson.fromJson(
            String(operation.payload, Charsets.UTF_8),
            PendingEquipmentTransferPayload::class.java
        )
    }

    private suspend fun resolveToRoomServerId(payload: PendingEquipmentMovePayload): Long? {
        payload.toRoomUuid?.let { uuid ->
            val room = ctx.localDataService.getRoomByUuid(uuid)
            if (room != null && room.serverId != null && room.serverId > 0) {
                return room.serverId
            }
            if (room != null) {
                return null
            }
        }
        return payload.toRoomId?.takeIf { it > 0 }
    }

    private suspend fun resolveToRoomServerId(payload: PendingEquipmentTransferPayload): Long? {
        payload.toRoomUuid?.let { uuid ->
            val room = ctx.localDataService.getRoomByUuid(uuid)
            if (room != null && room.serverId != null && room.serverId > 0) {
                return room.serverId
            }
            if (room != null) {
                return null
            }
        }
        return payload.toRoomId?.takeIf { it > 0 }
    }

    /**
     * Maps a SERVER room id to its LOCAL room PK. OfflineEquipmentEntity.roomId is a local room PK
     * (observeEquipmentForRoom and the pull path both use the local id); move/transfer responses and
     * attach calls speak in server room ids, so they must be translated before being stored.
     */
    private suspend fun localRoomIdForServer(serverRoomId: Long?): Long? {
        if (serverRoomId == null) return null
        return ctx.localDataService.getRoomByServerId(serverRoomId)?.roomId
    }

    private suspend fun reconcileMoveResult(
        equipment: OfflineEquipmentEntity,
        toRoomServerId: Long,
        requestedQty: Int?,
        returnedPivots: List<EquipmentDto>
    ): OfflineEquipmentEntity? {
        val isFullMove = requestedQty == null || requestedQty >= equipment.quantity
        val sourcePivot = returnedPivots.find {
            it.uuid == equipment.uuid || it.id == equipment.serverId
        }
        val destPivot = if (isFullMove) {
            null
        } else {
            returnedPivots.find {
                it.uuid != equipment.uuid && it.id != equipment.serverId
            }
        }
        if (sourcePivot == null && returnedPivots.isNotEmpty()) {
            Log.w(SYNC_TAG, "⚠️ reconcileMove: source pivot not in response; SKIP reconciliation for equipment ${equipment.uuid}")
            return null
        }
        val toRoomLocalId = localRoomIdForServer(toRoomServerId) ?: equipment.roomId
        if (isFullMove) {
            return equipment.copy(
                serverId = sourcePivot?.id ?: equipment.serverId,
                roomId = toRoomLocalId,
                quantity = sourcePivot?.quantity ?: equipment.quantity,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
        } else {
            if (sourcePivot == null) {
                Log.w(SYNC_TAG, "⚠️ reconcileMove: partial move but source pivot missing; SKIP for equipment ${equipment.uuid}")
                return null
            }
            val updatedQty = sourcePivot.quantity ?: equipment.quantity
            val sourceUpdated = equipment.copy(
                serverId = sourcePivot.id,
                roomId = equipment.roomId,
                quantity = updatedQty,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )
            ctx.localDataService.saveEquipment(listOf(sourceUpdated))
            if (destPivot != null) {
                val destEntity = destPivot.toEntity().copy(
                    equipmentId = 0,
                    serverId = destPivot.id,
                    catalogServerId = equipment.catalogServerId,
                    catalogUuid = equipment.catalogUuid,
                    uuid = destPivot.uuid ?: UuidUtils.generateUuidV7(),
                    projectId = equipment.projectId,
                    roomId = toRoomLocalId,
                    isDirty = false,
                    syncStatus = SyncStatus.SYNCED,
                    lastSyncedAt = ctx.now()
                )
                return destEntity
            }
            return null
        }
    }

    private suspend fun reconcileTransferResult(
        equipment: OfflineEquipmentEntity,
        requestedQty: Int,
        returnedPivots: List<EquipmentDto>
    ): OfflineEquipmentEntity? {
        val sourcePivot = returnedPivots.find {
            it.uuid == equipment.uuid || it.id == equipment.serverId
        }
        val destPivot = returnedPivots.find {
            it.uuid != equipment.uuid && it.id != equipment.serverId
        }
        if (sourcePivot == null) {
            Log.w(SYNC_TAG, "⚠️ reconcileTransfer: source pivot not in response (expected for full transfer); proceeding with dest only for equipment ${equipment.uuid}")
        }
        if (destPivot == null) {
            Log.w(SYNC_TAG, "⚠️ reconcileTransfer: dest pivot not in response; SKIP for equipment ${equipment.uuid}")
            return null
        }
        // A full transfer moves the entire quantity: the backend soft-deletes the source pivot and
        // returns ONLY the destination (sourcePivot == null). Treat requestedQty >= equipment.quantity
        // as full too, so a stale/echoed source pivot can't be misread as a partial and leave a ghost.
        val isFullTransfer = sourcePivot == null || requestedQty >= equipment.quantity
        val updatedQty = sourcePivot?.quantity ?: (equipment.quantity - requestedQty)
        if (!isFullTransfer && updatedQty > 0) {
            // Partial transfer: source pivot survives with a reduced quantity.
            ctx.localDataService.saveEquipment(listOf(equipment.copy(
                serverId = sourcePivot?.id ?: equipment.serverId,
                quantity = updatedQty,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )))
            Log.d(SYNC_TAG, "✅ reconcileTransfer: partial transfer — source quantity reduced to $updatedQty")
        } else {
            // Full transfer: the source no longer exists server-side (sourcePivot == null) or the
            // whole quantity moved. Tombstone the local source row so it stops showing in the source room.
            ctx.localDataService.saveEquipment(listOf(equipment.copy(
                serverId = sourcePivot?.id ?: equipment.serverId,
                isDeleted = true,
                isDirty = false,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = ctx.now()
            )))
            Log.d(SYNC_TAG, "✅ reconcileTransfer: full transfer — source tombstoned")
        }
        return destPivot.toEntity().copy(
            equipmentId = 0,
            serverId = destPivot.id,
            catalogServerId = equipment.catalogServerId,
            catalogUuid = equipment.catalogUuid,
            uuid = destPivot.uuid ?: UuidUtils.generateUuidV7(),
            projectId = equipment.projectId,
            roomId = (destPivot.roomId?.let { localRoomIdForServer(it) }) ?: equipment.roomId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = ctx.now()
        )
    }

    private suspend fun handleMoveConflict(
        error: HttpException,
        equipment: OfflineEquipmentEntity,
        pivotServerId: Long,
        moveRequest: EquipmentMoveRequest,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        if (freshUpdatedAt == null) {
            return OperationOutcome.SKIP
        }
        val retryRequest = moveRequest.copy(updatedAt = freshUpdatedAt)
        val retryResult = runCatching {
            ctx.api.moveEquipmentRoom(pivotServerId, retryRequest)
        }
        retryResult.onFailure { retryError ->
            if (retryError.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ handleMove: retry still got 409; recording conflict")
                val conflict = OfflineConflictResolutionEntity(
                    conflictId = UuidUtils.generateUuidV7(),
                    entityType = "equipment",
                    entityId = equipment.equipmentId,
                    entityUuid = equipment.uuid,
                    localVersion = ctx.gson.toJson(moveRequest).toByteArray(Charsets.UTF_8),
                    remoteVersion = ctx.gson.toJson(mapOf("updatedAt" to freshUpdatedAt)).toByteArray(Charsets.UTF_8),
                    conflictType = "MOVE_CONFLICT",
                    detectedAt = ctx.now(),
                    originalOperationId = operation.operationId
                )
                ctx.recordConflict(conflict)
                return OperationOutcome.CONFLICT_PENDING
            }
            throw retryError
        }
        val returnedPivots = retryResult.getOrThrow().data
        val saved = reconcileMoveResult(equipment, moveRequest.toRoomId, moveRequest.quantity, returnedPivots)
        saved?.let { ctx.localDataService.saveEquipment(listOf(it)) }
        return OperationOutcome.SUCCESS
    }

    private suspend fun handleTransferConflict(
        error: HttpException,
        equipment: OfflineEquipmentEntity,
        pivotServerId: Long,
        transferRequest: EquipmentTransferRequest,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        if (freshUpdatedAt == null) {
            return OperationOutcome.SKIP
        }
        val retryRequest = transferRequest.copy(updatedAt = freshUpdatedAt)
        val retryResult = runCatching {
            ctx.api.transferEquipmentRoom(pivotServerId, retryRequest)
        }
        retryResult.onFailure { retryError ->
            if (retryError.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ handleTransfer: retry still got 409; recording conflict")
                val conflict = OfflineConflictResolutionEntity(
                    conflictId = UuidUtils.generateUuidV7(),
                    entityType = "equipment",
                    entityId = equipment.equipmentId,
                    entityUuid = equipment.uuid,
                    localVersion = ctx.gson.toJson(transferRequest).toByteArray(Charsets.UTF_8),
                    remoteVersion = ctx.gson.toJson(mapOf("updatedAt" to freshUpdatedAt)).toByteArray(Charsets.UTF_8),
                    conflictType = "TRANSFER_CONFLICT",
                    detectedAt = ctx.now(),
                    originalOperationId = operation.operationId
                )
                ctx.recordConflict(conflict)
                return OperationOutcome.CONFLICT_PENDING
            }
            throw retryError
        }
        val returnedPivots = retryResult.getOrThrow().data
        val saved = reconcileTransferResult(equipment, transferRequest.quantity, returnedPivots)
        saved?.let { ctx.localDataService.saveEquipment(listOf(it)) }
        return OperationOutcome.SUCCESS
    }

    private fun deletedCopy(equipment: OfflineEquipmentEntity) = equipment.copy(
        isDeleted = true,
        isDirty = false,
        syncStatus = SyncStatus.SYNCED,
        lastSyncedAt = ctx.now()
    )

    private suspend fun handle409Conflict(
        error: HttpException,
        equipment: OfflineEquipmentEntity,
        projectServerId: Long,
        roomServerId: Long?,
        operation: OfflineSyncQueueEntity
    ): OperationOutcome {
        Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] 409 conflict for equipment pivot ${equipment.serverId}; extracting fresh timestamp and retrying")
        ctx.remoteLogger?.log(
            LogLevel.WARN, SYNC_TAG, "Equipment update 409 conflict",
            mapOf("equipmentServerId" to (equipment.serverId?.toString() ?: "null"), "equipmentUuid" to equipment.uuid)
        )

        val freshUpdatedAt = error.extractUpdatedAt(ctx.gson)
        if (freshUpdatedAt == null) {
            Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Could not extract updated_at from 409 body for pivot ${equipment.serverId}; will retry later")
            return OperationOutcome.SKIP
        }

        val updateRequest = UpdateEquipmentRoomRequest(
            quantity = equipment.quantity,
            duration = null,
            dateIn = equipment.startDate.toApiTimestamp(),
            dateOut = equipment.endDate.toApiTimestamp(),
            updatedAt = freshUpdatedAt
        )
        val retryResult = runCatching { ctx.api.updateEquipmentRoom(equipment.serverId!!, updateRequest) }
            .onFailure { if (it is CancellationException) throw it }
            .onSuccess { response ->
                if (!response.isSuccessful) {
                    throw retrofit2.HttpException(response)
                }
            }

        retryResult.onFailure { retryError ->
            if (retryError.isConflict()) {
                Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Retry still got 409; recording conflict for user resolution")
                val conflict = OfflineConflictResolutionEntity(
                    conflictId = UuidUtils.generateUuidV7(),
                    entityType = "equipment",
                    entityId = equipment.equipmentId,
                    entityUuid = equipment.uuid,
                    localVersion = ctx.gson.toJson(mapOf<String, Any?>(
                        "quantity" to equipment.quantity,
                        "startDate" to equipment.startDate.toApiTimestamp(),
                        "endDate" to equipment.endDate.toApiTimestamp()
                    )).toByteArray(Charsets.UTF_8),
                    remoteVersion = ctx.gson.toJson(mapOf<String, Any?>(
                        "updatedAt" to freshUpdatedAt
                    )).toByteArray(Charsets.UTF_8),
                    conflictType = "UPDATE_CONFLICT",
                    detectedAt = ctx.now(),
                    originalOperationId = operation.operationId
                )
                ctx.recordConflict(conflict)
                return OperationOutcome.CONFLICT_PENDING
            }
            if (retryError.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid}: server validation error (422)")
                return OperationOutcome.DROP
            }
            throw retryError
        }

        val synced = equipment.copy(
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = ctx.now()
        )
        ctx.localDataService.saveEquipment(listOf(synced))
        Log.d(SYNC_TAG, "✅ [syncPendingEquipment] Retry update succeeded for pivot ${equipment.serverId}")
        return OperationOutcome.SUCCESS
    }

    private suspend fun pushPendingEquipmentUpsert(
        equipment: OfflineEquipmentEntity,
        projectServerId: Long,
        roomServerId: Long?,
        lockUpdatedAt: String?
    ): OfflineEquipmentEntity? {
        if (equipment.serverId != null) {
            return updateExistingPivot(equipment, lockUpdatedAt)
        }
        return createAndAttachPivot(equipment, projectServerId, roomServerId)
    }

    private suspend fun createAndAttachPivot(
        equipment: OfflineEquipmentEntity,
        projectServerId: Long,
        roomServerId: Long?
    ): OfflineEquipmentEntity {
        val catalogServerId = resolveOrCreateCatalog(equipment, projectServerId)
        val roomId = roomServerId ?: throw IllegalStateException("roomServerId required for equipment attach")
        val attachRequest = AttachRoomEquipmentRequest(
            idempotencyKey = equipment.uuid,
            equipment = listOf(
                AttachRoomEquipmentItem(
                    equipmentId = catalogServerId,
                    uuid = equipment.uuid,
                    dateIn = equipment.startDate.toApiTimestamp(),
                    quantity = equipment.quantity
                )
            )
        )
        val response = ctx.api.attachRoomEquipment(roomId, attachRequest)
        val returnedPivot = response.data.firstOrNull()
            ?: throw IllegalStateException("attachRoomEquipment returned empty data for uuid ${equipment.uuid}")
        return returnedPivot.toEntity().copy(
            equipmentId = equipment.equipmentId,
            serverId = returnedPivot.pivotId ?: returnedPivot.id,
            catalogServerId = catalogServerId,
            catalogUuid = equipment.catalogUuid ?: returnedPivot.catalogUuid,
            uuid = equipment.uuid,
            projectId = equipment.projectId,
            // roomId is a LOCAL room PK everywhere (observeEquipmentForRoom, pull path). `roomId`
            // here is the SERVER room id used for the attach call — never store it in this field.
            roomId = equipment.roomId ?: localRoomIdForServer(roomId),
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = ctx.now()
        )
    }

    private suspend fun resolveOrCreateCatalog(
        equipment: OfflineEquipmentEntity,
        projectServerId: Long
    ): Long {
        if (equipment.catalogServerId != null) {
            return equipment.catalogServerId
        }
        val hasIdentityFields = !equipment.brand.isNullOrBlank() || !equipment.model.isNullOrBlank() || !equipment.serialNumber.isNullOrBlank()
        val catalogByIdentity = if (hasIdentityFields) {
            runCatching {
                ctx.api.getProjectEquipment(projectServerId).data
                    .firstOrNull { dto ->
                        dto.type?.equals(equipment.type, ignoreCase = true) == true &&
                            dto.brand?.equals(equipment.brand, ignoreCase = true) == true &&
                            dto.model?.equals(equipment.model, ignoreCase = true) == true &&
                            dto.serialNumber?.equals(equipment.serialNumber, ignoreCase = true) == true
                    }
            }.getOrNull()
        } else null
        if (catalogByIdentity != null) {
            return catalogByIdentity.id
        }
        val catalogByName = runCatching {
            ctx.api.getProjectEquipment(projectServerId).data
                .firstOrNull { it.type?.equals(equipment.type, ignoreCase = true) == true }
        }.getOrNull()
        if (catalogByName != null) {
            return catalogByName.id
        }
        val catalogRequest = EquipmentRequest(
            projectId = projectServerId,
            type = equipment.type,
            brand = equipment.brand,
            model = equipment.model,
            serialNumber = equipment.serialNumber,
            quantity = 1,
            status = equipment.status,
            idempotencyKey = "${equipment.uuid}-catalog"
        )
        val created = ctx.api.createProjectEquipment(projectServerId, catalogRequest)
        return created.data.id
    }

    private suspend fun updateExistingPivot(
        equipment: OfflineEquipmentEntity,
        lockUpdatedAt: String?
    ): OfflineEquipmentEntity? {
        val pivotId = equipment.serverId!!
        val updateRequest = UpdateEquipmentRoomRequest(
            quantity = equipment.quantity,
            duration = null,
            dateIn = equipment.startDate.toApiTimestamp(),
            dateOut = equipment.endDate.toApiTimestamp(),
            updatedAt = lockUpdatedAt ?: (equipment.serverUpdatedAt ?: equipment.updatedAt).toApiTimestamp()!!
        )
        // updateEquipmentRoom returns Response<Unit>; Retrofit does NOT throw on HTTP errors (4xx/5xx),
        // it returns the Response with the error code. We must check isSuccessful ourselves.
        runCatching { ctx.api.updateEquipmentRoom(pivotId, updateRequest) }
            .onSuccess { response ->
                if (!response.isSuccessful) {
                    throw retrofit2.HttpException(response)
                }
            }
            .recoverCatching { error ->
                if (error.isMissingOnServer()) {
                    Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Pivot $pivotId missing on server; treating as DROP with local tombstone")
                    return null
                }
                throw error
            }
            .onFailure { error ->
                val errorBody = if (error.isConflict()) null
                else (error as? retrofit2.HttpException)?.response()?.errorBody()?.string()
                Log.w(SYNC_TAG, "⚠️ [syncPendingEquipment] Failed to update equipment pivot ${equipment.uuid}: $errorBody", error)
            }.getOrElse { throw it }
        return equipment.copy(
            equipmentId = equipment.equipmentId,
            serverId = equipment.serverId,
            catalogServerId = equipment.catalogServerId,
            catalogUuid = equipment.catalogUuid,
            uuid = equipment.uuid,
            projectId = equipment.projectId,
            roomId = equipment.roomId,
            isDirty = false,
            syncStatus = SyncStatus.SYNCED,
            isDeleted = false,
            lastSyncedAt = ctx.now()
        )
    }

    private suspend fun resolveServerProjectId(projectId: Long): Long? {
        val project = ctx.localDataService.getProject(projectId)
        return project?.serverId
    }
}
