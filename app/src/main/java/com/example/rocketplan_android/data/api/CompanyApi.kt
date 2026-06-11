package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CheckCompanyByUuidRequest
import com.example.rocketplan_android.data.model.CompanyEnvelope
import com.example.rocketplan_android.data.model.CreateCompanyRequest
import com.example.rocketplan_android.data.model.CreateCompanyResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface CompanyApi {

    @POST("api/companies")
    suspend fun createCompany(@Body request: CreateCompanyRequest): Response<CreateCompanyResponse>

    @POST("api/companies/{companyId}/users/{userId}")
    suspend fun addCompanyUser(
        @Path("companyId") companyId: Long,
        @Path("userId") userId: Long
    ): Response<Unit>

    @POST("api/companies/check")
    suspend fun checkCompanyByUuid(@Body request: CheckCompanyByUuidRequest): Response<CompanyEnvelope>
}
