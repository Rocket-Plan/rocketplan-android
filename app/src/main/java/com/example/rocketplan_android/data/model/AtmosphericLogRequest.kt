package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class AtmosphericLogRequest(
    val uuid: String? = null,
    val date: String? = null,
    val temperature: Double? = null,
    @SerializedName("relative_humidity")
    val relativeHumidity: Double? = null,
    @SerializedName("dew_point")
    val dewPoint: Double? = null,
    val gpp: Double? = null,
    val pressure: Double? = null,
    @SerializedName("wind_speed")
    val windSpeed: Double? = null,
    @SerializedName("is_external")
    val isExternal: Boolean? = null,
    @SerializedName("is_inlet")
    val isInlet: Boolean? = null,
    @SerializedName("inlet_id")
    val inletId: Long? = null,
    @SerializedName("room_uuid")
    val roomUuid: String? = null,
    @SerializedName("project_uuid")
    val projectUuid: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)
