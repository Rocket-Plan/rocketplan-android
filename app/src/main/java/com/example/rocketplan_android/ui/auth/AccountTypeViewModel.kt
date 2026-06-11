package com.example.rocketplan_android.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.storage.SecureStorage
import kotlinx.coroutines.launch

class AccountTypeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(SecureStorage.getInstance(application))

    private val _inviteResolved = MutableLiveData<Boolean?>(null)
    val inviteResolved: LiveData<Boolean?> = _inviteResolved

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private var hasCheckedInvite = false

    fun checkForInvitation() {
        if (hasCheckedInvite) return
        hasCheckedInvite = true

        val pendingInviteUuid = authRepository.getPendingInviteCompanyUuid()
        if (pendingInviteUuid == null) {
            _inviteResolved.value = false
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            val userId = authRepository.getStoredUserId() ?: 0L
            if (userId == 0L) {
                _isLoading.value = false
                _inviteResolved.value = false
                return@launch
            }

            val resolveResult = authRepository.resolveCompanyByUuid(pendingInviteUuid)
            if (resolveResult.isFailure) {
                authRepository.clearPendingInviteCompanyUuid()
                _isLoading.value = false
                _inviteResolved.value = false
                return@launch
            }

            val company = resolveResult.getOrNull()
            if (company == null) {
                authRepository.clearPendingInviteCompanyUuid()
                _isLoading.value = false
                _inviteResolved.value = false
                return@launch
            }

            val addUserResult = authRepository.addCompanyUser(company.id, userId)
            if (addUserResult.isFailure) {
                _errorMessage.value = addUserResult.exceptionOrNull()?.message
                    ?: "Failed to join company"
                _isLoading.value = false
                _inviteResolved.value = false
                return@launch
            }

            val setCompanyResult = authRepository.setActiveCompany(company.id)
            if (setCompanyResult.isFailure) {
                _errorMessage.value = "Company joined but failed to set active. Please restart."
                _isLoading.value = false
                _inviteResolved.value = false
                return@launch
            }

            authRepository.clearPendingInviteCompanyUuid()
            _isLoading.value = false
            _inviteResolved.value = true
        }
    }

    fun onInviteResolvedHandled() {
        _inviteResolved.value = null
    }
}
