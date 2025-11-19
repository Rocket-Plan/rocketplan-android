package com.example.rocketplan_android.ui.company

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CompanyInfoViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository: AuthRepository = (application as RocketPlanApplication).authRepository

    private val _uiState = MutableStateFlow<CompanyInfoUiState>(CompanyInfoUiState.Loading)
    val uiState: StateFlow<CompanyInfoUiState> = _uiState

    init {
        loadCompanyInfo()
    }

    /**
     * Load company info, first from cache, then refresh from network
     */
    private fun loadCompanyInfo() {
        viewModelScope.launch {
            // Try to load from cache first
            val cachedData = loadFromCache()
            if (cachedData != null) {
                _uiState.value = cachedData
                Log.d("CompanyInfoVM", "Loaded cached company info")
            } else {
                _uiState.value = CompanyInfoUiState.Loading
            }

            // Refresh from network
            refreshFromNetwork()
        }
    }

    fun refreshCompanyInfo() {
        viewModelScope.launch {
            // If we have content, show it while refreshing
            val currentState = _uiState.value
            if (currentState is CompanyInfoUiState.Content) {
                _uiState.value = currentState.copy(isRefreshing = true)
            } else {
                _uiState.value = CompanyInfoUiState.Loading
            }

            refreshFromNetwork()
        }
    }

    private suspend fun loadFromCache(): CompanyInfoUiState.Content? {
        return try {
            val userId = authRepository.getStoredUserId()
            val companyId = authRepository.getStoredCompanyId()

            // TODO: In the future, cache full user/company details
            // For now, we don't have cached company name/logo, so return null
            null
        } catch (e: Exception) {
            Log.e("CompanyInfoVM", "Failed to load from cache", e)
            null
        }
    }

    private suspend fun refreshFromNetwork() {
        val result = authRepository.refreshUserContext()

        result.fold(
            onSuccess = { user ->
                val resources = getApplication<Application>().resources
                // Get primary company from companies array or fallback to single company object
                val primaryCompany = user.companies?.firstOrNull() ?: user.company
                val companyName = primaryCompany?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: resources.getString(R.string.company_info_unknown_company)
                val userName = listOfNotNull(user.firstName, user.lastName)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { user.email.ifBlank { resources.getString(R.string.company_info_unknown_user) } }

                _uiState.value = CompanyInfoUiState.Content(
                    companyName = companyName,
                    companyId = user.getPrimaryCompanyId(),
                    logoUrl = primaryCompany?.logoUrl,
                    userName = userName,
                    userEmail = user.email,
                    isRefreshing = false
                )
            },
            onFailure = { error ->
                // If we already have content, keep it and just log the error
                val currentState = _uiState.value
                if (currentState is CompanyInfoUiState.Content) {
                    Log.w("CompanyInfoVM", "Failed to refresh, keeping cached data", error)
                    _uiState.value = currentState.copy(isRefreshing = false)
                } else {
                    val fallback = getApplication<Application>().getString(R.string.company_info_generic_error)
                    _uiState.value = CompanyInfoUiState.Error(
                        message = error.message?.takeIf { it.isNotBlank() } ?: fallback
                    )
                }
            }
        )
    }
}

sealed class CompanyInfoUiState {
    data object Loading : CompanyInfoUiState()
    data class Content(
        val companyName: String,
        val companyId: Long?,
        val logoUrl: String?,
        val userName: String,
        val userEmail: String,
        val isRefreshing: Boolean = false
    ) : CompanyInfoUiState()

    data class Error(val message: String) : CompanyInfoUiState()
}
