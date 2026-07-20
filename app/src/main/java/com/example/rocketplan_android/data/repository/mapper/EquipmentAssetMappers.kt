package com.example.rocketplan_android.data.repository.mapper

import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import com.example.rocketplan_android.data.model.offline.CheckOutEquipmentAssetRequest
import com.example.rocketplan_android.data.model.offline.DeployPlacementRequest
import com.example.rocketplan_android.data.model.offline.EquipmentAssetDto
import com.example.rocketplan_android.data.model.offline.EquipmentAssetPlacementDto
import com.example.rocketplan_android.data.model.offline.MoveEquipmentAssetRequest
import com.example.rocketplan_android.data.model.offline.RegisterEquipmentAssetRequest
import com.example.rocketplan_android.data.model.offline.UpdateEquipmentAssetRequest
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.util.UuidUtils

/**
 * RP-FR-019 — mappers for the serialized equipment subsystem. Mirrors the
 * count-based Equipment mappers (SyncEntityMappers.kt): pulled rows land as
 * SYNCED/clean; the save path adopts local identity (assetId/uuid) via
 * mergePulledRowsByServerId so a pull never duplicates an offline-created row.
 */
internal fun EquipmentAssetDto.toEntity(
    existing: OfflineEquipmentAssetEntity? = null
): OfflineEquipmentAssetEntity {
    val timestamp = now()
    return OfflineEquipmentAssetEntity(
        assetId = existing?.assetId ?: 0,
        serverId = id,
        uuid = existing?.uuid ?: uuid,
        companyId = companyId,
        catalogUuid = catalogUuid ?: existing?.catalogUuid,
        name = name ?: existing?.name,
        manufacturer = manufacturer,
        model = model,
        isStandard = isStandard ?: existing?.isStandard ?: true,
        serialNumber = serialNumber,
        assetTag = assetTag,
        status = status ?: existing?.status ?: "available",
        currentPlacementServerId = currentPlacementId,
        purchaseDate = purchaseDate,
        purchasePrice = purchasePrice,
        vendor = vendor,
        warrantyExpiresAt = warrantyExpiresAt,
        rentalDayRate = rentalDayRate,
        note = note,
        createdAt = DateUtils.parseApiDate(createdAt) ?: existing?.createdAt ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        serverUpdatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = (existing?.syncVersion ?: 0) + 1,
        isDirty = false,
        isDeleted = existing?.isDeleted ?: false
    )
}

/**
 * @param assetLocalId local PK of the parent asset (resolved by the caller).
 * @param roomLocalId local PK of the room, or null when the placement is closed.
 * @param projectLocalId local PK of the project (for grouping), or null.
 */
internal fun EquipmentAssetPlacementDto.toEntity(
    existing: OfflineEquipmentPlacementEntity? = null,
    assetLocalId: Long,
    roomLocalId: Long? = null,
    projectLocalId: Long? = null
): OfflineEquipmentPlacementEntity {
    val timestamp = now()
    return OfflineEquipmentPlacementEntity(
        placementId = existing?.placementId ?: 0,
        serverId = id,
        uuid = existing?.uuid ?: uuid,
        assetId = assetLocalId,
        roomId = roomLocalId ?: existing?.roomId,
        projectId = projectLocalId ?: existing?.projectId,
        dateIn = DateUtils.parseApiDate(dateIn),
        dateOut = DateUtils.parseApiDate(dateOut),
        placedByUserId = placedByUserId,
        note = note,
        isOpen = isOpen ?: (dateOut == null),
        createdAt = DateUtils.parseApiDate(createdAt) ?: existing?.createdAt ?: timestamp,
        updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        serverUpdatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
        lastSyncedAt = timestamp,
        syncStatus = SyncStatus.SYNCED,
        syncVersion = (existing?.syncVersion ?: 0) + 1,
        isDirty = false,
        isDeleted = existing?.isDeleted ?: false
    )
}

// ---------------------------------------------------------------------------
// Request builders (push side). Idempotency key = the local uuid.
// ---------------------------------------------------------------------------
internal fun OfflineEquipmentAssetEntity.toRegisterRequest(): RegisterEquipmentAssetRequest =
    RegisterEquipmentAssetRequest(
        // Review #6: no UUID fallback — a real catalog id must be supplied. Substituting
        // the asset uuid produced syntactically-valid but catalog-orphaned assets.
        catalogUuid = requireNotNull(catalogUuid) {
            "catalogUuid is required to register a serialized asset"
        },
        name = name ?: "Equipment",
        manufacturer = manufacturer,
        model = model,
        isStandard = isStandard,
        serialNumber = serialNumber,
        assetTag = assetTag,
        purchaseDate = purchaseDate,
        purchasePrice = purchasePrice,
        vendor = vendor,
        warrantyExpiresAt = warrantyExpiresAt,
        rentalDayRate = rentalDayRate,
        note = note,
        idempotencyKey = uuid
    )

internal fun OfflineEquipmentAssetEntity.toUpdateRequest(
    lockUpdatedAt: String
): UpdateEquipmentAssetRequest =
    UpdateEquipmentAssetRequest(
        manufacturer = manufacturer,
        model = model,
        serialNumber = serialNumber,
        assetTag = assetTag,
        vendor = vendor,
        note = note,
        // Only available/maintenance are settable via update; anything else is left unset.
        status = status.takeIf { it == "available" || it == "maintenance" },
        purchaseDate = purchaseDate,
        warrantyExpiresAt = warrantyExpiresAt,
        purchasePrice = purchasePrice,
        rentalDayRate = rentalDayRate,
        updatedAt = lockUpdatedAt
    )

// Review round-4 #1: the idempotency key is OPERATION-scoped (one fresh key per
// logical deploy/move/check-out, persisted in the queue payload and preserved
// across retries) — NOT the placement uuid, which the backend's operation ledger
// would see reused across different operations and reject with 409.
internal fun OfflineEquipmentPlacementEntity.toDeployRequest(
    roomServerId: Long,
    idempotencyKey: String
): DeployPlacementRequest =
    DeployPlacementRequest(
        roomId = roomServerId,
        dateIn = dateIn.toApiTimestamp(),
        note = note,
        idempotencyKey = idempotencyKey
    )

internal fun OfflineEquipmentPlacementEntity.toMoveRequest(
    toRoomServerId: Long,
    lockUpdatedAt: String,
    idempotencyKey: String
): MoveEquipmentAssetRequest =
    MoveEquipmentAssetRequest(
        toRoomId = toRoomServerId,
        movedAt = now().toApiTimestamp(),
        note = note,
        idempotencyKey = idempotencyKey,
        updatedAt = lockUpdatedAt
    )

internal fun OfflineEquipmentPlacementEntity.toCheckOutRequest(
    lockUpdatedAt: String,
    idempotencyKey: String
): CheckOutEquipmentAssetRequest =
    CheckOutEquipmentAssetRequest(
        dateOut = dateOut.toApiTimestamp(),
        idempotencyKey = idempotencyKey,
        updatedAt = lockUpdatedAt
    )
