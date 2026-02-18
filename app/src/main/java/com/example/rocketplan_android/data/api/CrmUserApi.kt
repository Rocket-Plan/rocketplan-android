package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CrmUserDto
import com.example.rocketplan_android.data.model.GhlMeResponse
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface CrmUserApi {

    @GET("api/companies/{companyId}/ghl/me")
    suspend fun getGhlMe(
        @Path("companyId") companyId: Long
    ): Response<GhlMeResponse>

    @GET("api/companies/{companyId}/ghl/users")
    suspend fun getUsers(
        @Path("companyId") companyId: Long,
        @Query("filter[role]") role: String? = null,
        @Query("filter[type]") type: String? = null,
        @Query("filter[email]") email: String? = null,
        @Query("filter[name]") name: String? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null
    ): Response<PaginatedResponse<CrmUserDto>>
}
