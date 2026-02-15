package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class CrmNoteDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    @SerializedName("contact_id")
    val contactId: String? = null,
    val body: String? = null,
    @SerializedName("body_text")
    val bodyText: String? = null,
    @SerializedName("user_id")
    val userId: String? = null,
    @SerializedName("date_added")
    val dateAdded: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class CrmNoteRequest(
    @SerializedName("contact_id")
    val contactId: String,
    val body: String
)

data class CrmNoteUpdateRequest(
    val body: String
)
