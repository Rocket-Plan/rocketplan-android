package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CrmBusinessDto
import com.example.rocketplan_android.data.model.CrmBusinessRequest
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

interface CrmBusinessApi {

    @GET("api/companies/{companyId}/ghl/businesses")
    suspend fun getBusinesses(
        @Path("companyId") companyId: Long,
        @Query("filter[search]") search: String? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = 50,
        @Query("include") include: String? = "customFieldValues"
    ): Response<PaginatedResponse<CrmBusinessDto>>

    @GET("api/companies/{companyId}/ghl/businesses/{businessId}")
    suspend fun getBusiness(
        @Path("companyId") companyId: Long,
        @Path("businessId") businessId: String,
        @Query("include") include: String? = "customFieldValues"
    ): Response<SingleDataResponse<CrmBusinessDto>>

    @POST("api/companies/{companyId}/ghl/businesses")
    suspend fun createBusiness(
        @Path("companyId") companyId: Long,
        @Body request: CrmBusinessRequest
    ): Response<SingleDataResponse<CrmBusinessDto>>

    @PUT("api/companies/{companyId}/ghl/businesses/{businessId}")
    suspend fun updateBusiness(
        @Path("companyId") companyId: Long,
        @Path("businessId") businessId: String,
        @Body request: CrmBusinessRequest
    ): Response<SingleDataResponse<CrmBusinessDto>>

    @DELETE("api/companies/{companyId}/ghl/businesses/{businessId}")
    suspend fun deleteBusiness(
        @Path("companyId") companyId: Long,
        @Path("businessId") businessId: String
    ): Response<Unit>
}
