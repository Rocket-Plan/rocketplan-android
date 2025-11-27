package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class CreateRoomRequest(
    val name: String,
    @SerializedName("room_type_id")
    val roomTypeId: Long,
    @SerializedName("level_id")
    val levelId: Long,
    @SerializedName("is_source")
    val isSource: Boolean = false
)
