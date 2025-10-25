package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.NoteableDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface OfflineSyncApi {

    // Projects
    @GET("/api/companies/{companyId}/projects")
    suspend fun getCompanyProjects(
        @Path("companyId") companyId: Long,
        @Query("page") page: Int? = null
    ): PaginatedResponse<ProjectDto>

    @GET("/api/users/{userId}/projects")
    suspend fun getUserProjects(
        @Path("userId") userId: Long,
        @Query("page") page: Int? = null
    ): PaginatedResponse<ProjectDto>

    @GET("/api/projects/{projectId}")
    suspend fun getProjectDetail(
        @Path("projectId") projectId: Long
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
        @Path("projectId") projectId: Long
    ): List<NoteDto>

    // Property & locations
    @GET("/api/projects/{projectId}/properties")
    suspend fun getProjectProperties(
        @Path("projectId") projectId: Long
    ): List<PropertyDto>

    @GET("/api/properties/{propertyId}/levels")
    suspend fun getPropertyLevels(
        @Path("propertyId") propertyId: Long
    ): List<LocationDto>

    @GET("/api/properties/{propertyId}/locations")
    suspend fun getPropertyLocations(
        @Path("propertyId") propertyId: Long
    ): List<LocationDto>

    // Rooms
    @GET("/api/locations/{locationId}/rooms")
    suspend fun getRoomsForLocation(
        @Path("locationId") locationId: Long,
        @Query("include") include: String? = "roomType,photosCount,thumbnail,level,photoAssemblies,damage_materials_count,damageMaterials.damageType,noteCategoryCounts,equipment,equipmentCount,workScopeActions"
    ): List<RoomDto>

    @GET("/api/rooms/{roomId}")
    suspend fun getRoomDetail(
        @Path("roomId") roomId: Long
    ): RoomDto

    // Photos & albums
    @GET("/api/projects/{projectId}/albums")
    suspend fun getProjectAlbums(
        @Path("projectId") projectId: Long
    ): List<AlbumDto>

    @GET("/api/rooms/{roomId}/photos")
    suspend fun getRoomPhotos(
        @Path("roomId") roomId: Long,
        @Query("limit") limit: String? = "30",
        @Query("include") include: String? = "photo,albums,notesCount,creator"
    ): List<PhotoDto>

    @GET("/api/projects/{projectId}/floor-photos")
    suspend fun getProjectFloorPhotos(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null
    ): PaginatedResponse<ProjectPhotoListingDto>

    @GET("/api/projects/{projectId}/location-photos")
    suspend fun getProjectLocationPhotos(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null
    ): PaginatedResponse<ProjectPhotoListingDto>

    @GET("/api/projects/{projectId}/unit-photos")
    suspend fun getProjectUnitPhotos(
        @Path("projectId") projectId: Long,
        @Query("page") page: Int? = null
    ): PaginatedResponse<ProjectPhotoListingDto>

    // Atmospheric & moisture logs
    @GET("/api/projects/{projectId}/atmospheric-logs")
    suspend fun getProjectAtmosphericLogs(
        @Path("projectId") projectId: Long
    ): List<AtmosphericLogDto>

    @GET("/api/rooms/{roomId}/atmospheric-logs")
    suspend fun getRoomAtmosphericLogs(
        @Path("roomId") roomId: Long
    ): List<AtmosphericLogDto>

    @GET("/api/rooms/{roomId}/damage-materials/logs")
    suspend fun getRoomMoistureLogs(
        @Path("roomId") roomId: Long
    ): List<MoistureLogDto>

    // Damage, work scope, equipment
    @GET("/api/projects/{projectId}/damage-materials")
    suspend fun getProjectDamageMaterials(
        @Path("projectId") projectId: Long
    ): List<DamageMaterialDto>

    @GET("/api/rooms/{roomId}/damage-materials")
    suspend fun getRoomDamageMaterials(
        @Path("roomId") roomId: Long
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
}
