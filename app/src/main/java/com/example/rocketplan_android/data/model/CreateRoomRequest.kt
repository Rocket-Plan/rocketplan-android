package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class CreateRoomRequest(
    val name: String,
    val uuid: String? = null,
    @SerializedName("room_type_id")
    val roomTypeId: Long,
    @SerializedName("level_id")
    val levelId: Long? = null,
    @SerializedName("level_uuid")
    val levelUuid: String? = null,
    @SerializedName("location_uuid")
    val locationUuid: String? = null,
    @SerializedName("is_source")
    val isSource: Boolean = false,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null
)
