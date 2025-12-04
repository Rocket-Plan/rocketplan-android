package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CreateAddressRequest
import com.example.rocketplan_android.data.model.CreateCompanyProjectRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shared view model for the create-project flow. Handles validation, submission and navigation
 * events for both the auto-complete and manual address entry screens.
 */
class CreateProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val authRepository = rocketPlanApp.authRepository
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val localDataService = rocketPlanApp.localDataService

    private val _uiState = MutableStateFlow(CreateProjectUiState())
    val uiState: StateFlow<CreateProjectUiState> = _uiState

    private val _events = MutableSharedFlow<CreateProjectEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<CreateProjectEvent> = _events

    private val _validationEvents = MutableSharedFlow<CreateProjectValidation>(extraBufferCapacity = 1)
    val validationEvents: SharedFlow<CreateProjectValidation> = _validationEvents

    init {
        loadRecentAddresses()
    }

    fun createProjectFromQuickAddress(address: String?) {
        val cleanedAddress = address?.trim().orEmpty()
        if (cleanedAddress.isEmpty()) {
            _validationEvents.tryEmit(CreateProjectValidation.AddressRequired)
            return
        }
        val addressRequest = CreateAddressRequest(address = cleanedAddress)
        submitProject(addressRequest)
    }

    fun createProjectFromManualAddress(
        street: String?,
        unit: String?,
        city: String?,
        state: String?,
        postalCode: String?,
        country: String?
    ) {
        val cleanedStreet = street?.trim().orEmpty()
        val cleanedCity = city?.trim().orEmpty()
        val cleanedState = state?.trim().orEmpty()
        val cleanedPostal = postalCode?.trim().orEmpty()
        val cleanedCountry = country?.trim().orEmpty()

        var hasError = false
        if (cleanedStreet.isEmpty()) {
            _validationEvents.tryEmit(CreateProjectValidation.StreetRequired)
            hasError = true
        }
        if (cleanedCity.isEmpty()) {
            _validationEvents.tryEmit(CreateProjectValidation.CityRequired)
            hasError = true
        }
        if (cleanedState.isEmpty()) {
            _validationEvents.tryEmit(CreateProjectValidation.StateRequired)
            hasError = true
        }
        if (cleanedPostal.isEmpty()) {
            _validationEvents.tryEmit(CreateProjectValidation.PostalRequired)
            hasError = true
        }
        if (cleanedCountry.isEmpty()) {
            _validationEvents.tryEmit(CreateProjectValidation.CountryRequired)
            hasError = true
        }

        if (hasError) return

        val addressRequest = CreateAddressRequest(
            address = cleanedStreet,
            address2 = unit?.trim().takeIf { !it.isNullOrBlank() },
            city = cleanedCity,
            state = cleanedState,
            zip = cleanedPostal,
            country = cleanedCountry
        )

        submitProject(addressRequest)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun submitProject(addressRequest: CreateAddressRequest) {
        if (_uiState.value.isSubmitting) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = runCatching {
                val companyId = authRepository.getStoredCompanyId()
                    ?: authRepository.refreshUserContext()
                        .mapCatching { response ->
                            response.companyId
                                ?: authRepository.getStoredCompanyId()
                        }
                        .getOrElse { error ->
                            throw error
                        }
                        ?: throw IllegalStateException(getApplication<Application>().getString(R.string.create_project_missing_company))

                val address = offlineSyncRepository.createAddress(addressRequest).getOrElse { throw it }
                val addressId = address.id
                    ?: throw IllegalStateException(getApplication<Application>().getString(R.string.create_project_missing_address_id))

                val projectRequest = CreateCompanyProjectRequest(
                    projectStatusId = DEFAULT_PROJECT_STATUS_ID,
                    addressId = addressId
                )

                offlineSyncRepository.createCompanyProject(
                    companyId = companyId,
                    request = projectRequest,
                    projectAddress = address,
                    addressRequest = addressRequest
                ).getOrThrow()
            }

            result.fold(
                onSuccess = { project ->
                    Log.d("CreateProjectVM", "Project created with id=${project.projectId}")
                    addRecentAddress(formatAddressForCache(addressRequest) ?: addressRequest.address)
                    syncQueueManager.prioritizeProject(project.projectId)
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = null) }
                    _events.emit(CreateProjectEvent.ProjectCreated(project.projectId))
                },
                onFailure = { error ->
                    Log.e("CreateProjectVM", "Failed to create project", error)
                    val fallbackMessage = getApplication<Application>().getString(R.string.create_project_generic_error)
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = error.message ?: fallbackMessage
                        )
                    }
                }
            )
        }
    }

    private fun loadRecentAddresses() {
        viewModelScope.launch {
            val addresses = runCatching {
                localDataService.getRecentAddresses(RECENT_ADDRESS_LIMIT)
            }.onFailure { error ->
                Log.w("CreateProjectVM", "Failed to load recent addresses", error)
            }.getOrDefault(emptyList())

            _uiState.update { it.copy(recentAddresses = addresses) }
        }
    }

    private fun addRecentAddress(address: String?) {
        val normalized = address?.trim().orEmpty()
        if (normalized.isEmpty()) return

        _uiState.update { state ->
            val merged = listOf(normalized) + state.recentAddresses
            state.copy(recentAddresses = merged.distinct().take(RECENT_ADDRESS_LIMIT))
        }
    }

    private fun formatAddressForCache(request: CreateAddressRequest): String? {
        val line1 = request.address?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = mutableListOf(line1)
        request.address2?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        val cityStateZip = listOfNotNull(
            request.city?.trim()?.takeIf { it.isNotEmpty() },
            request.state?.trim()?.takeIf { it.isNotEmpty() },
            request.zip?.trim()?.takeIf { it.isNotEmpty() },
            request.country?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString(" ")
        if (cityStateZip.isNotEmpty()) {
            parts.add(cityStateZip)
        }
        return parts.joinToString(", ")
    }

    companion object {
        private const val DEFAULT_PROJECT_STATUS_ID = 1
        private const val RECENT_ADDRESS_LIMIT = 10
    }
}

data class CreateProjectUiState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val recentAddresses: List<String> = emptyList()
)

sealed interface CreateProjectEvent {
    data class ProjectCreated(val projectId: Long) : CreateProjectEvent
}

sealed interface CreateProjectValidation {
    data object AddressRequired : CreateProjectValidation
    data object StreetRequired : CreateProjectValidation
    data object CityRequired : CreateProjectValidation
    data object StateRequired : CreateProjectValidation
    data object PostalRequired : CreateProjectValidation
    data object CountryRequired : CreateProjectValidation
}
