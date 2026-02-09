package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

/**
 * DTO for timecard data from the API.
 */
data class TimecardDto(
    val id: Long,
    val uuid: String? = null,
    @SerializedName("user_id")
    val userId: Long,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("company_id")
    val companyId: Long? = null,
    @SerializedName("timecard_type_id")
    val timecardTypeId: Int = 1,
    @SerializedName("time_in")
    val timeIn: String,
    @SerializedName("time_out")
    val timeOut: String? = null,
    val elapsed: Long? = null,
    val notes: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    val project: ProjectDto? = null,
    val user: UserDto? = null,
    @SerializedName("timecard_type")
    val timecardType: TimecardTypeDto? = null
)

/**
 * DTO for timecard type reference data.
 */
data class TimecardTypeDto(
    val id: Int,
    val name: String,
    val description: String? = null
)

/**
 * Request body for creating a new timecard (clock in).
 */
data class CreateTimecardRequest(
    @SerializedName("time_in")
    val timeIn: String,
    @SerializedName("time_out")
    val timeOut: String? = null,
    val elapsed: Long? = null,
    @SerializedName("timecard_type_id")
    val timecardTypeId: Int = 1,
    val uuid: String,
    val notes: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null
)

/**
 * Request body for updating an existing timecard (clock out or edit).
 */
data class UpdateTimecardRequest(
    @SerializedName("time_in")
    val timeIn: String? = null,
    @SerializedName("time_out")
    val timeOut: String? = null,
    @SerializedName("timecard_type_id")
    val timecardTypeId: Int? = null,
    val notes: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)
