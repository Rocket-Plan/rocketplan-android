package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CrmCustomFieldsRequest
import com.example.rocketplan_android.data.model.CrmOpportunityDto
import com.example.rocketplan_android.data.model.CrmOpportunityRequest
import com.example.rocketplan_android.data.model.CrmPipelinesResponse
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

interface CrmOpportunityApi {

    @GET("api/companies/{companyId}/ghl/pipelines")
    suspend fun getPipelines(
        @Path("companyId") companyId: Long,
        @Query("limit") limit: Int? = 100,
        @Query("include") include: String? = "stages"
    ): Response<CrmPipelinesResponse>

    @GET("api/companies/{companyId}/ghl/opportunities/{opportunityId}")
    suspend fun getOpportunity(
        @Path("companyId") companyId: Long,
        @Path("opportunityId") opportunityId: String,
        @Query("include") include: String? = "customFieldValues,contact,pipeline,stage"
    ): Response<SingleDataResponse<CrmOpportunityDto>>

    @GET("api/companies/{companyId}/ghl/opportunities")
    suspend fun getOpportunities(
        @Path("companyId") companyId: Long,
        @Query("filter[pipeline_id]") pipelineId: String? = null,
        @Query("filter[search]") search: String? = null,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = 50,
        @Query("include") include: String? = "customFieldValues,contact"
    ): Response<PaginatedResponse<CrmOpportunityDto>>

    @POST("api/companies/{companyId}/ghl/opportunities")
    suspend fun createOpportunity(
        @Path("companyId") companyId: Long,
        @Body request: CrmOpportunityRequest
    ): Response<SingleDataResponse<CrmOpportunityDto>>

    @PUT("api/companies/{companyId}/ghl/opportunities/{opportunityId}")
    suspend fun updateOpportunity(
        @Path("companyId") companyId: Long,
        @Path("opportunityId") opportunityId: String,
        @Body request: CrmOpportunityRequest
    ): Response<SingleDataResponse<CrmOpportunityDto>>

    @PUT("api/companies/{companyId}/ghl/opportunities/{opportunityId}/custom-fields")
    suspend fun setOpportunityCustomFields(
        @Path("companyId") companyId: Long,
        @Path("opportunityId") opportunityId: String,
        @Body request: CrmCustomFieldsRequest
    ): Response<SingleDataResponse<CrmOpportunityDto>>

    @DELETE("api/companies/{companyId}/ghl/opportunities/{opportunityId}")
    suspend fun deleteOpportunity(
        @Path("companyId") companyId: Long,
        @Path("opportunityId") opportunityId: String
    ): Response<Unit>
}
