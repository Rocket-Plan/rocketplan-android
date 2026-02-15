package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.CrmTaskDto
import com.example.rocketplan_android.data.model.CrmTaskRequest
import com.example.rocketplan_android.data.model.CrmTaskUpdateRequest
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import retrofit2.Response

class CrmTaskRepository(
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger? = null
) {
    private val api = RetrofitClient.createService<com.example.rocketplan_android.data.api.CrmTaskApi>()

    suspend fun getTasks(contactId: String, page: Int? = null): Result<PaginatedResponse<CrmTaskDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching tasks for contact=$contactId page=$page")
            val response = api.getTasks(companyId, contactId = contactId, page = page)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${body.data.size} tasks for contact=$contactId")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load tasks")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching tasks: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createTask(request: CrmTaskRequest): Result<CrmTaskDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Creating task: title=${request.title} contact=${request.contactId}")
            val response = api.createTask(companyId, request)
            if (response.isSuccessful) {
                val data = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Task created: id=${data.id}")
                Result.success(data)
            } else {
                failWithErrorBody(response, "Failed to create task")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error creating task: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateTask(taskId: String, request: CrmTaskUpdateRequest): Result<CrmTaskDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Updating task: id=$taskId")
            val response = api.updateTask(companyId, taskId, request)
            if (response.isSuccessful) {
                val data = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Task updated: id=${data.id}")
                Result.success(data)
            } else {
                failWithErrorBody(response, "Failed to update task")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error updating task: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteTask(taskId: String): Result<Unit> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Deleting task: id=$taskId")
            val response = api.deleteTask(companyId, taskId)
            if (response.isSuccessful) {
                remoteLogger?.log(LogLevel.INFO, TAG, "Task deleted: id=$taskId")
                Result.success(Unit)
            } else {
                failWithErrorBody(response, "Failed to delete task")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error deleting task: ${e.message}")
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
        private const val TAG = "CrmTasks"
    }
}
