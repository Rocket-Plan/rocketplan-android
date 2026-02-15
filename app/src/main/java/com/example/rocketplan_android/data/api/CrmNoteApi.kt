package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CrmNoteDto
import com.example.rocketplan_android.data.model.CrmNoteRequest
import com.example.rocketplan_android.data.model.CrmNoteUpdateRequest
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

interface CrmNoteApi {

    @GET("api/companies/{companyId}/ghl/notes")
    suspend fun getNotes(
        @Path("companyId") companyId: Long,
        @Query("filter[contact_id]") contactId: String? = null,
        @Query("sort") sort: String? = "-date_added",
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = 50
    ): Response<PaginatedResponse<CrmNoteDto>>

    @POST("api/companies/{companyId}/ghl/notes")
    suspend fun createNote(
        @Path("companyId") companyId: Long,
        @Body request: CrmNoteRequest
    ): Response<SingleDataResponse<CrmNoteDto>>

    @PUT("api/companies/{companyId}/ghl/notes/{noteId}")
    suspend fun updateNote(
        @Path("companyId") companyId: Long,
        @Path("noteId") noteId: String,
        @Body request: CrmNoteUpdateRequest
    ): Response<SingleDataResponse<CrmNoteDto>>

    @DELETE("api/companies/{companyId}/ghl/notes/{noteId}")
    suspend fun deleteNote(
        @Path("companyId") companyId: Long,
        @Path("noteId") noteId: String
    ): Response<Unit>
}
