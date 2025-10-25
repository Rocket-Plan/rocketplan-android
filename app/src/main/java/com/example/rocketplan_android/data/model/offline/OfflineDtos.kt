package com.example.rocketplan_android.data.model.offline

import com.google.gson.annotations.SerializedName

data class PaginatedResponse<T>(
    val data: List<T>,
    val links: PaginationLinks? = null,
    val meta: PaginationMeta? = null
)

data class PaginationLinks(
    val first: String?,
    val last: String?,
    val prev: String?,
    val next: String?
)

data class PaginationMeta(
    @SerializedName("current_page")
    val currentPage: Int?,
    @SerializedName("last_page")
    val lastPage: Int?,
    @SerializedName("per_page")
    val perPage: Int?,
    val total: Int?
)

data class ProjectDto(
    val id: Long,
    val uuid: String? = null,
    @SerializedName("uid")
    val uid: String? = null,
    @SerializedName("alias")
    val alias: String? = null,
    val title: String? = null,
    @SerializedName("project_number")
    val projectNumber: String? = null,
    val status: String? = null,
    @SerializedName("property_type")
    val propertyType: String? = null,
    @SerializedName("company_id")
    val companyId: Long? = null,
    @SerializedName("property_id")
    val propertyId: Long? = null,
    val address: ProjectAddressDto? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class ProjectDetailDto(
    val id: Long,
    val uuid: String? = null,
    @SerializedName("uid")
    val uid: String? = null,
    @SerializedName("alias")
    val alias: String? = null,
    val title: String? = null,
    @SerializedName("project_number")
    val projectNumber: String? = null,
    val status: String? = null,
    @SerializedName("property_type")
    val propertyType: String? = null,
    @SerializedName("company_id")
    val companyId: Long? = null,
    @SerializedName("property_id")
    val propertyId: Long? = null,
    val address: ProjectAddressDto? = null,
    val notes: List<NoteDto>? = null,
    val users: List<UserDto>? = null,
    val locations: List<LocationDto>? = null,
    val rooms: List<RoomDto>? = null,
    val photos: List<PhotoDto>? = null,
    val atmosphericLogs: List<AtmosphericLogDto>? = null,
    val moistureLogs: List<MoistureLogDto>? = null,
    val equipment: List<EquipmentDto>? = null,
    val damages: List<DamageMaterialDto>? = null,
    val workScopes: List<WorkScopeDto>? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class ProjectAddressDto(
    val id: Long? = null,
    val address: String? = null,
    @SerializedName("address_2")
    val address2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val zip: String? = null,
    val country: String? = null,
    val latitude: String? = null,
    val longitude: String? = null
)

data class UserDto(
    val id: Long,
    val uuid: String?,
    val email: String,
    @SerializedName("first_name")
    val firstName: String?,
    @SerializedName("last_name")
    val lastName: String?,
    val role: String?,
    @SerializedName("company_id")
    val companyId: Long?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class NoteDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long?,
    @SerializedName("user_id")
    val userId: Long?,
    val content: String,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class NoteableDto(
    val id: Long,
    val uuid: String?,
    val type: String,
    val name: String?
)

data class PropertyDto(
    val id: Long,
    val uuid: String?,
    val address: String?,
    val city: String?,
    val state: String?,
    @SerializedName("postal_code")
    val postalCode: String?,
    val latitude: Double?,
    val longitude: Double?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class LocationDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    val title: String,
    val type: String,
    @SerializedName("parent_location_id")
    val parentLocationId: Long?,
    @SerializedName("is_accessible")
    val isAccessible: Boolean?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class RoomDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("location_id")
    val locationId: Long?,
    val title: String,
    @SerializedName("room_type")
    val roomType: String?,
    val level: String?,
    @SerializedName("square_footage")
    val squareFootage: Double?,
    @SerializedName("is_accessible")
    val isAccessible: Boolean?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class AlbumDto(
    val id: Long,
    val name: String?
)

data class PhotoDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long?,
    @SerializedName("log_id")
    val logId: Long?,
    @SerializedName("moisture_log_id")
    val moistureLogId: Long?,
    @SerializedName("file_name")
    val fileName: String?,
    @SerializedName("local_path")
    val localPath: String?,
    @SerializedName("remote_url")
    val remoteUrl: String?,
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String?,
    @SerializedName("assembly_id")
    val assemblyId: String?,
    @SerializedName("tus_upload_id")
    val tusUploadId: String?,
    @SerializedName("file_size")
    val fileSize: Long?,
    val width: Int?,
    val height: Int?,
    @SerializedName("mime_type")
    val mimeType: String?,
    @SerializedName("captured_at")
    val capturedAt: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class AtmosphericLogDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long?,
    val date: String?,
    @SerializedName("relative_humidity")
    val relativeHumidity: Double?,
    val temperature: Double?,
    @SerializedName("dew_point")
    val dewPoint: Double?,
    val gpp: Double?,
    val pressure: Double?,
    @SerializedName("wind_speed")
    val windSpeed: Double?,
    @SerializedName("is_external")
    val isExternal: Boolean?,
    @SerializedName("is_inlet")
    val isInlet: Boolean?,
    @SerializedName("inlet_id")
    val inletId: Long?,
    @SerializedName("outlet_id")
    val outletId: Long?,
    @SerializedName("photo_url")
    val photoUrl: String?,
    @SerializedName("photo_local_path")
    val photoLocalPath: String?,
    @SerializedName("photo_upload_status")
    val photoUploadStatus: String?,
    @SerializedName("photo_assembly_id")
    val photoAssemblyId: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class MoistureLogDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long,
    @SerializedName("material_id")
    val materialId: Long?,
    val date: String?,
    @SerializedName("moisture_content")
    val moistureContent: Double?,
    val location: String?,
    val depth: String?,
    @SerializedName("photo_url")
    val photoUrl: String?,
    @SerializedName("photo_local_path")
    val photoLocalPath: String?,
    @SerializedName("photo_upload_status")
    val photoUploadStatus: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class EquipmentDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long?,
    val type: String?,
    val brand: String?,
    val model: String?,
    @SerializedName("serial_number")
    val serialNumber: String?,
    val quantity: Int?,
    val status: String?,
    @SerializedName("start_date")
    val startDate: String?,
    @SerializedName("end_date")
    val endDate: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class DamageMaterialDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long?,
    @SerializedName("room_id")
    val roomId: Long?,
    val title: String?,
    val description: String?,
    val severity: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class WorkScopeDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long?,
    val name: String?,
    val description: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)
