package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.NoteableDto
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import retrofit2.http.GET
import retrofit2.http.Path

interface OfflineSyncApi {

    // Projects
    @GET("/api/companies/{companyId}/projects")
    suspend fun getCompanyProjects(
        @Path("companyId") companyId: Long
    ): List<ProjectDto>

    @GET("/api/users/{userId}/projects")
    suspend fun getUserProjects(
        @Path("userId") userId: Long
    ): List<ProjectDto>

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

    @GET("/api/projects/{projectId}/locations")
    suspend fun getProjectLocations(
        @Path("projectId") projectId: Long
    ): List<LocationDto>

    // Rooms
    @GET("/api/projects/{projectId}/rooms")
    suspend fun getProjectRooms(
        @Path("projectId") projectId: Long
    ): List<RoomDto>

    @GET("/api/locations/{locationId}/rooms")
    suspend fun getRoomsForLocation(
        @Path("locationId") locationId: Long
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

    @GET("/api/projects/{projectId}/photos")
    suspend fun getProjectPhotos(
        @Path("projectId") projectId: Long
    ): List<PhotoDto>

    @GET("/api/rooms/{roomId}/photos")
    suspend fun getRoomPhotos(
        @Path("roomId") roomId: Long
    ): List<PhotoDto>

    @GET("/api/projects/{projectId}/photo-shares")
    suspend fun getProjectPhotoShares(
        @Path("projectId") projectId: Long
    ): List<PhotoDto>

    @GET("/api/projects/{projectId}/resource-photos")
    suspend fun getProjectResourcePhotos(
        @Path("projectId") projectId: Long
    ): List<PhotoDto>

    // Atmospheric & moisture logs
    @GET("/api/projects/{projectId}/atmospheric-logs")
    suspend fun getProjectAtmosphericLogs(
        @Path("projectId") projectId: Long
    ): List<AtmosphericLogDto>

    @GET("/api/rooms/{roomId}/atmospheric-logs")
    suspend fun getRoomAtmosphericLogs(
        @Path("roomId") roomId: Long
    ): List<AtmosphericLogDto>

    @GET("/api/projects/{projectId}/moisture-logs")
    suspend fun getProjectMoistureLogs(
        @Path("projectId") projectId: Long
    ): List<MoistureLogDto>

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

    @GET("/api/projects/{projectId}/work-scope")
    suspend fun getProjectWorkScope(
        @Path("projectId") projectId: Long
    ): List<WorkScopeDto>

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
