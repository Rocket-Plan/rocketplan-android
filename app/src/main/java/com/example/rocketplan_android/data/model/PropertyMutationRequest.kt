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
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("damage_category")
    val damageCategory: Int? = null,
    @SerializedName("loss_class")
    val lossClass: Int? = null,
    @SerializedName("loss_date")
    val lossDate: String? = null,
    @SerializedName("call_received")
    val callReceived: String? = null,
    @SerializedName("crew_dispatched")
    val crewDispatched: String? = null,
    @SerializedName("arrived_on_site")
    val arrivedOnSite: String? = null,
    @SerializedName("damage_cause_id")
    val damageCauseId: Int? = null,
    @SerializedName("referred_by_name")
    val referredByName: String? = null,
    @SerializedName("referred_by_phone")
    val referredByPhone: String? = null,
    @SerializedName("is_platinum_agent")
    val isPlatinumAgent: Boolean? = null,
    @SerializedName("asbestos_status_id")
    val asbestosStatusId: Int? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)
