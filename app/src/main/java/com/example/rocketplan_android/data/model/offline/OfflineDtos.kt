package com.example.rocketplan_android.data.model.offline

import com.example.rocketplan_android.data.model.DamageCauseDto
import com.example.rocketplan_android.data.model.DamageTypeDto
import com.google.gson.annotations.SerializedName

data class PaginatedResponse<T>(
    val data: List<T>,
    val links: PaginationLinks? = null,
    val meta: PaginationMeta? = null
)

/**
 * Flexible response wrapper that handles APIs returning either an array or single object for `data`.
 * Use with Gson's JsonElement to manually parse the ambiguous field.
 */
data class FlexibleDataResponse(
    val data: com.google.gson.JsonElement?,
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

/**
 * Response from /api/status endpoint for backend health checks.
 */
data class StatusResponse(
    val status: String? = null,
    val service: String? = null,
    val timestamp: String? = null,
    val version: String? = null
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
    val properties: List<PropertyDto>? = null,
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
    val properties: List<PropertyDto>? = null,
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
    val body: String,
    @SerializedName("photo_id")
    val photoId: Long? = null,
    @SerializedName("category_id")
    val categoryId: Long? = null,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class CreateNoteRequest(
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long? = null,
    val body: String,
    @SerializedName("photo_id")
    val photoId: Long? = null,
    @SerializedName("category_id")
    val categoryId: Long? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class DeleteWithTimestampRequest(
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class EquipmentRequest(
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("room_id")
    val roomId: Long? = null,
    @SerializedName("name")
    val type: String,
    val brand: String? = null,
    val model: String? = null,
    @SerializedName("serial_number")
    val serialNumber: String? = null,
    val quantity: Int = 1,
    val status: String,
    @SerializedName("start_date")
    val startDate: String? = null,
    @SerializedName("end_date")
    val endDate: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class DamageMaterialRequest(
    val name: String,
    @SerializedName("damage_type_id")
    val damageTypeId: Long,
    val description: String? = null,
    @SerializedName("drying_goal")
    val dryingGoal: Double? = null,
    val quantity: Double? = null,
    @SerializedName("unit_of_measurement")
    val unitOfMeasurement: Long? = null,
    val action: Long? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class MoistureLogRequest(
    val reading: Double? = null,
    val removed: Boolean? = null,
    val location: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
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
    @SerializedName("is_residential")
    val isResidential: Boolean? = null,
    @SerializedName("is_commercial")
    val isCommercial: Boolean? = null,
    @SerializedName("is_multi_unit")
    val isMultiUnit: Boolean? = null,
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
    val asbestosStatusId: Long? = null,
    @SerializedName("asbestos_status")
    val asbestosStatus: PropertyDataDto? = null,
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
    @SerializedName("damage_cause")
    val damageCause: DamageCauseDto? = null,
    @SerializedName("property_damage_types")
    val propertyDamageTypes: List<DamageTypeDto>? = null,
    @SerializedName("property_type_id")
    val propertyTypeId: Long? = null,
    @SerializedName("property_type")
    val propertyTypeData: PropertyTypeDto? = null,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?,
    /** Manual override for property type name; defaults to propertyTypeData.name if not set */
    @Transient
    val propertyType: String? = null
) {
    /** Resolved property type name from either explicit value or API object */
    fun resolvedPropertyType(): String? = propertyType ?: propertyTypeData?.name
}

data class PropertyTypeDto(
    val id: Long,
    val name: String?
)

data class LocationDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long? = null,
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("location_type")
    val locationType: String? = null,
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
    val name: String?,  // Human-friendly room name from API
    val title: String?,  // Made nullable - API doesn't always return title
    @SerializedName("type_occurrence")
    val typeOccurrence: Int?,
    @SerializedName("room_type")
    val roomType: RoomTypeDto?,  // Changed from String? to RoomTypeDto? to handle eager-loaded relationship
    val level: LocationDto?,  // Changed from String? to LocationDto? to handle eager-loaded relationship
    @SerializedName("square_footage")
    val squareFootage: Double?,
    @SerializedName("is_accessible")
    val isAccessible: Boolean?,
    @SerializedName("photos_count")
    val photosCount: Int? = null,
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    val thumbnail: PhotoDto? = null,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class RoomTypeDto(
    val id: Long,
    val name: String?,
    val type: String?,
    @SerializedName("is_standard")
    val isStandard: Boolean?
)

data class PropertyDataDto(
    val id: Long? = null,
    val name: String? = null,
    val title: String? = null
)

data class AlbumDto(
    val id: Long,
    val name: String?,
    @SerializedName("albumable_type")
    val albumableType: String?,
    @SerializedName("albumable_id")
    val albumableId: Long?,
    val photos: List<PhotoDto>?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
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
    val updatedAt: String?,
    val albums: List<AlbumDto>?
)

data class PhotoSizeDto(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
    val gallery: String? = null,
    val raw: String? = null
)

data class RoomPhotoDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("relation_uuid")
    val relationUuid: String? = null,
    @SerializedName("is_ir")
    val isIr: Boolean? = null,
    @SerializedName("is_flagged")
    val isFlagged: Boolean? = null,
    @SerializedName("is_bookmarked")
    val isBookmarked: Boolean? = null,
    @SerializedName("s3_key")
    val s3Key: String? = null,
    val bucket: String? = null,
    @SerializedName("file_name")
    val fileName: String? = null,
    @SerializedName("file_extension")
    val fileExtension: String? = null,
    @SerializedName("content_type")
    val contentType: String? = null,
    val sizes: RoomPhotoSizeDto? = null,
    @SerializedName("photoable_type")
    val photoableType: String? = null,
    @SerializedName("photoable_id")
    val photoableId: Long? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    val albums: List<AlbumDto>? = null,
    val photo: RoomPhotoFileDto? = null
)

data class RoomPhotoSizeDto(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
    val gallery: String? = null,
    val raw: String? = null
)

data class RoomPhotoFileDto(
    val id: Long?,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long?,
    @SerializedName("room_id")
    val roomId: Long?,
    @SerializedName("log_id")
    val logId: Long? = null,
    @SerializedName("moisture_log_id")
    val moistureLogId: Long? = null,
    @SerializedName("file_name")
    val fileName: String?,
    @SerializedName("local_path")
    val localPath: String? = null,
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
    val updatedAt: String?,
    val albums: List<AlbumDto>? = null
)

data class ProjectPhotoListingDto(
    val id: Long,
    val uuid: String? = null,
    @SerializedName("project_id")
    val projectId: Long? = null,
    @SerializedName("room_id")
    val roomId: Long? = null,
    @SerializedName("location_id")
    val locationId: Long? = null,
    @SerializedName("unit_id")
    val unitId: Long? = null,
    @SerializedName("file_name")
    val fileName: String? = null,
    @SerializedName("content_type")
    val contentType: String? = null,
    val sizes: PhotoSizeDto? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
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
    @SerializedName("damage_material")
    val damageMaterial: DamageMaterialDto? = null,
    val date: String?,
    @SerializedName("moisture_content")
    val moistureContent: Double? = null,
    @SerializedName("reading")
    val reading: Double? = null,
    @SerializedName("removed")
    val removed: Boolean? = null,
    val location: String? = null,
    val depth: String? = null,
    @SerializedName("photo_url")
    val photoUrl: String? = null,
    @SerializedName("photo_local_path")
    val photoLocalPath: String? = null,
    @SerializedName("photo_upload_status")
    val photoUploadStatus: String? = null,
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
    @SerializedName("damage_type_id")
    val damageTypeId: Long? = null,
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
    @SerializedName("tab_name")
    val tabName: String? = null,
    val category: String? = null,
    @SerializedName("code_part_1")
    val codePart1: String? = null,
    @SerializedName("code_part_2")
    val codePart2: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val rate: String? = null,
    @SerializedName("line_total")
    val lineTotal: String? = null,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("updated_at")
    val updatedAt: String?
)

data class WorkScopeSheetDto(
    val id: Long,
    @SerializedName("tab_name")
    val tabName: String,
    @SerializedName("work_scope_items")
    val workScopeItems: List<WorkScopeCatalogItemDto>
)

data class WorkScopeCatalogItemDto(
    val id: Long,
    val category: String,
    @SerializedName("code_part_1")
    val codePart1: String?,
    @SerializedName("code_part_2")
    val codePart2: String?,
    val description: String,
    val unit: String,
    val rate: String?
)

data class WorkScopeItemRequest(
    @SerializedName("sheet_id")
    val sheetId: Long,
    val description: String,
    val quantity: Double,
    val category: String,
    @SerializedName("code_part_1")
    val codePart1: String,
    @SerializedName("code_part_2")
    val codePart2: String,
    val unit: String,
    val rate: Double
)

data class AddWorkScopeItemsRequest(
    @SerializedName("selected_items")
    val selectedItems: List<WorkScopeItemRequest>
)

// ============================================================================
// Support DTOs
// ============================================================================

data class SupportCategoryDto(
    val id: Long,
    val name: String?,
    val description: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?
)

data class SupportConversationDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("user_id") val userId: Long?,
    @SerializedName("category_id") val categoryId: Long?,
    val subject: String?,
    val status: String?,  // "open", "closed"
    @SerializedName("unread_count") val unreadCount: Int?,
    @SerializedName("last_message_at") val lastMessageAt: String?,
    val category: SupportCategoryDto?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?
)

data class SupportMessageDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("conversation_id") val conversationId: Long?,
    @SerializedName("sender_id") val senderId: Long?,
    @SerializedName("sender_type") val senderType: String?,  // "user", "admin"
    val body: String?,
    @SerializedName("is_read") val isRead: Boolean?,
    val attachments: List<SupportMessageAttachmentDto>?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?
)

data class SupportMessageAttachmentDto(
    val id: Long,
    @SerializedName("message_id") val messageId: Long?,
    @SerializedName("file_name") val fileName: String?,
    @SerializedName("file_url") val fileUrl: String?,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("mime_type") val mimeType: String?
)

data class CreateSupportConversationRequest(
    @SerializedName("category_id") val categoryId: Long,
    val subject: String,
    val body: String,
    @SerializedName("idempotency_key") val idempotencyKey: String? = null
)

data class CreateSupportMessageRequest(
    val body: String,
    @SerializedName("idempotency_key") val idempotencyKey: String? = null
)
