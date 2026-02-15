package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.CrmNoteDto
import com.example.rocketplan_android.data.model.CrmNoteRequest
import com.example.rocketplan_android.data.model.CrmNoteUpdateRequest
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import retrofit2.Response

class CrmNoteRepository(
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger? = null
) {
    private val api = RetrofitClient.createService<com.example.rocketplan_android.data.api.CrmNoteApi>()

    suspend fun getNotes(contactId: String, page: Int? = null): Result<PaginatedResponse<CrmNoteDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching notes for contact=$contactId page=$page")
            val response = api.getNotes(companyId, contactId = contactId, page = page)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${body.data.size} notes for contact=$contactId")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load notes")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching notes: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createNote(contactId: String, body: String): Result<CrmNoteDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Creating note for contact=$contactId")
            val response = api.createNote(companyId, CrmNoteRequest(contactId, body))
            if (response.isSuccessful) {
                val data = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Note created: id=${data.id}")
                Result.success(data)
            } else {
                failWithErrorBody(response, "Failed to create note")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error creating note: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateNote(noteId: String, body: String): Result<CrmNoteDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Updating note: id=$noteId")
            val response = api.updateNote(companyId, noteId, CrmNoteUpdateRequest(body))
            if (response.isSuccessful) {
                val data = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Note updated: id=${data.id}")
                Result.success(data)
            } else {
                failWithErrorBody(response, "Failed to update note")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error updating note: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteNote(noteId: String): Result<Unit> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Deleting note: id=$noteId")
            val response = api.deleteNote(companyId, noteId)
            if (response.isSuccessful) {
                remoteLogger?.log(LogLevel.INFO, TAG, "Note deleted: id=$noteId")
                Result.success(Unit)
            } else {
                failWithErrorBody(response, "Failed to delete note")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error deleting note: ${e.message}")
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
        private const val TAG = "CrmNotes"
    }
}
