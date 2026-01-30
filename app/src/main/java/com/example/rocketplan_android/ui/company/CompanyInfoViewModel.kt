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

    private val rocketPlanApp = application as RocketPlanApplication
    private val authRepository: AuthRepository = rocketPlanApp.authRepository
    private val localDataService = rocketPlanApp.localDataService

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
            val companyId = authRepository.getStoredCompanyId()
            val companyName = authRepository.getStoredCompanyName()?.takeIf { it.isNotBlank() }
            val userName = authRepository.getStoredUserName()?.takeIf { it.isNotBlank() }
            val userEmail = authRepository.getSavedEmail()
            val userId = authRepository.getStoredUserId()

            // Return cached content if we have the essentials
            if (companyName != null && userName != null) {
                val isCompanyAdmin = userId?.let {
                    try {
                        localDataService.isUserCompanyAdmin(it)
                    } catch (e: Exception) {
                        false
                    }
                } ?: false

                CompanyInfoUiState.Content(
                    companyName = companyName,
                    companyId = companyId,
                    logoUrl = null, // Logo URL not cached
                    userName = userName,
                    userEmail = userEmail ?: "",
                    isCompanyAdmin = isCompanyAdmin,
                    isRefreshing = false
                )
            } else {
                null
            }
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
                val storedCompanyId = authRepository.getStoredCompanyId()
                val companies = user.companies.orEmpty()
                // Prefer the stored active company, otherwise fall back to the first available company
                val selectedCompany = companies.firstOrNull { it.id == storedCompanyId }
                    ?: companies.firstOrNull()
                    ?: user.company
                val companyName = selectedCompany?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: resources.getString(R.string.company_info_unknown_company)
                val userName = listOfNotNull(user.firstName, user.lastName)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { user.email.ifBlank { resources.getString(R.string.company_info_unknown_user) } }

                // Check if user is company admin from local database
                val isCompanyAdmin = try {
                    localDataService.isUserCompanyAdmin(user.id)
                } catch (e: Exception) {
                    Log.w("CompanyInfoVM", "Failed to check admin status", e)
                    false
                }

                _uiState.value = CompanyInfoUiState.Content(
                    companyName = companyName,
                    companyId = selectedCompany?.id ?: storedCompanyId ?: user.getPrimaryCompanyId(),
                    logoUrl = selectedCompany?.logoUrl,
                    userName = userName,
                    userEmail = user.email,
                    isCompanyAdmin = isCompanyAdmin,
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
        val isCompanyAdmin: Boolean = false,
        val isRefreshing: Boolean = false
    ) : CompanyInfoUiState()

    data class Error(val message: String) : CompanyInfoUiState()
}
