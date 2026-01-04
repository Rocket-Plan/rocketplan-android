package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class UpdateLocationRequest(
    val name: String? = null,
    @SerializedName("floor_number")
    val floorNumber: Int? = null,
    @SerializedName("is_accessible")
    val isAccessible: Boolean? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)
