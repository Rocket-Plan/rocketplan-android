package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Data transfer objects for Project/Loss Info surfaces.
 * Kept minimal and nullable to avoid breaking existing flows while
 * the UI is being built incrementally.
 */
data class DamageTypeDto(
    val id: Long,
    val name: String? = null,
    val title: String? = null
)

data class DamageCauseDto(
    val id: Long,
    val name: String? = null,
    @SerializedName("is_standard")
    val isStandard: Boolean? = null,
    @SerializedName("property_damage_type")
    val propertyDamageType: DamageTypeDto? = null
)

data class ClaimDto(
    val id: Long,
    @SerializedName("project_id")
    val projectId: Long? = null,
    @SerializedName("location_id")
    val locationId: Long? = null,
    @SerializedName("policy_holder")
    val policyHolder: String? = null,
    @SerializedName("ownership_status")
    val ownershipStatus: String? = null,
    @SerializedName("policy_holder_phone")
    val policyHolderPhone: String? = null,
    @SerializedName("policy_holder_email")
    val policyHolderEmail: String? = null,
    val representative: String? = null,
    val provider: String? = null,
    @SerializedName("insurance_deductible")
    val insuranceDeductible: String? = null,
    @SerializedName("policy_number")
    val policyNumber: String? = null,
    @SerializedName("claim_number")
    val claimNumber: String? = null,
    val adjuster: String? = null,
    @SerializedName("adjuster_phone")
    val adjusterPhone: String? = null,
    @SerializedName("adjuster_email")
    val adjusterEmail: String? = null,
    @SerializedName("claim_type")
    val claimType: ClaimTypeDto? = null,
    @SerializedName("created_at")
    val createdAt: Date? = null,
    @SerializedName("updated_at")
    val updatedAt: Date? = null
)

data class ClaimTypeDto(
    val id: Long,
    val name: String? = null
)

/**
 * Request bodies for claim CRUD. Mirrors iOS Claim service payload.
 */
data class ClaimMutationRequest(
    @SerializedName("policy_holder")
    val policyHolder: String? = null,
    @SerializedName("ownership_status")
    val ownershipStatus: String? = null,
    @SerializedName("policy_holder_phone")
    val policyHolderPhone: String? = null,
    @SerializedName("policy_holder_email")
    val policyHolderEmail: String? = null,
    val representative: String? = null,
    val provider: String? = null,
    @SerializedName("insurance_deductible")
    val insuranceDeductible: String? = null,
    @SerializedName("policy_number")
    val policyNumber: String? = null,
    @SerializedName("claim_number")
    val claimNumber: String? = null,
    val adjuster: String? = null,
    @SerializedName("adjuster_phone")
    val adjusterPhone: String? = null,
    @SerializedName("adjuster_email")
    val adjusterEmail: String? = null,
    @SerializedName("claim_type_id")
    val claimTypeId: Long? = null,
    @SerializedName("project_id")
    val projectId: Long? = null,
    @SerializedName("location_id")
    val locationId: Long? = null
)

/**
 * Feature flag payload to gate Project/Loss Info rollout.
 */
data class FeatureFlagResponse(
    val values: FeatureFlagValues
)

data class FeatureFlagValues(
    @SerializedName("project_loss_info")
    val projectLossInfo: Boolean? = null,
    @SerializedName("rocketscan_damages")
    val rocketScanDamages: Boolean? = null,
    @SerializedName("rocket_scope")
    val rocketScope: Boolean? = null,
    @SerializedName("rocket_pay")
    val rocketPay: Boolean? = null,
    @SerializedName("use_image_processor")
    val useImageProcessor: Boolean? = null
)
