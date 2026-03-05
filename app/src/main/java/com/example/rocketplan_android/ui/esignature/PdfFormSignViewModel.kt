package com.example.rocketplan_android.ui.esignature

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.model.PdfFormFieldDto
import com.example.rocketplan_android.data.model.PdfFormSubmissionDto
import com.example.rocketplan_android.data.model.PdfFormTemplateDto
import com.example.rocketplan_android.data.model.SignPdfFormRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter

sealed class PdfFormSignUiState {
    object Loading : PdfFormSignUiState()
    data class Ready(
        val submission: PdfFormSubmissionDto,
        val template: PdfFormTemplateDto,
        val fields: List<PdfFormFieldDto>,
        val pdfFile: File,
        val tokenDefaults: Map<String, String> = emptyMap(),
        val tokenLabels: Map<String, String> = emptyMap()
    ) : PdfFormSignUiState()
    data class Error(val message: String) : PdfFormSignUiState()
}

sealed class PdfFormSignEvent {
    object FormSigned : PdfFormSignEvent()
    data class SignFailed(val message: String) : PdfFormSignEvent()
}

class PdfFormSignViewModel(
    application: Application,
    private val submissionUuid: String,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val pdfFormRepository = app.pdfFormRepository
    private val localDataService = app.localDataService

    private val _uiState = MutableStateFlow<PdfFormSignUiState>(PdfFormSignUiState.Loading)
    val uiState: StateFlow<PdfFormSignUiState> = _uiState.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _events = Channel<PdfFormSignEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        loadSignData()
    }

    private fun loadSignData() {
        viewModelScope.launch {
            _uiState.value = PdfFormSignUiState.Loading
            Log.d(TAG, "loadSignData: submissionUuid=$submissionUuid")

            val result = pdfFormRepository.getSignData(submissionUuid)
            result.fold(
                onSuccess = { signData ->
                    val submission = signData.submission
                    val template = signData.template
                    val fields = signData.fields
                    Log.d(TAG, "loadSignData: submission=${submission?.id} template=${template?.id} fields=${fields?.size}")

                    if (submission == null || template == null) {
                        Log.e(TAG, "loadSignData: Invalid sign data - submission=$submission template=$template")
                        _uiState.value = PdfFormSignUiState.Error("Invalid sign data")
                        return@fold
                    }

                    // Download PDF to cache (prefer signed PDF for completed forms)
                    val pdfUrl = submission.signedUrl ?: submission.prefilledUrl ?: template.pdfUrl
                    Log.d(TAG, "loadSignData: hasPdfUrl=${!pdfUrl.isNullOrBlank()} (signed=${submission.signedUrl != null} prefilled=${submission.prefilledUrl != null} template=${template.pdfUrl != null})")
                    if (pdfUrl.isNullOrBlank()) {
                        _uiState.value = PdfFormSignUiState.Error("No PDF URL available")
                        return@fold
                    }

                    val pdfFile = downloadPdf(pdfUrl)
                    Log.d(TAG, "loadSignData: pdfFile=${pdfFile?.absolutePath} size=${pdfFile?.length()}")
                    if (pdfFile == null) {
                        _uiState.value = PdfFormSignUiState.Error("Failed to download PDF")
                        return@fold
                    }

                    // Build token defaults and labels from project data + backend resolved values
                    val (tokenDefaults, tokenLabels) = buildTokenMaps(submission.resolvedTokenValues)

                    _uiState.value = PdfFormSignUiState.Ready(
                        submission = submission,
                        template = template,
                        fields = fields ?: emptyList(),
                        pdfFile = pdfFile,
                        tokenDefaults = tokenDefaults,
                        tokenLabels = tokenLabels
                    )
                },
                onFailure = { error ->
                    Log.e(TAG, "loadSignData: failed", error)
                    _uiState.value = PdfFormSignUiState.Error(error.message ?: "Failed to load form data")
                }
            )
        }
    }

    private suspend fun buildTokenMaps(
        resolvedTokenValues: Map<String, Any>?
    ): Pair<Map<String, String>, Map<String, String>> {
        val defaults = mutableMapOf<String, String>()
        val labels = mutableMapOf(
            "{{current_date}}" to "Current Date",
            "{{policy_holder_name}}" to "Policy Holder Name",
            "{{project}}" to "Project",
            "{{date_of_loss}}" to "Date of Loss",
            "{{policy_number}}" to "Policy Number",
            "{{adjuster}}" to "Adjuster",
            "{{adjuster_phone}}" to "Adjuster Phone",
            "{{claim_number}}" to "Claim Number"
        )

        // Use backend resolved_token_values as primary source
        val displayDateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy")
        resolvedTokenValues?.forEach { (token, value) ->
            val strValue = value.toString()
            if (strValue.isNotBlank()) {
                // Backend may send with or without braces; normalize to {{token}}
                val key = if (token.startsWith("{{")) token else "{{$token}}"
                // Reformat ISO dates (yyyy-MM-dd) to display format (MMM dd, yyyy)
                val formatted = try {
                    LocalDate.parse(strValue).format(displayDateFormat)
                } catch (_: Exception) {
                    strValue
                }
                defaults[key] = formatted
            }
        }

        // Always provide current_date locally since server may not include it
        if (!defaults.containsKey("{{current_date}}")) {
            defaults["{{current_date}}"] = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
        }

        // Only fall back to local Room data when the server provided no resolved values at all.
        // The server is the source of truth for token defaults; local data should not override
        // or supplement server-resolved values to avoid stale data from previous forms.
        if (resolvedTokenValues.isNullOrEmpty()) {
            try {
                val project = localDataService.getProject(projectId)
                if (project != null && !defaults.containsKey("{{project}}")) {
                    defaults["{{project}}"] = project.title
                }

                project?.propertyId?.let { propId ->
                    val property = localDataService.getProperty(propId)
                    property?.lossDate?.takeIf { it.isNotBlank() }?.let { raw ->
                        if (!defaults.containsKey("{{date_of_loss}}")) {
                            defaults["{{date_of_loss}}"] = try {
                                LocalDate.parse(raw).format(displayDateFormat)
                            } catch (_: Exception) { raw }
                        }
                    }
                }

                val serverProjectId = project?.serverId ?: projectId
                val claims = localDataService.getProjectClaims(serverProjectId)
                val claim = claims.firstOrNull()
                if (claim != null) {
                    claim.policyHolder?.takeIf { it.isNotBlank() }?.let { if (!defaults.containsKey("{{policy_holder_name}}")) defaults["{{policy_holder_name}}"] = it }
                    claim.policyNumber?.takeIf { it.isNotBlank() }?.let { if (!defaults.containsKey("{{policy_number}}")) defaults["{{policy_number}}"] = it }
                    claim.claimNumber?.takeIf { it.isNotBlank() }?.let { if (!defaults.containsKey("{{claim_number}}")) defaults["{{claim_number}}"] = it }
                    claim.adjuster?.takeIf { it.isNotBlank() }?.let { if (!defaults.containsKey("{{adjuster}}")) defaults["{{adjuster}}"] = it }
                    claim.adjusterPhone?.takeIf { it.isNotBlank() }?.let { if (!defaults.containsKey("{{adjuster_phone}}")) defaults["{{adjuster_phone}}"] = it }
                }
            } catch (e: Exception) {
                Log.e(TAG, "buildTokenMaps: failed to load project data", e)
            }
        }

        Log.d(TAG, "buildTokenMaps: ${defaults.size} defaults resolved, keys=${defaults.keys}")
        return defaults to labels
    }

    private suspend fun downloadPdf(url: String): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "downloadPdf: starting download")
            val cacheDir = app.cacheDir
            val pdfFile = File(cacheDir, "pdf_form_${submissionUuid}.pdf")
            if (pdfFile.exists()) {
                Log.d(TAG, "downloadPdf: deleting existing cached file")
                pdfFile.delete()
            }

            val connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            // Only attach auth headers for non-S3 URLs; pre-signed S3 URLs
            // already contain auth params and S3 rejects duplicate auth.
            val isPreSignedS3 = url.contains("X-Amz-Signature") || url.contains("x-amz-signature")
            if (!isPreSignedS3) {
                RetrofitClient.getAuthToken()?.let { token ->
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                RetrofitClient.getCompanyId()?.let { companyId ->
                    connection.setRequestProperty("X-Company-Id", companyId.toString())
                }
            }
            Log.d(TAG, "downloadPdf: connecting... responseCode=${connection.responseCode}")
            connection.inputStream.use { input ->
                pdfFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "downloadPdf: saved to ${pdfFile.absolutePath} size=${pdfFile.length()} bytes")
            pdfFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadPdf: FAILED - ${e.message}", e)
            null
        }
    }

    fun submitSignature(fieldValuesById: Map<String, Any>, signatureData: String?) {
        if (_isSubmitting.value) return
        viewModelScope.launch {
            _isSubmitting.value = true
            Log.d(TAG, "submitSignature: uuid=$submissionUuid fields=${fieldValuesById.size} fieldKeys=${fieldValuesById.keys} hasSignature=${signatureData != null} sigLength=${signatureData?.length}")
            val request = SignPdfFormRequest(
                fieldValuesById = fieldValuesById,
                signatureData = signatureData
            )
            val result = pdfFormRepository.signForm(submissionUuid, request)
            _isSubmitting.value = false

            result.fold(
                onSuccess = {
                    Log.d(TAG, "submitSignature: SUCCESS")
                    _events.send(PdfFormSignEvent.FormSigned)
                },
                onFailure = { error ->
                    Log.e(TAG, "submitSignature: FAILED - ${error.message}", error)
                    _events.send(PdfFormSignEvent.SignFailed(error.message ?: "Failed to sign form"))
                }
            )
        }
    }

    companion object {
        private const val TAG = "PdfFormSign"

        fun provideFactory(
            application: Application,
            submissionUuid: String,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(PdfFormSignViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return PdfFormSignViewModel(application, submissionUuid, projectId) as T
            }
        }
    }
}
