package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class UpdateRoomRequest(
    @SerializedName("is_source")
    val isSource: Boolean? = null,
    @SerializedName("level_id")
    val levelId: Long? = null,
    @SerializedName("room_type_id")
    val roomTypeId: Long? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)
