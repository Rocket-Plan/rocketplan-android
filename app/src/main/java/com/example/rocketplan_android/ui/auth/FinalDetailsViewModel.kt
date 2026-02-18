package com.example.rocketplan_android.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

class FinalDetailsViewModel(
    application: Application,
    private val userId: Long,
    val isCreating: Boolean,
    private val email: String
) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))

    private val _firstName = MutableLiveData("")
    val firstName: LiveData<String> = _firstName

    private val _lastName = MutableLiveData("")
    val lastName: LiveData<String> = _lastName

    private val _companyName = MutableLiveData("")
    val companyName: LiveData<String> = _companyName

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _setupComplete = MutableLiveData(false)
    val setupComplete: LiveData<Boolean> = _setupComplete

    fun setFirstName(value: String) {
        _firstName.value = value
        _errorMessage.value = null
    }

    fun setLastName(value: String) {
        _lastName.value = value
        _errorMessage.value = null
    }

    fun setCompanyName(value: String) {
        _companyName.value = value
        _errorMessage.value = null
    }

    fun finish() {
        val first = _firstName.value?.trim() ?: ""
        val last = _lastName.value?.trim() ?: ""
        val company = _companyName.value?.trim() ?: ""
        val app = getApplication<Application>()

        if (first.isBlank()) {
            _errorMessage.value = app.getString(R.string.onboarding_first_name_required)
            return
        }
        if (last.isBlank()) {
            _errorMessage.value = app.getString(R.string.onboarding_last_name_required)
            return
        }
        if (isCreating && company.isBlank()) {
            _errorMessage.value = app.getString(R.string.onboarding_company_name_required)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            // Step 1: Update user profile
            val updateResult = authRepository.updateUser(
                userId = userId,
                firstName = first,
                lastName = last,
                email = email
            )
            if (updateResult.isFailure) {
                _errorMessage.value = updateResult.exceptionOrNull()?.message ?: "Failed to update profile"
                _isLoading.value = false
                return@launch
            }

            // Step 2: Create company if applicable
            if (isCreating) {
                val companyResult = authRepository.createCompany(company)
                if (companyResult.isFailure) {
                    _errorMessage.value = companyResult.exceptionOrNull()?.message ?: "Failed to create company"
                    _isLoading.value = false
                    return@launch
                }

                val newCompanyId = companyResult.getOrNull()?.id
                if (newCompanyId != null) {
                    // Step 2b: Explicitly link user to company.
                    // This endpoint has company.resolve middleware removed on the server.
                    authRepository.addCompanyUser(newCompanyId, userId)

                    // Step 2c: Set company context locally so subsequent API calls
                    // include the company header for ResolveActiveCompany middleware.
                    authRepository.setActiveCompany(newCompanyId)
                }
            }

            // Step 3: Refresh user context to pick up new company
            val contextResult = authRepository.refreshUserContext()
            if (contextResult.isFailure) {
                _errorMessage.value = "Account setup succeeded but failed to load company context. Please restart the app."
                _isLoading.value = false
                return@launch
            }

            // Verify company is now set
            val refreshedCompanyId = contextResult.getOrNull()?.getPrimaryCompanyId()
            if (refreshedCompanyId == null) {
                _errorMessage.value = "Company was created but not yet associated. Please try again."
                _isLoading.value = false
                return@launch
            }

            _isLoading.value = false
            _setupComplete.value = true
        }
    }

    fun onSetupCompleteHandled() {
        _setupComplete.value = false
    }
}
