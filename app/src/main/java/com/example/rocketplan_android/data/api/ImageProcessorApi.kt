package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.ImageProcessorAssemblyRequest
import com.example.rocketplan_android.data.model.ImageProcessorAssemblyResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

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
}
