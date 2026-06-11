package com.example.rocketplan_android.ui.auth

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import com.example.rocketplan_android.util.InviteLink
import kotlinx.coroutines.launch

class JoinCompanyViewModel(
    application: Application,
    private val userId: Long
) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))

    private val _companyCode = MutableLiveData("")
    val companyCode: LiveData<String> = _companyCode

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _joinComplete = MutableLiveData(false)
    val joinComplete: LiveData<Boolean> = _joinComplete

    fun setCompanyCode(value: String) {
        _companyCode.value = value
        _errorMessage.value = null
    }

    fun join() {
        val code = _companyCode.value?.trim() ?: ""
        if (code.isBlank()) {
            _errorMessage.value = getApplication<Application>().getString(R.string.onboarding_join_company_code_required)
            return
        }

        val uuid = extractUuid(code)
        if (uuid == null) {
            _errorMessage.value = getApplication<Application>().getString(R.string.onboarding_join_company_not_found)
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val resolveResult = authRepository.resolveCompanyByUuid(uuid)
            if (resolveResult.isFailure) {
                _errorMessage.value = resolveResult.exceptionOrNull()?.message
                    ?: getApplication<Application>().getString(R.string.onboarding_join_company_not_found)
                _isLoading.value = false
                return@launch
            }

            val company = resolveResult.getOrNull()
            if (company == null) {
                _errorMessage.value = getApplication<Application>().getString(R.string.onboarding_join_company_not_found)
                _isLoading.value = false
                return@launch
            }

            val addUserResult = authRepository.addCompanyUser(company.id, userId)
            if (addUserResult.isFailure) {
                _errorMessage.value = addUserResult.exceptionOrNull()?.message
                    ?: getApplication<Application>().getString(R.string.onboarding_join_failed)
                _isLoading.value = false
                return@launch
            }

            val setCompanyResult = authRepository.setActiveCompany(company.id)
            if (setCompanyResult.isFailure) {
                _errorMessage.value = "Company joined but failed to set active. Please restart."
                _isLoading.value = false
                return@launch
            }

            val contextResult = authRepository.refreshUserContext()
            if (contextResult.isFailure) {
                _errorMessage.value = "Company joined but failed to load context. Please restart."
                _isLoading.value = false
                return@launch
            }

            _isLoading.value = false
            _joinComplete.value = true
        }
    }

    private fun extractUuid(input: String): String? {
        val trimmed = input.trim()
        val inviteLink = try {
            InviteLink.parse(Uri.parse(trimmed))
        } catch (e: Exception) {
            null
        }
        return inviteLink?.companyUuid ?: trimmed
    }

    fun onJoinCompleteHandled() {
        _joinComplete.value = false
    }
}
