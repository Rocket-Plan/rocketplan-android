package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class CrmBusinessDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val address1: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerializedName("postal_code")
    val postalCode: String? = null,
    val country: String? = null,
    val description: String? = null,
    @SerializedName("custom_fields")
    val customFields: List<CrmCustomFieldValueDto>? = null,
    @SerializedName("date_added")
    val dateAdded: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class CrmBusinessRequest(
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val address1: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerializedName("postal_code")
    val postalCode: String? = null,
    val country: String? = null,
    val description: String? = null
)
