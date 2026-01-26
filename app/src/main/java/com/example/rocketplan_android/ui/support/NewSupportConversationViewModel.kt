package com.example.rocketplan_android.ui.support

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.sync.SupportSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NewSupportConversationUiState(
    val isLoading: Boolean = false,
    val createdConversationId: Long? = null,
    val error: String? = null
)

class NewSupportConversationViewModel(
    application: Application,
    private val localDataService: LocalDataService,
    private val supportSyncService: SupportSyncService,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(NewSupportConversationUiState())
    val uiState: StateFlow<NewSupportConversationUiState> = _uiState.asStateFlow()

    fun createConversation(subject: String, message: String) {
        if (subject.isBlank() || message.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Subject and message are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val userId = authRepository.getStoredUserId() ?: 0L
                val conversation = supportSyncService.createConversation(
                    userId = userId,
                    categoryId = 0L, // No category required
                    subject = subject.trim(),
                    initialMessageBody = message.trim()
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    createdConversationId = conversation.conversationId
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to create conversation"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as RocketPlanApplication
                    return NewSupportConversationViewModel(
                        application = application,
                        localDataService = app.localDataService,
                        supportSyncService = app.supportSyncService,
                        authRepository = app.authRepository
                    ) as T
                }
            }
    }
}
