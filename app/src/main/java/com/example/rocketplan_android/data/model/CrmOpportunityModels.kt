package com.example.rocketplan_android.data.model

import com.example.rocketplan_android.data.model.offline.PaginationMeta
import com.google.gson.annotations.SerializedName

data class CrmPipelineDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    val name: String? = null,
    val stages: List<CrmPipelineStageDto>? = null
)

data class CrmPipelineStageDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    @SerializedName("pipeline_id")
    val pipelineId: String? = null,
    val name: String? = null,
    @SerializedName("sort_order")
    val sortOrder: Int? = null
)

data class CrmReferralFeeDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    @SerializedName("opportunity_id")
    val opportunityId: String? = null,
    @SerializedName("referral_partner_id")
    val referralPartnerId: String? = null,
    @SerializedName("fee_type")
    val feeType: String? = null,
    @SerializedName("fee_value")
    val feeValue: Double? = null,
    @SerializedName("calculated_amount")
    val calculatedAmount: Double? = null,
    val status: String? = null,
    @SerializedName("paid_at")
    val paidAt: String? = null,
    val notes: String? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class CrmOpportunityDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    val name: String? = null,
    @SerializedName("monetary_value")
    val monetaryValue: Double? = null,
    @SerializedName("pipeline_id")
    val pipelineId: String? = null,
    @SerializedName("pipeline_stage_id")
    val pipelineStageId: String? = null,
    @SerializedName("contact_id")
    val contactId: String? = null,
    val contact: CrmContactDto? = null,
    @SerializedName("assigned_to")
    val assignedTo: String? = null,
    val status: String? = null,
    val source: String? = null,
    val pipeline: CrmPipelineDto? = null,
    val stage: CrmPipelineStageDto? = null,
    @SerializedName("last_status_change_at")
    val lastStatusChangeAt: String? = null,
    @SerializedName("referral_fee")
    val referralFee: CrmReferralFeeDto? = null,
    @SerializedName("referred_by")
    val referredBy: CrmContactDto? = null,
    @SerializedName("custom_fields")
    val customFields: List<CrmCustomFieldValueDto>? = null,
    @SerializedName("date_added")
    val dateAdded: String? = null,
    @SerializedName("temp_id")
    val tempId: String? = null,
    @SerializedName("synced_at")
    val syncedAt: String? = null,
    @SerializedName("pending_sync")
    val pendingSync: Boolean? = null,
    @SerializedName("created_at")
    val createdAt: String? = null
)

data class CrmOpportunityRequest(
    val name: String,
    @SerializedName("pipeline_id")
    val pipelineId: String,
    @SerializedName("pipeline_stage_id")
    val pipelineStageId: String,
    @SerializedName("contact_id")
    val contactId: String? = null,
    @SerializedName("monetary_value")
    val monetaryValue: Double? = null,
    val status: String? = null,
    val source: String? = null,
    @SerializedName("assigned_to")
    val assignedTo: String? = null,
    @SerializedName("referral_partner_id")
    val referralPartnerId: String? = null,
    @SerializedName("type_of_loss")
    val typeOfLoss: String? = null,
    @SerializedName("caller_class")
    val callerClass: String? = null,
    val notes: String? = null
)

data class CrmPipelinesResponse(
    val data: List<CrmPipelineDto>,
    val meta: PaginationMeta? = null
)
