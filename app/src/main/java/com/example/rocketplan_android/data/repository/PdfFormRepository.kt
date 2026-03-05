package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.PdfFormApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.CreatePdfFormSubmissionRequest
import com.example.rocketplan_android.data.model.PdfFormSignDataResponse
import com.example.rocketplan_android.data.model.PdfFormSubmissionDto
import com.example.rocketplan_android.data.model.PdfFormTemplateDto
import com.example.rocketplan_android.data.model.SharePdfFormSubmissionRequest
import com.example.rocketplan_android.data.model.SignPdfFormRequest
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import retrofit2.Response
import java.io.File

class PdfFormRepository(
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger? = null
) {

    private val api = RetrofitClient.createService<PdfFormApi>()

    suspend fun getTemplates(): Result<List<PdfFormTemplateDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching PDF form templates")
            val response = api.getTemplates(companyId)
            if (response.isSuccessful) {
                val templates = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${templates.size} PDF form templates")
                Result.success(templates)
            } else {
                failWithErrorBody(response, "Failed to load templates")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching templates: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getSubmissions(projectId: Long): Result<List<PdfFormSubmissionDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching PDF form submissions for project=$projectId")
            val response = api.getSubmissions(companyId, projectId)
            if (response.isSuccessful) {
                val submissions = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${submissions.size} PDF form submissions")
                Result.success(submissions)
            } else {
                failWithErrorBody(response, "Failed to load submissions")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching submissions: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createSubmission(
        templateId: Long,
        projectId: Long,
        clientName: String?,
        clientEmail: String?,
        clientPhone: String?
    ): Result<PdfFormSubmissionDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Creating PDF form submission: template=$templateId project=$projectId")
            val request = CreatePdfFormSubmissionRequest(
                pdfFormTemplateId = templateId,
                companyId = companyId,
                projectId = projectId,
                clientName = clientName,
                clientEmail = clientEmail,
                clientPhone = clientPhone
            )
            val response = api.createSubmission(request)
            if (response.isSuccessful) {
                val submission = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Created submission: id=${submission.id} uuid=${submission.uuid}")
                Result.success(submission)
            } else {
                failWithErrorBody(response, "Failed to create submission")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error creating submission: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getSignData(uuid: String): Result<PdfFormSignDataResponse> {
        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching sign data for uuid=$uuid")
            val response = api.getSignData(uuid)
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched sign data: fields=${body.fields?.size}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load sign data")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching sign data: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signForm(uuid: String, request: SignPdfFormRequest): Result<PdfFormSubmissionDto> {
        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Signing form uuid=$uuid fields=${request.fieldValuesById.size} hasSignature=${request.signatureData != null}")
            val response = api.signForm(uuid, request)
            if (response.isSuccessful) {
                val submission = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Form signed: uuid=$uuid status=${submission.status}")
                Result.success(submission)
            } else {
                failWithErrorBody(response, "Failed to sign form")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error signing form: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun shareSubmission(
        id: Long,
        email: String? = null,
        phone: String? = null
    ): Result<PdfFormSubmissionDto> {
        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Sharing submission id=$id email=${email != null} phone=${phone != null}")
            val request = SharePdfFormSubmissionRequest(email = email, phone = phone)
            val response = api.shareSubmission(id, request)
            if (response.isSuccessful) {
                val submission = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Shared submission id=$id")
                Result.success(submission)
            } else {
                failWithErrorBody(response, "Failed to share submission")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error sharing submission: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteSubmission(id: Long): Result<Unit> {
        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Deleting submission id=$id")
            val response = api.deleteSubmission(id)
            if (response.isSuccessful) {
                remoteLogger?.log(LogLevel.INFO, TAG, "Deleted submission id=$id")
                Result.success(Unit)
            } else {
                failWithErrorBody(response, "Failed to delete submission")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error deleting submission: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun downloadPdf(url: String, cacheDir: File, fileName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Downloading PDF: $fileName")
            val pdfFile = File(cacheDir, fileName)
            if (pdfFile.exists()) pdfFile.delete()

            val isPreSignedS3 = url.contains("X-Amz-Signature", ignoreCase = true)
            val requestBuilder = Request.Builder().url(url)
            if (!isPreSignedS3) {
                RetrofitClient.getAuthToken()?.let { token ->
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                }
                RetrofitClient.getCompanyId()?.let { companyId ->
                    requestBuilder.addHeader("X-Company-Id", companyId.toString())
                }
            }

            val response = RetrofitClient.plainHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("PDF download failed: HTTP ${resp.code}"))
                }

                resp.body?.byteStream()?.use { input ->
                    pdfFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext Result.failure(IllegalStateException("Empty response body"))
            }

            remoteLogger?.log(LogLevel.INFO, TAG, "Downloaded PDF: ${pdfFile.length()} bytes")
            Result.success(pdfFile)
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error downloading PDF: ${e.message}")
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
        private const val TAG = "PdfForms"
    }
}
