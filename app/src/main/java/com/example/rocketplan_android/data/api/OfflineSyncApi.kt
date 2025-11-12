package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.DeletedRecordsResponse
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.NoteableDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.google.gson.JsonObject
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface OfflineSyncApi {

    // Projects
    @GET("/api/companies/{companyId}/projects")
    suspend fun getCompanyProjects(
        @Path("companyId") companyId: Long,
        @Query("page") page: Int? = null,
        @Query("filter[updated_date]") updatedSince: String? = null
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
    ): ProjectDetailDto

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
        @Query("filter[updated_date]") updatedSince: String? = null
    ): List<NoteDto>

    // Property & locations
    @GET("/api/projects/{projectId}/properties")
    suspend fun getProjectProperties(
        @Path("projectId") projectId: Long
    ): PaginatedResponse<PropertyDto>

    @GET("/api/properties/{propertyId}/levels")
    suspend fun getPropertyLevels(
        @Path("propertyId") propertyId: Long
    ): PaginatedResponse<LocationDto>

    @GET("/api/properties/{propertyId}/locations")
    suspend fun getPropertyLocations(
        @Path("propertyId") propertyId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): PaginatedResponse<LocationDto>

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
        @Query("filter[updated_date]") updatedSince: String? = null,
        @Query("sort") sort: String? = "-id"
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

    // Atmospheric & moisture logs
    @GET("/api/projects/{projectId}/atmospheric-logs")
    suspend fun getProjectAtmosphericLogs(
        @Path("projectId") projectId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): List<AtmosphericLogDto>

    @GET("/api/rooms/{roomId}/atmospheric-logs")
    suspend fun getRoomAtmosphericLogs(
        @Path("roomId") roomId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): List<AtmosphericLogDto>

    @GET("/api/rooms/{roomId}/damage-materials/logs")
    suspend fun getRoomMoistureLogs(
        @Path("roomId") roomId: Long
    ): List<MoistureLogDto>

    // Damage, work scope, equipment
    @GET("/api/projects/{projectId}/damage-materials")
    suspend fun getProjectDamageMaterials(
        @Path("projectId") projectId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): List<DamageMaterialDto>

    @GET("/api/rooms/{roomId}/damage-materials")
    suspend fun getRoomDamageMaterials(
        @Path("roomId") roomId: Long,
        @Query("filter[updated_date]") updatedSince: String? = null
    ): List<DamageMaterialDto>

    @GET("/api/rooms/{roomId}/work-scope-items")
    suspend fun getRoomWorkScope(
        @Path("roomId") roomId: Long
    ): List<WorkScopeDto>

    @GET("/api/projects/{projectId}/equipment")
    suspend fun getProjectEquipment(
        @Path("projectId") projectId: Long
    ): List<EquipmentDto>

    @GET("/api/rooms/{roomId}/equipment")
    suspend fun getRoomEquipment(
        @Path("roomId") roomId: Long
    ): List<EquipmentDto>

    @GET("/api/sync/deleted")
    suspend fun getDeletedRecords(
        @Query("since") since: String,
        @Query("types[]") types: List<String>? = null
    ): Response<DeletedRecordsResponse>
}
