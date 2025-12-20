package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class CreateLocationRequest(
    val name: String,
    @SerializedName("floor_number")
    val floorNumber: Int? = null,
    @SerializedName("location_type_id")
    val locationTypeId: Long,
    @SerializedName("is_common")
    val isCommon: Boolean = true,
    @SerializedName("is_accessible")
    val isAccessible: Boolean = true,
    @SerializedName("is_commercial")
    val isCommercial: Boolean = false,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null
)
