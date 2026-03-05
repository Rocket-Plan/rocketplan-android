package com.example.rocketplan_android.data.model

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type

data class PdfFormTemplateDto(
    val id: Long? = null,
    val name: String? = null,
    val status: String? = null,
    @SerializedName("has_signature")
    val hasSignature: Boolean? = null,
    @SerializedName("pdf_url")
    val pdfUrl: String? = null,
    val fields: List<PdfFormFieldDto>? = null
)

data class PdfFormFieldDto(
    val id: Long? = null,
    @SerializedName("field_name")
    val fieldName: String? = null,
    @SerializedName("field_type")
    val fieldType: String? = null,
    val page: Int? = null,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    @SerializedName("font_size")
    val fontSize: Float? = null,
    @SerializedName("user_fillable")
    val userFillable: Boolean? = null,
    val required: Boolean? = null
)

data class PdfFormSubmissionDto(
    val id: Long? = null,
    val uuid: String? = null,
    val template: PdfFormTemplateDto? = null,
    @SerializedName("pdf_form_template_id")
    val templateId: Long? = null,
    @SerializedName("client_name")
    val clientName: String? = null,
    @SerializedName("client_email")
    val clientEmail: String? = null,
    @SerializedName("client_phone")
    val clientPhone: String? = null,
    val status: String? = null,
    @SerializedName("prefilled_url")
    val prefilledUrl: String? = null,
    @SerializedName("signed_url")
    val signedUrl: String? = null,
    @SerializedName("field_values")
    @JsonAdapter(EmptyArrayToMapDeserializer::class)
    val fieldValues: Map<String, Any>? = null,
    @SerializedName("field_values_by_id")
    @JsonAdapter(EmptyArrayToMapDeserializer::class)
    val fieldValuesById: Map<String, Any>? = null,
    @SerializedName("signature_data")
    val signatureData: String? = null,
    @SerializedName("resolved_token_values")
    @JsonAdapter(EmptyArrayToMapDeserializer::class)
    val resolvedTokenValues: Map<String, Any>? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class CreatePdfFormSubmissionRequest(
    @SerializedName("pdf_form_template_id")
    val pdfFormTemplateId: Long,
    @SerializedName("company_id")
    val companyId: Long,
    @SerializedName("project_id")
    val projectId: Long,
    @SerializedName("client_name")
    val clientName: String? = null,
    @SerializedName("client_email")
    val clientEmail: String? = null,
    @SerializedName("client_phone")
    val clientPhone: String? = null
)

data class SignPdfFormRequest(
    @SerializedName("field_values_by_id")
    val fieldValuesById: Map<String, Any>,
    @SerializedName("signature_data")
    val signatureData: String? = null
)

data class PdfFormTemplateResponse(
    val data: List<PdfFormTemplateDto>
)

data class PdfFormSubmissionListResponse(
    val data: List<PdfFormSubmissionDto>
)

data class PdfFormSubmissionSingleResponse(
    val data: PdfFormSubmissionDto
)

data class PdfFormSignDataResponse(
    val data: PdfFormSubmissionDto? = null
) {
    /** Convenience accessors matching old API expectations */
    val submission: PdfFormSubmissionDto? get() = data
    val template: PdfFormTemplateDto? get() = data?.template
    val fields: List<PdfFormFieldDto>? get() = data?.template?.fields
}

/**
 * Custom Gson deserializer that handles the API returning `[]` instead of `{}`
 * for empty field_values / field_values_by_id maps.
 */
class EmptyArrayToMapDeserializer : JsonDeserializer<Map<String, Any>?> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): Map<String, Any>? {
        if (json == null || json.isJsonNull) return null
        if (json.isJsonArray) return emptyMap()
        if (json.isJsonObject) {
            val result = mutableMapOf<String, Any>()
            for ((key, value) in json.asJsonObject.entrySet()) {
                result[key] = when {
                    value.isJsonPrimitive -> {
                        val prim = value.asJsonPrimitive
                        when {
                            prim.isBoolean -> prim.asBoolean
                            prim.isNumber -> prim.asString
                            else -> prim.asString
                        }
                    }
                    else -> value.toString()
                }
            }
            return result
        }
        return null
    }
}
