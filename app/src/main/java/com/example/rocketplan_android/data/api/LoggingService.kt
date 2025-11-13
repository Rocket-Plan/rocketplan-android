package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.RemoteLogBatch
import com.example.rocketplan_android.data.model.RemoteLogResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface LoggingService {
    @Headers("Content-Encoding: gzip")
    @POST("api/logs/ios")
    suspend fun submitLogBatch(@Body batch: RemoteLogBatch): Response<RemoteLogResponse>
}
