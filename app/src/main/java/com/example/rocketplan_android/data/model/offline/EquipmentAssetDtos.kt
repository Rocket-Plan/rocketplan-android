package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

/**
 * RP-FR-019 — Serialized equipment DTOs.
 *
 * Contract verified against mongoose (branch `dev`) EquipmentAssetResource /
 * EquipmentAssetPlacementResource / form-requests on 2026-07-13. See
 * docs/plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md.
 *
 * Tolerant deserialization (API Contract Discipline): every field is nullable
 * EXCEPT the keys the server always sends for a serialized asset/placement
 * (`id`, `uuid`, `company_id`/`equipment_asset_id`). Gson bypasses constructors,
 * so a missing non-null field would become a runtime null landmine.
 *
 * NOTE: `purchase_price` / `rental_day_rate` serialize as decimal STRINGS
 * ("1899.00"), not numbers — keep them String?.
 */
data class EquipmentAssetDto(
    @SerializedName("id")
    val id: Long,
    @SerializedName("uuid")
    val uuid: String,
    @SerializedName("company_id")
    val companyId: Long,
    @SerializedName("catalog_uuid")
    val catalogUuid: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("manufacturer")
    val manufacturer: String?,
    @SerializedName("model")
    val model: String?,
    @SerializedName("is_standard")
    val isStandard: Boolean?,
    @SerializedName("serial_number")
    val serialNumber: String?,
    @SerializedName("asset_tag")
    val assetTag: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("current_placement_id")
    val currentPlacementId: Long?,
    @SerializedName("purchase_date")
    val purchaseDate: String?,
    @SerializedName("purchase_price")
    val purchasePrice: String?,
    @SerializedName("vendor")
    val vendor: String?,
    @SerializedName("warranty_expires_at")
    val warrantyExpiresAt: String?,
    @SerializedName("rental_day_rate")
    val rentalDayRate: String?,
    @SerializedName("idempotency_key")
    val idempotencyKey: String?,
    @SerializedName("note")
    val note: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?,
    // Conditional (whenLoaded) relations — present only on show / some writes.
    @SerializedName("current_placement")
    val currentPlacement: EquipmentAssetPlacementDto? = null,
    @SerializedName("placements")
    val placements: List<EquipmentAssetPlacementDto>? = null
)

data class EquipmentAssetPlacementDto(
    @SerializedName("id")
    val id: Long,
    @SerializedName("uuid")
    val uuid: String,
    @SerializedName("equipment_asset_id")
    val equipmentAssetId: Long,
    @SerializedName("room_id")
    val roomId: Long?,
    @SerializedName("project_id")
    val projectId: Long?,
    @SerializedName("date_in")
    val dateIn: String?,
    @SerializedName("date_out")
    val dateOut: String?,
    @SerializedName("placed_by_user_id")
    val placedByUserId: Long?,
    @SerializedName("note")
    val note: String?,
    @SerializedName("idempotency_key")
    val idempotencyKey: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?,
    @SerializedName("is_open")
    val isOpen: Boolean?
)

// ---------------------------------------------------------------------------
// Response envelopes
//
// Singular responses are `{ "data": {...} }`. Idempotent-replay adds a boolean
// sibling flag whose KEY DIFFERS by endpoint: check-in uses `idempotency`,
// register/move/check-out use `idempotent`. Both modelled optional so a normal
// (non-replay) 201/200 still deserializes.
// ---------------------------------------------------------------------------
data class EquipmentAssetResponse(
    @SerializedName("data")
    val data: EquipmentAssetDto,
    @SerializedName("idempotent")
    val idempotent: Boolean? = null
)

data class EquipmentAssetPlacementResponse(
    @SerializedName("data")
    val data: EquipmentAssetPlacementDto,
    @SerializedName("idempotency")
    val idempotency: Boolean? = null
)

/** Company `index` — Laravel pagination. Placement/room lists use SingleDataResponse<List<..>>. */
data class EquipmentAssetPageResponse(
    @SerializedName("data")
    val data: List<EquipmentAssetDto>,
    @SerializedName("meta")
    val meta: PaginationMeta? = null
)

// ---------------------------------------------------------------------------
// Timeline (GET /companies/{c}/equipment-asset-timeline) — bespoke shape,
// paginated by project. Has `meta` but no `links`.
// ---------------------------------------------------------------------------
data class EquipmentAssetTimelineResponse(
    @SerializedName("data")
    val data: List<EquipmentAssetTimelineEntryDto>,
    @SerializedName("meta")
    val meta: PaginationMeta? = null
)

data class EquipmentAssetTimelineEntryDto(
    @SerializedName("project")
    val project: TimelineProjectDto?,
    @SerializedName("bars")
    val bars: List<TimelineBarDto>?
)

data class TimelineProjectDto(
    @SerializedName("id")
    val id: Long,
    @SerializedName("uid")
    val uid: String?,
    @SerializedName("address")
    val address: String?
)

