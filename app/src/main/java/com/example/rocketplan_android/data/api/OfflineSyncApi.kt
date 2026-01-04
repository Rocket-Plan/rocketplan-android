package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.ClaimDto
import com.example.rocketplan_android.data.model.ClaimMutationRequest
import com.example.rocketplan_android.data.model.DamageCauseDto
import com.example.rocketplan_android.data.model.DamageTypeDto
import com.example.rocketplan_android.data.model.FeatureFlagResponse
import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.CreateNoteRequest
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialRequest
import com.example.rocketplan_android.data.model.offline.DeletedRecordsResponse
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.EquipmentRequest
import com.example.rocketplan_android.data.model.CreateLocationRequest
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.MoistureLogRequest
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.NoteableDto
import com.example.rocketplan_android.data.model.offline.FlexibleDataResponse
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.ProjectDetailResourceResponse
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.AddressResourceResponse
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.CreateCompanyProjectRequest
import com.example.rocketplan_android.data.model.RoomResourceResponse
import com.example.rocketplan_android.data.model.NoteResourceResponse
import com.example.rocketplan_android.data.model.DeleteProjectRequest
import com.example.rocketplan_android.data.model.PropertyResourceResponse
import com.example.rocketplan_android.data.model.ProjectResourceResponse
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.UpdateProjectRequest
import com.example.rocketplan_android.data.model.CreateRoomRequest
import com.example.rocketplan_android.data.model.UpdateLocationRequest
import com.example.rocketplan_android.data.model.UpdateRoomRequest
import com.example.rocketplan_android.data.model.AtmosphericLogRequest
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.RoomTypeDto
import com.google.gson.JsonObject
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.AddWorkScopeItemsRequest
import com.example.rocketplan_android.data.model.offline.DeleteWithTimestampRequest
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.data.model.offline.WorkScopeSheetDto
import retrofit2.Response
import com.google.gson.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import com.example.rocketplan_android.data.model.offline.RestoreRecordsRequest
import com.example.rocketplan_android.data.model.offline.RestoreRecordsResponse
import com.example.rocketplan_android.data.model.offline.OfflineRoomTypeCatalogResponse

interface OfflineSyncApi {

    @GET("/api/offline-room-types")
    suspend fun getOfflineRoomTypes(): OfflineRoomTypeCatalogResponse

    // Projects
    @GET("/api/companies/{companyId}/projects")
    suspend fun getCompanyProjects(
        @Path("companyId") companyId: Long,
        @Query("page") page: Int? = null,
        @Query("filter[updated_date]") updatedSince: String? = null,
        @Query("filter[assigned_to_me]") assignedToMe: String? = null
    ): PaginatedResponse<ProjectDto>

