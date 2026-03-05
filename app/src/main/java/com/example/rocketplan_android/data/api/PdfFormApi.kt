package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.PdfFormSubmissionListResponse
import com.example.rocketplan_android.data.model.PdfFormSubmissionSingleResponse
import com.example.rocketplan_android.data.model.PdfFormSignDataResponse
import com.example.rocketplan_android.data.model.PdfFormTemplateResponse
import com.example.rocketplan_android.data.model.CreatePdfFormSubmissionRequest
import com.example.rocketplan_android.data.model.SignPdfFormRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface RawPdfFormApi {
    @GET("api/pdf-form-sign/{uuid}")
    suspend fun getSignDataRaw(
        @Path("uuid") uuid: String
    ): Response<okhttp3.ResponseBody>
}

interface PdfFormApi {

    @GET("api/companies/{companyId}/pdf-form-templates")
    suspend fun getTemplates(
        @Path("companyId") companyId: Long
    ): Response<PdfFormTemplateResponse>

    @GET("api/pdf-form-submissions")
    suspend fun getSubmissions(
        @Query("company_id") companyId: Long,
        @Query("project_id") projectId: Long
    ): Response<PdfFormSubmissionListResponse>

    @POST("api/pdf-form-submissions")
    suspend fun createSubmission(
        @Body request: CreatePdfFormSubmissionRequest
    ): Response<PdfFormSubmissionSingleResponse>

    @GET("api/pdf-form-sign/{uuid}")
    suspend fun getSignData(
        @Path("uuid") uuid: String
    ): Response<PdfFormSignDataResponse>

    @POST("api/pdf-form-sign/{uuid}/sign")
    suspend fun signForm(
        @Path("uuid") uuid: String,
        @Body request: SignPdfFormRequest
    ): Response<PdfFormSubmissionSingleResponse>

    @DELETE("api/pdf-form-submissions/{id}")
    suspend fun deleteSubmission(
        @Path("id") id: Long
    ): Response<Unit>
}
