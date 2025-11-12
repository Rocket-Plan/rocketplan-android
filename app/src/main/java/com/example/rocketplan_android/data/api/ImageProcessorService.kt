package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.ImageProcessingConfigurationResponse
import retrofit2.http.GET

interface ImageProcessorService {
    @GET("/api/configuration/processing-service")
    suspend fun getProcessingConfiguration(): ImageProcessingConfigurationResponse
}
