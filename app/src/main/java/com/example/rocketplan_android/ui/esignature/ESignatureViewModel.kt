package com.example.rocketplan_android.ui.esignature

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.entity.OfflineClaimEntity
import com.example.rocketplan_android.data.model.PdfFormSubmissionDto
import com.example.rocketplan_android.data.model.PdfFormTemplateDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed class ESignatureUiState {
    object Loading : ESignatureUiState()
    data class Ready(
        val submissions: List<PdfFormSubmissionDto>,
        val templates: List<PdfFormTemplateDto>
    ) : ESignatureUiState()
    data class Error(val message: String) : ESignatureUiState()
}

sealed class ESignatureEvent {
    data class SubmissionCreated(val uuid: String) : ESignatureEvent()
    data class NavigateToSign(val uuid: String) : ESignatureEvent()
    data class ShowError(val message: String) : ESignatureEvent()
    data class OpenSignedUrl(val url: String) : ESignatureEvent()
}

class ESignatureViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val pdfFormRepository = app.pdfFormRepository
    private val localDataService = app.localDataService

    private val _uiState = MutableStateFlow<ESignatureUiState>(ESignatureUiState.Loading)
    val uiState: StateFlow<ESignatureUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _events = Channel<ESignatureEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var templates: List<PdfFormTemplateDto> = emptyList()
    private var cachedClaim: OfflineClaimEntity? = null
    private val claimsReady = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch { loadData() }
        viewModelScope.launch { loadClaimsForPrefill() }
    }

    private suspend fun loadData() {
        if (_uiState.value !is ESignatureUiState.Loading && !_isRefreshing.value) {
            _uiState.value = ESignatureUiState.Loading
        }

        val templatesResult = pdfFormRepository.getTemplates()
        val submissionsResult = pdfFormRepository.getSubmissions(projectId)

        if (templatesResult.isSuccess && submissionsResult.isSuccess) {
            templates = templatesResult.getOrDefault(emptyList())
            _uiState.value = ESignatureUiState.Ready(
                submissions = submissionsResult.getOrDefault(emptyList()),
                templates = templates
            )
        } else {
            val error = templatesResult.exceptionOrNull()?.message
                ?: submissionsResult.exceptionOrNull()?.message
                ?: "Unknown error"
            _uiState.value = ESignatureUiState.Error(error)
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            loadData()
            _isRefreshing.value = false
        }
    }

    fun createSubmission(
        templateId: Long,
        clientName: String?,
        clientEmail: String?,
        clientPhone: String?
    ) {
        if (_isCreating.value) return
        viewModelScope.launch {
            _isCreating.value = true
            val result = pdfFormRepository.createSubmission(
                templateId = templateId,
                projectId = projectId,
                clientName = clientName,
                clientEmail = clientEmail,
                clientPhone = clientPhone
            )
            _isCreating.value = false

            result.fold(
                onSuccess = { submission ->
                    // Reload list
                    loadData()
                    // Navigate to sign screen
                    submission.uuid?.let { uuid ->
                        _events.send(ESignatureEvent.SubmissionCreated(uuid))
                    }
                },
                onFailure = { error ->
                    _events.send(ESignatureEvent.ShowError(error.message ?: "Failed to create submission"))
                }
            )
        }
    }

    fun deleteSubmission(submission: PdfFormSubmissionDto) {
        val id = submission.id ?: return
        viewModelScope.launch {
            val result = pdfFormRepository.deleteSubmission(id)
            result.fold(
                onSuccess = { loadData() },
                onFailure = { error ->
                    _events.send(ESignatureEvent.ShowError(error.message ?: "Failed to delete submission"))
                }
            )
        }
    }

    fun onSubmissionClicked(submission: PdfFormSubmissionDto) {
        viewModelScope.launch {
            submission.uuid?.let { uuid ->
                _events.send(ESignatureEvent.NavigateToSign(uuid))
            }
        }
    }

    private suspend fun loadClaimsForPrefill() {
        try {
            val project = localDataService.getProject(projectId)
            val serverProjectId = project?.serverId
            Log.d(TAG, "loadClaimsForPrefill: projectId=$projectId serverId=$serverProjectId")
            if (serverProjectId == null) {
                Log.d(TAG, "loadClaimsForPrefill: no server ID for project $projectId")
                return
            }

            // Try local Room first (offline-first)
            val localClaims = localDataService.getProjectClaims(serverProjectId)
            Log.d(TAG, "loadClaimsForPrefill: ${localClaims.size} local claims for serverId=$serverProjectId")
            if (localClaims.isNotEmpty()) {
                cachedClaim = localClaims.first()
                Log.d(TAG, "loadClaimsForPrefill: loaded from Room")
                return
            }

            // Claims not synced locally yet (Loss Info not visited) — fetch from API
            val api = RetrofitClient.createService<OfflineSyncApi>()
            val response = api.getProjectClaims(serverProjectId, include = "claimType")
            Log.d(TAG, "loadClaimsForPrefill: API returned ${response.data.size} claims")
            val apiClaim = response.data.firstOrNull()
            if (apiClaim != null) {
                cachedClaim = OfflineClaimEntity(
                    claimId = apiClaim.id,
                    projectId = apiClaim.projectId,
                    policyHolder = apiClaim.policyHolder ?: apiClaim.claimInfo?.policyHolder,
                    policyHolderEmail = apiClaim.policyHolderEmail ?: apiClaim.claimInfo?.policyHolderEmail,
                    policyHolderPhone = apiClaim.policyHolderPhone ?: apiClaim.claimInfo?.policyHolderPhone
                )
                Log.d(TAG, "loadClaimsForPrefill: loaded from API, hasName=${cachedClaim?.policyHolder != null} hasEmail=${cachedClaim?.policyHolderEmail != null} hasPhone=${cachedClaim?.policyHolderPhone != null}")
            } else {
                Log.d(TAG, "loadClaimsForPrefill: no claims found for project")
            }
        } catch (e: Exception) {
            val body = (e as? retrofit2.HttpException)?.response()?.errorBody()?.string()
            Log.e(TAG, "loadClaimsForPrefill: failed (${e.message}) body=${body?.take(500)}", e)
        } finally {
            claimsReady.complete(Unit)
        }
    }

    suspend fun awaitClaimsLoaded() = claimsReady.await()

    fun getClientNamePrefill(): String {
        return cachedClaim?.policyHolder?.takeIf { it.isNotBlank() } ?: ""
    }

    fun getClientEmailPrefill(): String {
        return cachedClaim?.policyHolderEmail?.takeIf { it.isNotBlank() } ?: ""
    }

    fun getClientPhonePrefill(): String {
        return cachedClaim?.policyHolderPhone?.takeIf { it.isNotBlank() } ?: ""
    }

    companion object {
        private const val TAG = "ESignatureVM"

        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ESignatureViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return ESignatureViewModel(application, projectId) as T
            }
        }
    }
}
