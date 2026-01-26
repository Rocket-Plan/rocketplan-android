package com.example.rocketplan_android.ui.support

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.repository.sync.SupportSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class SupportUiState(
    val conversations: List<SupportConversationItem> = emptyList(),
    val isLoading: Boolean = false,
    val isEmpty: Boolean = true,
    val error: String? = null
)

class SupportViewModel(
    application: Application,
    private val localDataService: LocalDataService,
    private val supportSyncService: SupportSyncService
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SupportUiState(isLoading = true))
    val uiState: StateFlow<SupportUiState> = _uiState.asStateFlow()

    init {
        loadConversations()
        refreshFromServer()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            localDataService.observeSupportConversations()
                .map { conversations ->
                    conversations.map { entity ->
                        val categoryName = localDataService.getSupportCategories()
                            .find { it.categoryId == entity.categoryId }
                            ?.name

                        SupportConversationItem(
                            id = entity.conversationId,
                            subject = entity.subject,
                            categoryName = categoryName,
                            status = entity.status,
                            unreadCount = entity.unreadCount,
                            lastMessageAt = entity.lastMessageAt ?: entity.createdAt,
                            syncStatus = entity.syncStatus
                        )
                    }
                }
                .collectLatest { items ->
                    _uiState.value = _uiState.value.copy(
                        conversations = items,
                        isEmpty = items.isEmpty(),
                        isLoading = false
                    )
                }
        }
    }

    private fun refreshFromServer() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            supportSyncService.syncCategories()
            val result = supportSyncService.syncConversations()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = result.exceptionOrNull()?.message
            )
        }
    }

    fun refresh() {
        refreshFromServer()
    }

    companion object {
        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as RocketPlanApplication
                    return SupportViewModel(
                        application = application,
                        localDataService = app.localDataService,
                        supportSyncService = app.supportSyncService
                    ) as T
                }
            }
    }
}
