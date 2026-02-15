package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.CrmBusinessDto
import com.example.rocketplan_android.data.model.CrmBusinessRequest
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import retrofit2.Response

class CrmBusinessRepository(
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger? = null
) {

    private val api = RetrofitClient.createService<com.example.rocketplan_android.data.api.CrmBusinessApi>()
    private val contactApi = RetrofitClient.createService<com.example.rocketplan_android.data.api.CrmContactApi>()

    suspend fun getBusinesses(
        search: String? = null,
        page: Int? = null,
        limit: Int? = null
    ): Result<PaginatedResponse<CrmBusinessDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching businesses page=$page search=${search?.take(20)}")
            val response = api.getBusinesses(
                companyId = companyId,
                search = search?.takeIf { it.isNotBlank() },
                sort = "name",
                page = page,
                limit = limit
            )
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                val count = body.data.size
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched $count businesses (page=${body.meta?.currentPage}/${body.meta?.lastPage})")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load businesses")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching businesses: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getBusiness(businessId: String): Result<CrmBusinessDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching business=$businessId")
            val response = api.getBusiness(companyId, businessId)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched business: id=${body.id}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load business")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching business: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createBusiness(request: CrmBusinessRequest): Result<CrmBusinessDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Creating business: name=${request.name}")
            val response = api.createBusiness(companyId, request)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Business created: id=${body.id}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to create business")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error creating business: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateBusiness(businessId: String, request: CrmBusinessRequest): Result<CrmBusinessDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Updating business: id=$businessId")
            val response = api.updateBusiness(companyId, businessId, request)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Business updated: id=${body.id}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to update business")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error updating business: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteBusiness(businessId: String): Result<Unit> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Deleting business: id=$businessId")
            val response = api.deleteBusiness(companyId, businessId)
            if (response.isSuccessful) {
                remoteLogger?.log(LogLevel.INFO, TAG, "Business deleted: id=$businessId")
                Result.success(Unit)
            } else {
                failWithErrorBody(response, "Failed to delete business")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error deleting business: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getCustomFieldDefinitions(model: String = "business"): Result<List<CrmCustomFieldDefinitionDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching custom field definitions for model=$model")
            val response = contactApi.getCustomFields(companyId, model = model)
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

    private fun <T> failWithErrorBody(response: Response<T>, prefix: String): Result<Nothing> {
        val errorBody = try { response.errorBody()?.string()?.take(500) } catch (_: Exception) { null }
        val msg = "$prefix: HTTP ${response.code()}${if (errorBody != null) " - $errorBody" else ""}"
        remoteLogger?.log(LogLevel.ERROR, TAG, msg)
        return Result.failure(IllegalStateException(msg))
    }

    companion object {
        private const val TAG = "CrmBusinesses"
    }
}
