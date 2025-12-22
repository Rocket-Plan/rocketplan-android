package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

data class OfflineRoomTypeCatalogResponse(
    val version: String?,
    @SerializedName("property_types")
    val propertyTypes: List<OfflinePropertyTypeDto> = emptyList(),
    val levels: List<OfflineLevelDto> = emptyList(),
    @SerializedName("room_types")
    val roomTypes: List<OfflineRoomTypeCatalogItemDto> = emptyList()
)

data class OfflinePropertyTypeDto(
    val id: Long,
    val name: String?,
    @SerializedName("sort_order")
    val sortOrder: Int?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class OfflineLevelDto(
    val id: Long,
    val name: String?,
    val type: String?, // "internal" or "external"
    @SerializedName("is_default")
    val isDefault: Boolean?,
    @SerializedName("is_standard")
    val isStandard: Boolean?,
    @SerializedName("property_type_ids")
    val propertyTypeIds: List<Long> = emptyList(),
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class OfflineRoomTypeCatalogItemDto(
    val id: Long,
    val name: String?,
    val type: String?, // e.g., "unit", "external"
    @SerializedName("is_standard")
    val isStandard: Boolean?,
    @SerializedName("is_default")
    val isDefault: Boolean?,
    @SerializedName("level_ids")
    val levelIds: List<Long> = emptyList(),
    @SerializedName("property_type_ids")
    val propertyTypeIds: List<Long> = emptyList(),
    @SerializedName("updated_at")
    val updatedAt: String?
)
