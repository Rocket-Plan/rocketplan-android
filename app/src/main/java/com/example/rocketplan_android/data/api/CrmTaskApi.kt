package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CrmTaskDto
import com.example.rocketplan_android.data.model.CrmTaskRequest
import com.example.rocketplan_android.data.model.CrmTaskUpdateRequest
import com.example.rocketplan_android.data.model.SingleDataResponse
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface CrmTaskApi {

    @GET("api/companies/{companyId}/ghl/tasks")
    suspend fun getTasks(
        @Path("companyId") companyId: Long,
        @Query("filter[contact_id]") contactId: String? = null,
        @Query("sort") sort: String? = "-created_at",
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = 50
    ): Response<PaginatedResponse<CrmTaskDto>>

    @POST("api/companies/{companyId}/ghl/tasks")
    suspend fun createTask(
        @Path("companyId") companyId: Long,
        @Body request: CrmTaskRequest
    ): Response<SingleDataResponse<CrmTaskDto>>

    @PUT("api/companies/{companyId}/ghl/tasks/{taskId}")
    suspend fun updateTask(
        @Path("companyId") companyId: Long,
        @Path("taskId") taskId: String,
        @Body request: CrmTaskUpdateRequest
    ): Response<SingleDataResponse<CrmTaskDto>>

    @DELETE("api/companies/{companyId}/ghl/tasks/{taskId}")
    suspend fun deleteTask(
        @Path("companyId") companyId: Long,
        @Path("taskId") taskId: String
    ): Response<Unit>
}