    @GET("/api/users/{userId}/projects")
    suspend fun getUserProjects(
        @Path("userId") userId: Long,
        @Query("page") page: Int? = null,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<ProjectDto>

    @GET("/api/projects/{projectId}")
    suspend fun getProjectDetail(
        @Path("projectId") projectId: Long,
        @Query("include") include: String? = "projectStatus,address,projectType,properties,notes,atmosphericLogs"
    ): ProjectDetailResourceResponse

    @HTTP(method = "DELETE", path = "/api/projects/{projectId}", hasBody = true)
    suspend fun deleteProject(
        @Path("projectId") projectId: Long,
        @Body body: DeleteProjectRequest
    ): Response<Unit>

    @GET("/api/projects/{projectId}/users")
    suspend fun getProjectUsers(
        @Path("projectId") projectId: Long
    ): List<UserDto>

    @GET("/api/projects/{projectId}/noteables")
    suspend fun getProjectNoteables(
        @Path("projectId") projectId: Long
    ): List<NoteableDto>

    @GET("/api/projects/{projectId}/notes")
    suspend fun getProjectNotes(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<NoteDto>

    @POST("/api/projects/{projectId}/notes")
    suspend fun createProjectNote(
        @Path("projectId") projectId: Long,
        @Body body: CreateNoteRequest
    ): NoteResourceResponse

    @PUT("/api/notes/{noteId}")
    suspend fun updateNote(
        @Path("noteId") noteId: Long,
        @Body body: CreateNoteRequest
    ): NoteResourceResponse

    @HTTP(method = "DELETE", path = "/api/notes/{noteId}", hasBody = true)
    suspend fun deleteNote(
        @Path("noteId") noteId: Long,
        @Body body: DeleteWithTimestampRequest
    ): Response<Unit>

    @POST("/api/addresses")
    suspend fun createAddress(
        @Body body: CreateAddressRequest
    ): AddressResourceResponse

    @POST("/api/companies/{companyId}/projects")
    suspend fun createCompanyProject(
        @Path("companyId") companyId: Long,
        @Body body: CreateCompanyProjectRequest
    ): ProjectResourceResponse

    @PUT("/api/projects/{projectId}")
    suspend fun updateProject(
        @Path("projectId") projectId: Long,
        @Body body: UpdateProjectRequest
    ): ProjectResourceResponse

    // Feature flags
    @GET("/api/auth/user/feature-flags")
    suspend fun getFeatureFlags(): FeatureFlagResponse

    // Property & locations
    @GET("/api/projects/{projectId}/properties")
    suspend fun getProjectProperties(
        @Path("projectId") projectId: Long,
        @Query("include") include: String? = null
    ): PaginatedResponse<PropertyDto>

    @POST("/api/projects/{projectId}/properties")
    suspend fun createProjectProperty(
        @Path("projectId") projectId: Long,
        @Body body: PropertyMutationRequest
    ): PropertyResourceResponse

    @PUT("/api/properties/{propertyId}")
    suspend fun updateProperty(
        @Path("propertyId") propertyId: Long,
        @Body body: PropertyMutationRequest
    ): PropertyResourceResponse

    @GET("/api/properties/{propertyId}")
    suspend fun getProperty(
        @Path("propertyId") propertyId: Long,
        @Query("include") include: String? = "propertyType,asbestosStatus,propertyDamageTypes,damageCause"
    ): PropertyResourceResponse

    @HTTP(method = "DELETE", path = "/api/properties/{propertyId}", hasBody = true)
    suspend fun deleteProperty(
        @Path("propertyId") propertyId: Long,
        @Body body: DeleteWithTimestampRequest
    )

    @GET("/api/properties/{propertyId}/levels")
    suspend fun getPropertyLevels(
        @Path("propertyId") propertyId: Long
    ): PaginatedResponse<LocationDto>

    @GET("/api/properties/{propertyId}/locations")
    suspend fun getPropertyLocations(
        @Path("propertyId") propertyId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<LocationDto>

    @POST("/api/properties/{propertyId}/locations")
    suspend fun createLocation(
        @Path("propertyId") propertyId: Long,
        @Body body: CreateLocationRequest
    ): LocationDto

    @PUT("/api/locations/{locationId}")
    suspend fun updateLocation(
        @Path("locationId") locationId: Long,
        @Body body: UpdateLocationRequest
    ): LocationDto

    @HTTP(method = "DELETE", path = "/api/locations/{locationId}", hasBody = true)
    suspend fun deleteLocation(
        @Path("locationId") locationId: Long,
        @Body body: DeleteWithTimestampRequest
    )

    // Damage types / causes (Project Loss Info)
    @GET("/api/projects/{projectId}/property-damage-types")
    suspend fun getProjectDamageTypes(
        @Path("projectId") projectId: Long
    ): List<DamageTypeDto>

    @GET("/api/projects/{projectId}/damage-causes")
    suspend fun getDamageCauses(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = 100,
        @Query("include") include: String? = "propertyDamageType"
    ): PaginatedResponse<DamageCauseDto>

    @POST("/api/properties/{propertyId}/property-damage-types/{damageTypeId}")
    suspend fun addPropertyDamageType(
        @Path("propertyId") propertyId: Long,
        @Path("damageTypeId") damageTypeId: Long
    )

    @DELETE("/api/properties/{propertyId}/property-damage-types/{damageTypeId}")
    suspend fun removePropertyDamageType(
        @Path("propertyId") propertyId: Long,
        @Path("damageTypeId") damageTypeId: Long
    )

    // Rooms
    @GET("/api/locations/{locationId}/rooms")
    suspend fun getRoomsForLocation(
        @Path("locationId") locationId: Long,
        @Query("page") page: Int? = null,
        @Query("include") include: String? = "roomType,level,thumbnail,photos_count,notes_count,bookmarked_notes_count,flagged_notes_count,damage_materials_count,equipment_count",
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<RoomDto>

    @GET("/api/rooms/{roomId}")
    suspend fun getRoomDetail(
        @Path("roomId") roomId: Long
    ): RoomDto

    @POST("/api/locations/{locationId}/rooms")
    suspend fun createRoom(
        @Path("locationId") locationId: Long,
        @Body request: CreateRoomRequest
    ): JsonElement

    @PUT("/api/rooms/{roomId}")
    suspend fun updateRoom(
        @Path("roomId") roomId: Long,
        @Body request: UpdateRoomRequest
    ): RoomDto

    @HTTP(method = "DELETE", path = "/api/rooms/{roomId}", hasBody = true)
    suspend fun deleteRoom(
        @Path("roomId") roomId: Long,
        @Body request: DeleteWithTimestampRequest
    )

    @GET("/api/properties/{propertyId}/room-types")
    suspend fun getPropertyRoomTypes(
        @Path("propertyId") propertyId: Long,
        @Query("filter[type]") filterType: String? = null
    ): PaginatedResponse<RoomTypeDto>

    // Photos & albums
    @GET("/api/projects/{projectId}/albums")
    suspend fun getProjectAlbums(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null,
        @Query("include") include: String? = "photos"
    ): PaginatedResponse<AlbumDto>

    @GET("/api/rooms/{roomId}/photos")
    suspend fun getRoomPhotos(
        @Path("roomId") roomId: Long,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = 30,
        @Query("include") include: String? = "photo,albums,notes_count,creator",
        @Query("filter[updated_date]") updatedSince: String? = null
    ): JsonObject

    @GET("/api/projects/{projectId}/floor-photos")
    suspend fun getProjectFloorPhotos(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<ProjectPhotoListingDto>

    @GET("/api/projects/{projectId}/location-photos")
    suspend fun getProjectLocationPhotos(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<ProjectPhotoListingDto>

    @GET("/api/projects/{projectId}/unit-photos")
    suspend fun getProjectUnitPhotos(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<ProjectPhotoListingDto>

    @HTTP(method = "DELETE", path = "/api/photos/{photoId}", hasBody = true)
    suspend fun deletePhoto(
        @Path("photoId") photoId: Long,
        @Body body: DeleteWithTimestampRequest
    ): Response<Unit>

    // Atmospheric & moisture logs
    @GET("/api/projects/{projectId}/atmospheric-logs")
    suspend fun getProjectAtmosphericLogs(
        @Path("projectId") projectId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<AtmosphericLogDto>

    @GET("/api/rooms/{roomId}/atmospheric-logs")
    suspend fun getRoomAtmosphericLogs(
        @Path("roomId") roomId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): List<AtmosphericLogDto>

    @POST("/api/projects/{projectId}/atmospheric-logs")
    suspend fun createProjectAtmosphericLog(
        @Path("projectId") projectId: Long,
        @Body body: AtmosphericLogRequest
    ): AtmosphericLogDto

    @POST("/api/rooms/{roomId}/atmospheric-logs")
    suspend fun createRoomAtmosphericLog(
        @Path("roomId") roomId: Long,
        @Body body: AtmosphericLogRequest
    ): AtmosphericLogDto

    @PUT("/api/atmospheric-logs/{logId}")
    suspend fun updateAtmosphericLog(
        @Path("logId") logId: Long,
        @Body body: AtmosphericLogRequest
    ): AtmosphericLogDto

    @HTTP(method = "DELETE", path = "/api/atmospheric-logs/{logId}", hasBody = true)
    suspend fun deleteAtmosphericLog(
        @Path("logId") logId: Long,
        @Body body: DeleteWithTimestampRequest
    ): Response<Unit>

    @GET("/api/rooms/{roomId}/damage-materials/logs")
    suspend fun getRoomMoistureLogs(
        @Path("roomId") roomId: Long,
        @Query("include") include: String? = null
    ): FlexibleDataResponse

    @POST("/api/projects/{projectId}/damage-materials")
    suspend fun createProjectDamageMaterial(
        @Path("projectId") projectId: Long,
        @Body body: DamageMaterialRequest
    ): DamageMaterialDto

    @PUT("/api/damage-materials/{damageMaterialId}")
    suspend fun updateDamageMaterial(
        @Path("damageMaterialId") damageMaterialId: Long,
        @Body body: DamageMaterialRequest
    ): DamageMaterialDto

    @HTTP(method = "DELETE", path = "/api/damage-materials/{damageMaterialId}", hasBody = true)
    suspend fun deleteDamageMaterial(
        @Path("damageMaterialId") damageMaterialId: Long,
        @Body body: DeleteWithTimestampRequest
    ): Response<Unit>

    @POST("/api/rooms/{roomId}/damage-materials/{damageMaterialId}")
    suspend fun attachDamageMaterialToRoom(
        @Path("roomId") roomId: Long,
        @Path("damageMaterialId") damageMaterialId: Long,
        @Body body: DamageMaterialRequest
    ): Response<Unit>

    @POST("/api/rooms/{roomId}/damage-materials/{damageMaterialId}/logs")
    suspend fun createMoistureLog(
        @Path("roomId") roomId: Long,
        @Path("damageMaterialId") damageMaterialId: Long,
        @Body body: MoistureLogRequest
    ): MoistureLogDto

    @PUT("/api/damage-material-room-log/{logId}")
    suspend fun updateMoistureLog(
        @Path("logId") logId: Long,
        @Body body: MoistureLogRequest
    ): MoistureLogDto

    @HTTP(method = "DELETE", path = "/api/damage-material-room-log/{logId}", hasBody = true)
    suspend fun deleteMoistureLog(
        @Path("logId") logId: Long,
        @Body body: DeleteWithTimestampRequest
    ): Response<Unit>

    // Damage, work scope, equipment
    @GET("/api/projects/{projectId}/damage-materials")
    suspend fun getProjectDamageMaterials(
        @Path("projectId") projectId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<DamageMaterialDto>

    @GET("/api/rooms/{roomId}/damage-materials")
    suspend fun getRoomDamageMaterials(
        @Path("roomId") roomId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<DamageMaterialDto>

    @GET("/api/work-scope/{companyId}")
    suspend fun getWorkScopeCatalog(
        @Path("companyId") companyId: Long
    ): PaginatedResponse<WorkScopeSheetDto>

    @POST("/api/rooms/{roomId}/work-scope-items")
    suspend fun addRoomWorkScopeItems(
        @Path("roomId") roomId: Long,
        @Body body: AddWorkScopeItemsRequest
    ): Response<Unit>

    @GET("/api/rooms/{roomId}/work-scope-items")
    suspend fun getRoomWorkScope(
        @Path("roomId") roomId: Long
    ): PaginatedResponse<WorkScopeDto>

    @GET("/api/projects/{projectId}/equipment")
    suspend fun getProjectEquipment(
        @Path("projectId") projectId: Long
    ): PaginatedResponse<EquipmentDto>

    @GET("/api/rooms/{roomId}/equipment")
    suspend fun getRoomEquipment(
        @Path("roomId") roomId: Long
    ): List<EquipmentDto>

    @POST("/api/projects/{projectId}/equipment")
    suspend fun createProjectEquipment(
        @Path("projectId") projectId: Long,
        @Body body: EquipmentRequest
    ): EquipmentDto

    @PUT("/api/equipment/{equipmentId}")
    suspend fun updateEquipment(
        @Path("equipmentId") equipmentId: Long,
        @Body body: EquipmentRequest
    ): EquipmentDto

    @HTTP(method = "DELETE", path = "/api/equipment/{equipmentId}", hasBody = true)
    suspend fun deleteEquipment(
        @Path("equipmentId") equipmentId: Long,
        @Body body: DeleteWithTimestampRequest
    ): Response<Unit>

    // Claims
    @GET("/api/projects/{projectId}/claims")
    suspend fun getProjectClaims(
        @Path("projectId") projectId: Long,
        @Query("include") include: String? = "project,claimType,claimInfo"
    ): PaginatedResponse<ClaimDto>

    @GET("/api/locations/{locationId}/claims")
    suspend fun getLocationClaims(
        @Path("locationId") locationId: Long,
        @Query("include") include: String? = "project,claimType,claimInfo"
    ): PaginatedResponse<ClaimDto>

    @POST("/api/projects/{projectId}/claims")
    suspend fun createProjectClaim(
        @Path("projectId") projectId: Long,
        @Body body: ClaimMutationRequest
    ): ClaimDto

    @POST("/api/locations/{locationId}/claims")
    suspend fun createLocationClaim(
        @Path("locationId") locationId: Long,
        @Body body: ClaimMutationRequest
    ): ClaimDto

    @PUT("/api/claims/{claimId}")
    suspend fun updateClaim(
        @Path("claimId") claimId: Long,
        @Body body: ClaimMutationRequest
    ): ClaimDto

    @GET("/api/sync/deleted")
    suspend fun getDeletedRecords(
        @Query("since") since: String,
        @Query("types[]") types: List<String>? = null
    ): Response<DeletedRecordsResponse>

    @POST("/api/sync/restore")
    suspend fun restoreDeletedRecords(
        @Body request: RestoreRecordsRequest
    ): RestoreRecordsResponse
}
