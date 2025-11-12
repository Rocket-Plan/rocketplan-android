package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

/**
 * DTO describing the configuration required to communicate with
 * the backend image processing service (TUS or future variants).
 */
data class ImageProcessingConfiguration(
    val service: String,
    val url: String,
    @SerializedName("api_key")
    val apiKey: String?
)

data class ImageProcessingConfigurationResponse(
    val data: ImageProcessingConfiguration
)
