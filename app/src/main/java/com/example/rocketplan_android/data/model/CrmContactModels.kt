package com.example.rocketplan_android.data.model

import com.google.gson.annotations.SerializedName

data class CrmContactDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    @SerializedName("first_name")
    val firstName: String? = null,
    @SerializedName("last_name")
    val lastName: String? = null,
    @SerializedName("display_name")
    val displayName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val type: String? = null,
    @SerializedName("business_id")
    val businessId: String? = null,
    @SerializedName("referred_by_id")
    val referredById: String? = null,
    @SerializedName("referred_by")
    val referredBy: CrmContactDto? = null,
    val address1: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerializedName("postal_code")
    val postalCode: String? = null,
    val country: String? = null,
    @SerializedName("company_name")
    val companyName: String? = null,
    val website: String? = null,
    val source: String? = null,
    @SerializedName("date_added")
    val dateAdded: String? = null,
    @SerializedName("date_updated")
    val dateUpdated: String? = null,
    @SerializedName("temp_id")
    val tempId: String? = null,
    @SerializedName("synced_at")
    val syncedAt: String? = null,
    @SerializedName("pending_sync")
    val pendingSync: Boolean? = null,
    @SerializedName("custom_fields")
    val customFields: List<CrmCustomFieldValueDto>? = null
)

data class CrmCustomFieldValueDto(
    val id: String? = null,
    @SerializedName("field_key")
    val fieldKey: String? = null,
    @SerializedName("field_name")
    val fieldName: String? = null,
    @SerializedName("field_value")
    val fieldValue: String? = null
)

data class CrmCustomFieldDefinitionDto(
    val id: String? = null,
    @SerializedName("location_id")
    val locationId: String? = null,
    val name: String? = null,
    val model: String? = null,
    @SerializedName("field_key")
    val fieldKey: String? = null,
    val placeholder: String? = null,
    @SerializedName("data_type")
    val dataType: String? = null,
    val position: Int? = null,
    @SerializedName("document_type")
    val documentType: String? = null,
    @SerializedName("parent_id")
    val parentId: String? = null,
    val standard: Boolean? = null,
    @SerializedName("picklist_options")
    val picklistOptions: List<String>? = null,
    @SerializedName("is_allowed_custom_option")
    val isAllowedCustomOption: Boolean? = null,
    val type: String? = null,
    @SerializedName("custom_field_type")
    val customFieldType: String? = null,
    @SerializedName("is_required")
    val isRequired: Boolean? = null,
    @SerializedName("is_editable")
    val isEditable: Boolean? = null,
    @SerializedName("object_type")
    val objectType: String? = null
)

data class CrmContactRequest(
    @SerializedName("first_name")
    val firstName: String,
    @SerializedName("last_name")
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val type: String? = null,
    val source: String? = null,
    val address1: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerializedName("postal_code")
    val postalCode: String? = null,
    val country: String? = null,
    @SerializedName("company_name")
    val companyName: String? = null,
    val website: String? = null,
    @SerializedName("business_id")
    val businessId: String? = null,
    @SerializedName("referred_by_id")
    val referredById: String? = null
)

data class CrmCustomFieldsRequest(
    @SerializedName("custom_fields")
    val customFields: List<Map<String, String?>>
)

data class SingleDataResponse<T>(
    val data: T
)
