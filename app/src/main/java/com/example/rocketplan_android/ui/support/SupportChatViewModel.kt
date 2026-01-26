package com.example.rocketplan_android.ui.support

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineSupportConversationEntity
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.sync.SupportSyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class SupportChatUiState(
    val conversation: OfflineSupportConversationEntity? = null,
    val messages: List<SupportMessageItem> = emptyList(),
    val isLoading: Boolean = false,
    val isClosed: Boolean = false,
    val error: String? = null
)

class SupportChatViewModel(
    application: Application,
    private val conversationId: Long,
    private val localDataService: LocalDataService,
    private val supportSyncService: SupportSyncService,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SupportChatUiState(isLoading = true))
    val uiState: StateFlow<SupportChatUiState> = _uiState.asStateFlow()

    init {
        loadConversation()
        loadMessages()
    }

    private fun loadConversation() {
        viewModelScope.launch {
            val conversation = localDataService.getSupportConversation(conversationId)
            _uiState.value = _uiState.value.copy(
                conversation = conversation,
                isClosed = conversation?.status == "closed"
            )

            // Mark as read and sync messages if we have a valid serverId
            conversation?.let {
                val serverId = it.serverId
                if (serverId != null && serverId > 0) {
                    supportSyncService.markAsRead(it)
                    supportSyncService.syncMessages(serverId)
                }
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            localDataService.observeSupportMessages(conversationId)
                .map { messages ->
                    messages.map { entity ->
                        SupportMessageItem(
                            id = entity.messageId,
                            body = entity.body,
                            senderType = entity.senderType,
                            createdAt = entity.createdAt,
                            syncStatus = entity.syncStatus
                        )
                    }
                }
                .collectLatest { items ->
                    _uiState.value = _uiState.value.copy(
                        messages = items,
                        isLoading = false
                    )
                }
        }
    }

    fun sendMessage(body: String) {
        val conversation = _uiState.value.conversation ?: return
        if (body.isBlank()) return

        viewModelScope.launch {
            val userId = authRepository.getStoredUserId() ?: 0L
            supportSyncService.sendMessage(conversation, userId, body.trim())
        }
    }

    fun closeConversation() {
        val conversation = _uiState.value.conversation ?: return

        viewModelScope.launch {
            val result = supportSyncService.closeConversation(conversation)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isClosed = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun refresh() {
        val conversation = _uiState.value.conversation ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val serverId = conversation.serverId
            if (serverId != null && serverId > 0) {
                supportSyncService.syncMessages(serverId)
            }
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    companion object {
        fun provideFactory(
            application: Application,
            conversationId: Long
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as RocketPlanApplication
                    return SupportChatViewModel(
                        application = application,
                        conversationId = conversationId,
                        localDataService = app.localDataService,
                        supportSyncService = app.supportSyncService,
                        authRepository = app.authRepository
                    ) as T
                }
            }
    }
}
