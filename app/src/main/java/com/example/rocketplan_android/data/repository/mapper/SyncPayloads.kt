package com.example.rocketplan_android.data.repository.mapper

import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.PropertyMutationRequest

/**
 * Payload data classes used for serializing/deserializing pending sync operations.
 * Extracted from OfflineSyncRepository to reduce file size and improve maintainability.
 */

internal data class PendingProjectCreationPayload(
    val localProjectId: Long,
    val projectUuid: String,
    val companyId: Long,
    val projectStatusId: Int,
    val addressRequest: CreateAddressRequest,
    val idempotencyKey: String?
)

internal data class PendingPropertyCreationPayload(
    val localPropertyId: Long,
    val propertyUuid: String,
    val projectId: Long,
    val propertyTypeId: Int,
    val propertyTypeValue: String?,
    val idempotencyKey: String?
)

internal data class PendingPropertyUpdatePayload(
    val projectId: Long,
    val propertyId: Long,
    val request: PropertyMutationRequest,
    val propertyTypeValue: String?,
    val lockUpdatedAt: String?
)

internal data class PendingLockPayload(
    val lockUpdatedAt: String?
)

internal data class PendingLocationCreationPayload(
    val localLocationId: Long,
    val locationUuid: String,
    val projectId: Long,
    val propertyLocalId: Long,
    val locationName: String,
    val locationTypeId: Long,
    val type: String,
    val floorNumber: Int,
    val isCommon: Boolean,
    val isAccessible: Boolean,
    val isCommercial: Boolean,
    val idempotencyKey: String?
)

internal data class PendingRoomCreationPayload(
    val localRoomId: Long,
    val roomUuid: String?,
    val projectId: Long,
    val roomName: String,
    val roomTypeId: Long,
    val roomTypeName: String?,
    val isSource: Boolean,
    val isExterior: Boolean,
    val levelServerId: Long?,
    val locationServerId: Long?,
    val levelUuid: String,      // UUID-based resolution (simpler than local IDs)
    val locationUuid: String,   // UUID-based resolution (simpler than local IDs)
    val idempotencyKey: String?
)

internal data class PendingLocationUpdatePayload(
    val locationId: Long,
    val locationUuid: String,
    val name: String?,
    val floorNumber: Int?,
    val isAccessible: Boolean?,
    val lockUpdatedAt: String?
)

internal data class PendingRoomUpdatePayload(
    val roomId: Long,
    val roomUuid: String?,
    val projectId: Long,
    val locationId: Long?,
    val isSource: Boolean,
    val levelId: Long?,
    val roomTypeId: Long?,
    val lockUpdatedAt: String?
)

internal data class PendingEquipmentMovePayload(
    val pivotServerId: Long?,
    val pivotLocalId: Long,
    val toRoomId: Long?,
    val toRoomUuid: String?,
    val quantity: Int?,
    val movedAt: String,
    val note: String?,
    val idempotencyKey: String,
    val lockUpdatedAt: String?
)

internal data class PendingEquipmentTransferPayload(
    val pivotServerId: Long?,
    val pivotLocalId: Long,
    val toRoomId: Long?,
    val toRoomUuid: String?,
    // Server project ID. The UI stores project.serverId here; retain the JSON field name
    // for already-queued operations, but do not resolve it as a local project primary key.
    val toProjectId: Long,
    val quantity: Int,
    val movedAt: String,
    val note: String? = null,
    val idempotencyKey: String,
    val lockUpdatedAt: String?
)

internal data class PendingAtmosphericLogCreationPayload(
    val localLogId: Long,
    val logUuid: String,
    val projectId: Long,
    val projectUuid: String?,
    val roomId: Long?,
    val roomUuid: String?,
    val idempotencyKey: String?
)

// ============================================================================
// Support Payloads
// ============================================================================

// ============================================================================
// Crew (Project-User) Payloads
// ============================================================================

internal data class PendingProjectUserPayload(
    val projectServerId: Long,
    val userServerId: Long
)

internal data class PendingSupportConversationPayload(
    val localConversationId: Long,
    val conversationUuid: String,
    val categoryId: Long,
    val subject: String,
    val initialMessageBody: String,
    val idempotencyKey: String
)

internal data class PendingSupportMessagePayload(
    val localMessageId: Long,
    val messageUuid: String,
    val conversationId: Long,
    val conversationServerId: Long?,
    val body: String,
    val idempotencyKey: String
)
