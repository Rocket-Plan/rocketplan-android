package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.CrmUserApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.CrmUserDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import retrofit2.Response

class CrmUserRepository(
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger? = null
) {

    private val api = RetrofitClient.createService<CrmUserApi>()

    suspend fun getUsers(
        role: String? = null,
        type: String? = null,
        email: String? = null,
        name: String? = null,
        sort: String? = null,
        page: Int? = null,
        limit: Int? = null
    ): Result<PaginatedResponse<CrmUserDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching GHL users for company=$companyId")
            val response = api.getUsers(
                companyId = companyId,
                role = role,
                type = type,
                email = email,
                name = name,
                sort = sort,
                page = page,
                limit = limit
            )
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${body.data.size} GHL users (page=${body.meta?.currentPage}/${body.meta?.lastPage})")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load GHL users")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching GHL users: ${e.message}")
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
        private const val TAG = "CrmUsers"
    }
}
