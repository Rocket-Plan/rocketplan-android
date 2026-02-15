package com.example.rocketplan_android.data.api

import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.model.CrmContactRequest
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.model.CrmCustomFieldsRequest
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

interface CrmContactApi {

    @GET("api/companies/{companyId}/ghl/contacts/{contactId}")
    suspend fun getContact(
        @Path("companyId") companyId: Long,
        @Path("contactId") contactId: String
    ): Response<SingleDataResponse<CrmContactDto>>

    @GET("api/companies/{companyId}/ghl/contacts")
    suspend fun getContacts(
        @Path("companyId") companyId: Long,
        @Query("filter[search]") search: String? = null,
        @Query("filter[type]") type: String? = null,
        @Query("filter[business_id]") businessId: String? = null,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = 50,
        @Query("include") include: String? = "customFieldValues"
    ): Response<PaginatedResponse<CrmContactDto>>

    @POST("api/companies/{companyId}/ghl/contacts")
    suspend fun createContact(
        @Path("companyId") companyId: Long,
        @Body request: CrmContactRequest
    ): Response<SingleDataResponse<CrmContactDto>>

    @PUT("api/companies/{companyId}/ghl/contacts/{contactId}")
    suspend fun updateContact(
        @Path("companyId") companyId: Long,
        @Path("contactId") contactId: String,
        @Body request: CrmContactRequest
    ): Response<SingleDataResponse<CrmContactDto>>

    @PUT("api/companies/{companyId}/ghl/contacts/{contactId}")
    suspend fun updateContactRaw(
        @Path("companyId") companyId: Long,
        @Path("contactId") contactId: String,
        @Body body: okhttp3.RequestBody
    ): Response<SingleDataResponse<CrmContactDto>>

    @GET("api/companies/{companyId}/ghl/contact-types")
    suspend fun getContactTypes(
        @Path("companyId") companyId: Long
    ): Response<SingleDataResponse<List<String>>>

    @GET("api/companies/{companyId}/ghl/custom-fields")
    suspend fun getCustomFields(
        @Path("companyId") companyId: Long,
        @Query("filter[model]") model: String? = null,
        @Query("limit") limit: Int? = 100
    ): Response<PaginatedResponse<CrmCustomFieldDefinitionDto>>

    @PUT("api/companies/{companyId}/ghl/contacts/{contactId}/custom-fields")
    suspend fun setContactCustomFields(
        @Path("companyId") companyId: Long,
        @Path("contactId") contactId: String,
        @Body request: CrmCustomFieldsRequest
    ): Response<SingleDataResponse<CrmContactDto>>

    @DELETE("api/companies/{companyId}/ghl/contacts/{contactId}")
    suspend fun deleteContact(
        @Path("companyId") companyId: Long,
        @Path("contactId") contactId: String
    ): Response<Unit>
}
