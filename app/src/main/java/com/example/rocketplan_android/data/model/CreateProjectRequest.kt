package com.example.rocketplan_android.data.model

import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.google.gson.annotations.SerializedName

data class CreateAddressRequest(
    val address: String,
    @SerializedName("address_2") val address2: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerializedName("zip") val zip: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class CreateCompanyProjectRequest(
    @SerializedName("project_status_id") val projectStatusId: Int,
    @SerializedName("address_id") val addressId: Long,
    val alias: String? = null,
    @SerializedName("opportunity_id") val opportunityId: String? = null,
    @SerializedName("project_type_id") val projectTypeId: Int? = null
)

data class UpdateProjectRequest(
    val alias: String? = null,
    @SerializedName("project_status_id") val projectStatusId: Int? = null
)

data class SingleResourceResponse<T>(
    val data: T
)

typealias AddressResourceResponse = SingleResourceResponse<ProjectAddressDto>
typealias ProjectResourceResponse = SingleResourceResponse<ProjectDto>
typealias ProjectDetailResourceResponse = SingleResourceResponse<ProjectDetailDto>
typealias NoteResourceResponse = SingleResourceResponse<NoteDto>
typealias PropertyResourceResponse = SingleResourceResponse<PropertyDto>
