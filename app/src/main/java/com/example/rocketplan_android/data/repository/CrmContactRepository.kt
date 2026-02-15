package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.model.CrmContactRequest
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.model.CrmCustomFieldsRequest
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

class CrmContactRepository(
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger? = null
) {

    private val api = RetrofitClient.createService<com.example.rocketplan_android.data.api.CrmContactApi>()

    suspend fun getContact(contactId: String): Result<CrmContactDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching contact=$contactId")
            val response = api.getContact(companyId, contactId)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched contact: id=${body.id}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load contact")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching contact: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getContacts(
        search: String? = null,
        type: String? = null,
        businessId: String? = null,
        page: Int? = null,
        limit: Int? = null
    ): Result<PaginatedResponse<CrmContactDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching contacts page=$page type=$type search=${search?.take(20)}")
            val response = api.getContacts(
                companyId = companyId,
                search = search?.takeIf { it.isNotBlank() },
                type = type,
                businessId = businessId,
                sort = "first_name",
                page = page,
                limit = limit
            )
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                val count = body.data.size
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched $count contacts (page=${body.meta?.currentPage}/${body.meta?.lastPage})")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load contacts")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching contacts: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createContact(request: CrmContactRequest): Result<CrmContactDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Creating contact: firstName=${request.firstName} lastName=${request.lastName} email=${request.email} phone=${request.phone} type=${request.type}")
            val response = api.createContact(companyId, request)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Contact created: id=${body.id}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to create contact")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error creating contact: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateContact(contactId: String, request: CrmContactRequest): Result<CrmContactDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Updating contact: id=$contactId")
            val response = api.updateContact(companyId, contactId, request)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Contact updated: id=${body.id}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to update contact")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error updating contact: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun removeContactBusiness(contactId: String): Result<CrmContactDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Removing business from contact=$contactId")
            val body = """{"business_id":null}""".toRequestBody("application/json".toMediaType())
            val response = api.updateContactRaw(companyId, contactId, body)
            if (response.isSuccessful) {
                val dto = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Removed business from contact=$contactId")
                Result.success(dto)
            } else {
                failWithErrorBody(response, "Failed to remove business from contact")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error removing business from contact: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getCustomFieldDefinitions(model: String = "contact"): Result<List<CrmCustomFieldDefinitionDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching custom field definitions for model=$model")
            val response = api.getCustomFields(companyId, model = model)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${body.size} custom field definitions")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load custom fields")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching custom fields: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateContactCustomFields(
        contactId: String,
        customFields: List<Map<String, String?>>
    ): Result<CrmContactDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Updating custom fields for contact=$contactId (${customFields.size} fields)")
            val response = api.setContactCustomFields(companyId, contactId, CrmCustomFieldsRequest(customFields))
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Custom fields updated for contact=$contactId")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to update custom fields")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error updating custom fields: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getContactTypes(): Result<List<String>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching contact types")
            val response = api.getContactTypes(companyId)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${body.size} contact types: $body")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load contact types")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching contact types: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteContact(contactId: String): Result<Unit> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Deleting contact: id=$contactId")
            val response = api.deleteContact(companyId, contactId)
            if (response.isSuccessful) {
                remoteLogger?.log(LogLevel.INFO, TAG, "Contact deleted: id=$contactId")
                Result.success(Unit)
            } else {
                failWithErrorBody(response, "Failed to delete contact")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error deleting contact: ${e.message}")
            Result.failure(e)
        }
    }

    private fun <T> failWithErrorBody(response: Response<T>, prefix: String): Result<Nothing> {
        val errorBody = try { response.errorBody()?.string()?.take(500) } catch (_: Exception) { null }
        val msg = "$prefix: HTTP ${response.code()}${if (errorBody != null) " - $errorBody" else ""}"
        remoteLogger?.log(LogLevel.ERROR, TAG, msg)
        return Result.failure(IllegalStateException(msg))
    }

    companion object {
        private const val TAG = "CrmContacts"
    }
}
