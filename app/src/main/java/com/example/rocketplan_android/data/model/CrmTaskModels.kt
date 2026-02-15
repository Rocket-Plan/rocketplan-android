package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class CrmTaskDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    @SerializedName("contact_id")
    val contactId: String? = null,
    @SerializedName("opportunity_id")
    val opportunityId: String? = null,
    @SerializedName("assigned_to")
    val assignedTo: String? = null,
    val title: String? = null,
    val description: String? = null,
    @SerializedName("due_date")
    val dueDate: String? = null,
    @SerializedName("is_completed")
    val isCompleted: Boolean? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class CrmTaskRequest(
    @SerializedName("contact_id")
    val contactId: String,
    val title: String,
    @SerializedName("due_date")
    val dueDate: String,
    val description: String? = null,
    @SerializedName("is_completed")
    val isCompleted: Boolean? = null,
    @SerializedName("opportunity_id")
    val opportunityId: String? = null
)

data class CrmTaskUpdateRequest(
    val title: String? = null,
    val description: String? = null,
    @SerializedName("due_date")
    val dueDate: String? = null,
    @SerializedName("is_completed")
    val isCompleted: Boolean? = null
)
