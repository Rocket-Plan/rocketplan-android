package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CreateCompanyRequest
import com.example.rocketplan_android.data.model.CreateCompanyResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface CompanyApi {

    @POST("api/companies")
    suspend fun createCompany(@Body request: CreateCompanyRequest): Response<CreateCompanyResponse>

    /**
     * Link a user to a company. This route has company.resolve middleware removed
     * on the server, so it works during onboarding before an active company is set.
     */
    @POST("api/companies/{companyId}/users/{userId}")
    suspend fun addCompanyUser(
        @Path("companyId") companyId: Long,
        @Path("userId") userId: Long
    ): Response<Unit>
}
