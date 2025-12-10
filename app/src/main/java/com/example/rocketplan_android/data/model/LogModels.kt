package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class RemoteLogEntry(
    val timestamp: String,
    val level: String,
    val category: String?,
    val message: String,
    @SerializedName("correlation_id")
    val correlationId: String? = null,
    val tags: List<String>? = null,
    val data: Map<String, String>? = null
)

data class RemoteLogBatch(
    @SerializedName("batch_id")
    val batchId: String,
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("user_id")
    val userId: String?,
    @SerializedName("company_id")
    val companyId: String?,
    @SerializedName("session_id")
    val sessionId: String,
    @SerializedName("app_version")
    val appVersion: String,
    @SerializedName("build_number")
    val buildNumber: String?,
    val platform: String = "Android",
    val logs: List<RemoteLogEntry>
)

data class RemoteLogResponse(
    val success: Boolean = true,
    val error: String? = null,
    val details: Map<String, Any?>? = null
)
