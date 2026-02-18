package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class GhlMeResponse(
    val connected: Boolean = false,
    @SerializedName("ghl_user_id") val ghlUserId: String? = null,
    val name: String? = null,
    val email: String? = null,
    val role: String? = null,
    val permissions: Map<String, Any>? = null
)

data class CrmUserDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    @SerializedName("first_name")
    val firstName: String? = null,
    @SerializedName("last_name")
    val lastName: String? = null,
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val extension: String? = null,
    val role: String? = null,
    val type: String? = null,
    @SerializedName("synced_at")
    val syncedAt: String? = null
)
