package com.example.rocketplan_android.ui.people

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonListItem(
    val id: Long,
    val name: String,
    val email: String,
    val isAdmin: Boolean,
    val isCurrentUser: Boolean
)

data class PeopleUiState(
    val items: List<PersonListItem> = emptyList(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
    val isCurrentUserAdmin: Boolean = false,
    val error: String? = null
)

class PeopleViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val authRepository = rocketPlanApp.authRepository

    private val _uiState = MutableStateFlow(PeopleUiState())
    val uiState: StateFlow<PeopleUiState> = _uiState

    private var currentUserId: Long? = null

    init {
        loadUsers()
    }

    private fun loadUsers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // Get current user context
                currentUserId = authRepository.getStoredUserId()
                val companyId = authRepository.getStoredCompanyId()

                if (companyId == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isEmpty = true,
                            error = "No company selected"
                        )
                    }
                    return@launch
                }

                // Check if current user is admin
                val isCurrentUserAdmin = currentUserId?.let { userId ->
                    localDataService.isUserCompanyAdmin(userId)
                } ?: false

                // Observe users for the company
                localDataService.observeUsersForCompany(companyId)
                    .collect { users ->
                        val items = users.map { user ->
                            user.toListItem()
                        }
                        _uiState.update {
                            it.copy(
                                items = items,
                                isLoading = false,
                                isEmpty = items.isEmpty(),
                                isCurrentUserAdmin = isCurrentUserAdmin
                            )
                        }
                    }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load users"
                    )
                }
            }
        }
    }

    private suspend fun OfflineUserEntity.toListItem(): PersonListItem {
        val displayName = listOfNotNull(
            firstName?.takeIf { it.isNotBlank() },
            lastName?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { email }

        val isAdmin = serverId?.let { localDataService.isUserCompanyAdmin(it) } ?: false
        val isCurrentUser = serverId == currentUserId || userId == currentUserId

        return PersonListItem(
            id = serverId ?: userId,
            name = displayName,
            email = email,
            isAdmin = isAdmin,
            isCurrentUser = isCurrentUser
        )
    }

    fun refresh() {
        loadUsers()
    }
}
