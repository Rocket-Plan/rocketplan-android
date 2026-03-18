package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class AppVersionResponse(
    @SerializedName("update_available")
    val updateAvailable: Boolean = false,
    @SerializedName("must_update")
    val mustUpdate: Boolean = false,
    @SerializedName("show_modal")
    val showModal: Boolean = false,
    @SerializedName("flavor_disabled")
    val flavorDisabled: Boolean = false,
    @SerializedName("flavor_message")
    val flavorMessage: String? = null
)
