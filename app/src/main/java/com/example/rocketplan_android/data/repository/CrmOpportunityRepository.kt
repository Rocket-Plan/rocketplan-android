package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.CrmCustomFieldsRequest
import com.example.rocketplan_android.data.model.CrmOpportunityDto
import com.example.rocketplan_android.data.model.CrmOpportunityRequest
import com.example.rocketplan_android.data.model.CrmPipelineDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import retrofit2.Response

class CrmOpportunityRepository(
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger? = null
) {

    private val api = RetrofitClient.createService<com.example.rocketplan_android.data.api.CrmOpportunityApi>()

    suspend fun getPipelines(): Result<List<CrmPipelineDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching pipelines for company=$companyId")
            val response = api.getPipelines(companyId)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched ${body.size} pipelines")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load pipelines")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching pipelines: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getOpportunity(opportunityId: String): Result<CrmOpportunityDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching opportunity=$opportunityId")
            val response = api.getOpportunity(companyId, opportunityId)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched opportunity: id=${body.id}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load opportunity")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching opportunity: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getOpportunities(
        pipelineId: String? = null,
        search: String? = null,
        page: Int? = null
    ): Result<PaginatedResponse<CrmOpportunityDto>> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.DEBUG, TAG, "Fetching opportunities pipeline=$pipelineId search=${search?.take(20)} page=$page")
            val response = api.getOpportunities(
                companyId = companyId,
                pipelineId = pipelineId,
                search = search?.takeIf { it.isNotBlank() },
                page = page
            )
            if (response.isSuccessful) {
                val body = response.body()
                    ?: return Result.failure(IllegalStateException("Empty response"))
                val count = body.data.size
                remoteLogger?.log(LogLevel.INFO, TAG, "Fetched $count opportunities (page=${body.meta?.currentPage}/${body.meta?.lastPage})")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to load opportunities")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error fetching opportunities: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createOpportunity(request: CrmOpportunityRequest): Result<CrmOpportunityDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Creating opportunity: name=${request.name} contactId=${request.contactId} pipelineId=${request.pipelineId} stageId=${request.pipelineStageId} monetaryValue=${request.monetaryValue} status=${request.status} source=${request.source}")
            val response = api.createOpportunity(companyId, request)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Opportunity created: id=${body.id} contactId=${body.contactId}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to create opportunity")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error creating opportunity: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateOpportunity(opportunityId: String, request: CrmOpportunityRequest): Result<CrmOpportunityDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Updating opportunity: id=$opportunityId contactId=${request.contactId} pipelineId=${request.pipelineId} stageId=${request.pipelineStageId} monetaryValue=${request.monetaryValue} status=${request.status}")
            val response = api.updateOpportunity(companyId, opportunityId, request)
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Opportunity updated: id=${body.id} contactId=${body.contactId}")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to update opportunity")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error updating opportunity: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateOpportunityStage(
        opportunityId: String,
        pipelineId: String,
        stageId: String,
        opportunity: CrmOpportunityDto
    ): Result<CrmOpportunityDto> {
        val request = CrmOpportunityRequest(
            name = opportunity.name ?: "",
            pipelineId = pipelineId,
            pipelineStageId = stageId,
            contactId = opportunity.contactId ?: opportunity.contact?.id,
            monetaryValue = opportunity.monetaryValue,
            status = opportunity.status,
            source = opportunity.source,
            assignedTo = opportunity.assignedTo
        )
        return updateOpportunity(opportunityId, request)
    }

    suspend fun updateOpportunityCustomFields(
        opportunityId: String,
        customFields: List<Map<String, String?>>
    ): Result<CrmOpportunityDto> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Updating custom fields for opportunity=$opportunityId (${customFields.size} fields)")
            val response = api.setOpportunityCustomFields(companyId, opportunityId, CrmCustomFieldsRequest(customFields))
            if (response.isSuccessful) {
                val body = response.body()?.data
                    ?: return Result.failure(IllegalStateException("Empty response"))
                remoteLogger?.log(LogLevel.INFO, TAG, "Custom fields updated for opportunity=$opportunityId")
                Result.success(body)
            } else {
                failWithErrorBody(response, "Failed to update custom fields")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error updating custom fields: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteOpportunity(opportunityId: String): Result<Unit> {
        val companyId = authRepository.getStoredCompanyId()
            ?: return Result.failure(IllegalStateException("No company selected"))

        return try {
            remoteLogger?.log(LogLevel.INFO, TAG, "Deleting opportunity: id=$opportunityId")
            val response = api.deleteOpportunity(companyId, opportunityId)
            if (response.isSuccessful) {
                remoteLogger?.log(LogLevel.INFO, TAG, "Opportunity deleted: id=$opportunityId")
                Result.success(Unit)
            } else {
                failWithErrorBody(response, "Failed to delete opportunity")
            }
        } catch (e: Exception) {
            remoteLogger?.log(LogLevel.ERROR, TAG, "Error deleting opportunity: ${e.message}")
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
        private const val TAG = "CrmOpportunities"
    }
}
