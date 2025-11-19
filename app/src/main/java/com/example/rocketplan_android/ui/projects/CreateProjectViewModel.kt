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

    private val _uiState = MutableStateFlow(CreateProjectUiState())
    val uiState: StateFlow<CreateProjectUiState> = _uiState

    private val _events = MutableSharedFlow<CreateProjectEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<CreateProjectEvent> = _events

    private val _validationEvents = MutableSharedFlow<CreateProjectValidation>(extraBufferCapacity = 1)
    val validationEvents: SharedFlow<CreateProjectValidation> = _validationEvents

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
        postalCode: String?
    ) {
        val cleanedStreet = street?.trim().orEmpty()
        if (cleanedStreet.isEmpty()) {
            _validationEvents.tryEmit(CreateProjectValidation.StreetRequired)
            return
        }

        val addressRequest = CreateAddressRequest(
            address = cleanedStreet,
            address2 = unit?.trim().takeIf { !it.isNullOrBlank() },
            city = city?.trim().takeIf { !it.isNullOrBlank() },
            state = state?.trim().takeIf { !it.isNullOrBlank() },
            zip = postalCode?.trim().takeIf { !it.isNullOrBlank() }
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
            _uiState.value = CreateProjectUiState(isSubmitting = true, errorMessage = null)
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

                offlineSyncRepository.createCompanyProject(companyId, projectRequest).getOrThrow()
            }

            result.fold(
                onSuccess = { project ->
                    Log.d("CreateProjectVM", "Project created with id=${project.projectId}")
                    syncQueueManager.prioritizeProject(project.projectId)
                    _uiState.value = CreateProjectUiState(isSubmitting = false, errorMessage = null)
                    _events.emit(CreateProjectEvent.ProjectCreated(project.projectId))
                },
                onFailure = { error ->
                    Log.e("CreateProjectVM", "Failed to create project", error)
                    val fallbackMessage = getApplication<Application>().getString(R.string.create_project_generic_error)
                    _uiState.value = CreateProjectUiState(
                        isSubmitting = false,
                        errorMessage = error.message ?: fallbackMessage
                    )
                }
            )
        }
    }

    companion object {
        private const val DEFAULT_PROJECT_STATUS_ID = 1
    }
}

data class CreateProjectUiState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null
)

sealed interface CreateProjectEvent {
    data class ProjectCreated(val projectId: Long) : CreateProjectEvent
}

sealed interface CreateProjectValidation {
    data object AddressRequired : CreateProjectValidation
    data object StreetRequired : CreateProjectValidation
}