data class TimelineBarDto(
    @SerializedName("placement_id")
    val placementId: Long,
    @SerializedName("asset")
    val asset: TimelineBarAssetDto?,
    @SerializedName("room")
    val room: TimelineBarRoomDto?,
    @SerializedName("date_in")
    val dateIn: String?,
    @SerializedName("date_out")
    val dateOut: String?,
    @SerializedName("is_open")
    val isOpen: Boolean?
)

data class TimelineBarAssetDto(
    @SerializedName("id")
    val id: Long,
    @SerializedName("uuid")
    val uuid: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("serial_number")
    val serialNumber: String?,
    @SerializedName("asset_tag")
    val assetTag: String?
)

data class TimelineBarRoomDto(
    @SerializedName("id")
    val id: Long,
    @SerializedName("name")
    val name: String?
)

/**
 * A row from the company equipment CATALOG (GET /companies/{c}/equipment) — the source
 * of the `catalog_uuid` required to register a serialized asset. `catalog_uuid` is the
 * stable logical catalog identity (EquipmentTypes::uuidFor(name) server-side).
 */
data class EquipmentCatalogItemDto(
    @SerializedName("id")
    val id: Long?,
    @SerializedName("catalog_uuid")
    val catalogUuid: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("display_name")
    val displayName: String?,
    @SerializedName("is_standard")
    val isStandard: Boolean?
)

// ---------------------------------------------------------------------------
// Request bodies
// ---------------------------------------------------------------------------
data class RegisterEquipmentAssetRequest(
    @SerializedName("catalog_uuid")
    val catalogUuid: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("manufacturer")
    val manufacturer: String? = null,
    @SerializedName("model")
    val model: String? = null,
    @SerializedName("is_standard")
    val isStandard: Boolean? = null,
    @SerializedName("serial_number")
    val serialNumber: String? = null,
    @SerializedName("asset_tag")
    val assetTag: String? = null,
    @SerializedName("purchase_date")
    val purchaseDate: String? = null,
    @SerializedName("purchase_price")
    val purchasePrice: String? = null,
    @SerializedName("vendor")
    val vendor: String? = null,
    @SerializedName("warranty_expires_at")
    val warrantyExpiresAt: String? = null,
    @SerializedName("rental_day_rate")
    val rentalDayRate: String? = null,
    @SerializedName("note")
    val note: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null
)

data class UpdateEquipmentAssetRequest(
    @SerializedName("manufacturer")
    val manufacturer: String? = null,
    @SerializedName("model")
    val model: String? = null,
    @SerializedName("serial_number")
    val serialNumber: String? = null,
    @SerializedName("asset_tag")
    val assetTag: String? = null,
    @SerializedName("vendor")
    val vendor: String? = null,
    @SerializedName("note")
    val note: String? = null,
    /** Only `available` / `maintenance` are settable here (server `in:` rule). */
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("purchase_date")
    val purchaseDate: String? = null,
    @SerializedName("warranty_expires_at")
    val warrantyExpiresAt: String? = null,
    @SerializedName("purchase_price")
    val purchasePrice: String? = null,
    @SerializedName("rental_day_rate")
    val rentalDayRate: String? = null,
    /** Required optimistic lock. */
    @SerializedName("updated_at")
    val updatedAt: String
)

/** Deploy / check-in a unit into a room. `date_out` is prohibited by the server. */
data class DeployPlacementRequest(
    @SerializedName("room_id")
    val roomId: Long,
    @SerializedName("date_in")
    val dateIn: String? = null,
    @SerializedName("note")
    val note: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null
)

data class MoveEquipmentAssetRequest(
    @SerializedName("to_room_id")
    val toRoomId: Long,
    @SerializedName("moved_at")
    val movedAt: String? = null,
    @SerializedName("note")
    val note: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    /** Required optimistic lock. */
    @SerializedName("updated_at")
    val updatedAt: String
)

/**
 * RP-FR-030 — correct a placement's dates. At least one of `date_in`/`date_out` must be
 * present (server 422s otherwise). `updated_at` is the optimistic lock on the PLACEMENT's
 * own `updated_at` (NOT the asset's — differs from move/check-out). Dates are sent date-only
 * (UTC, `yyyy-MM-dd`) so a correction never shifts by a day across timezones (mirrors iOS
 * RP-BUG-345). Explicit @SerializedName on every field (RP-CD-006).
 */
data class CorrectPlacementRequest(
    @SerializedName("date_in")
    val dateIn: String? = null,
    @SerializedName("date_out")
    val dateOut: String? = null,
    /** Required optimistic lock — the placement's own updated_at. */
    @SerializedName("updated_at")
    val updatedAt: String,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null
)

data class CheckOutEquipmentAssetRequest(
    @SerializedName("date_out")
    val dateOut: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    /** Required optimistic lock. */
    @SerializedName("updated_at")
    val updatedAt: String
)
