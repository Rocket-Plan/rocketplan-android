package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

/**
 * Request body for creating or updating a property.
 * All fields besides propertyTypeId are optional and default to null so that we only send
 * the data we actually know on Android today.
 */
data class PropertyMutationRequest(
    @SerializedName("property_type_id")
    val propertyTypeId: Int,
    @SerializedName("is_commercial")
    val isCommercial: Boolean? = null,
    @SerializedName("is_residential")
    val isResidential: Boolean? = null,
    @SerializedName("year_built")
    val yearBuilt: Int? = null,
    val name: String? = null,
    @SerializedName("referred_by_name")
    val referredByName: String? = null,
    @SerializedName("referred_by_phone")
    val referredByPhone: String? = null,
    @SerializedName("is_platinum_agent")
    val isPlatinumAgent: Boolean? = null,
    @SerializedName("asbestos_status_id")
    val asbestosStatusId: Int? = null
)
