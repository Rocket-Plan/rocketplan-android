package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.ImageProcessorAssemblyRequest
import com.example.rocketplan_android.data.model.ImageProcessorAssemblyResponse
import com.example.rocketplan_android.data.model.ImageProcessorStatusResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ImageProcessorApi {

    @POST("/api/rooms/{roomId}/image-processor")
    suspend fun createRoomAssembly(
        @Path("roomId") roomId: Long,
        @Body request: ImageProcessorAssemblyRequest
    ): Response<ImageProcessorAssemblyResponse>

    @POST("/api/image-processor")
    suspend fun createEntityAssembly(
        @Body request: ImageProcessorAssemblyRequest
    ): Response<ImageProcessorAssemblyResponse>

    @GET("/api/rooms/{roomId}/image-processor/{assemblyId}")
    suspend fun getRoomAssemblyStatus(
        @Path("roomId") roomId: Long,
        @Path("assemblyId") assemblyId: String,
        @Query("format") format: String = "simple",
        @Query("include_logs") includeLogs: Boolean = true
    ): Response<ImageProcessorStatusResponse>

    @GET("/api/image-processor/{assemblyId}")
    suspend fun getAssemblyStatus(
        @Path("assemblyId") assemblyId: String,
        @Query("format") format: String = "simple",
        @Query("include_logs") includeLogs: Boolean = true
    ): Response<ImageProcessorStatusResponse>
}
