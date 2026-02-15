package com.example.rocketplan_android.ui.crm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmBusinessDto
import com.example.rocketplan_android.data.repository.CrmBusinessRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CrmBusinessListItem(
    val id: String,
    val name: String,
    val phone: String?,
    val email: String?,
    val initials: String
)

data class CrmBusinessesUiState(
    val items: List<CrmBusinessListItem> = emptyList(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val isLoadingMore: Boolean = false
)

@OptIn(FlowPreview::class)
class CrmBusinessesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CrmBusinessRepository

    private val _uiState = MutableStateFlow(CrmBusinessesUiState())
    val uiState: StateFlow<CrmBusinessesUiState> = _uiState

    private val searchQueryFlow = MutableStateFlow("")

    init {
        val app = application as RocketPlanApplication
        repository = CrmBusinessRepository(app.authRepository, app.remoteLogger)

        viewModelScope.launch {
            searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    _uiState.update { it.copy(searchQuery = query) }
                    loadPage(page = 1, reset = true)
                }
        }

        loadPage(page = 1, reset = true)
    }

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun refresh() {
        loadPage(page = 1, reset = true)
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        loadPage(page = state.currentPage + 1, reset = false)
    }

    private fun loadPage(page: Int, reset: Boolean) {
        viewModelScope.launch {
            if (reset) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            } else {
                _uiState.update { it.copy(isLoadingMore = true) }
            }

            val state = _uiState.value
            val result = repository.getBusinesses(
                search = state.searchQuery.takeIf { it.isNotBlank() },
                page = page,
                limit = 50
            )

            result.onSuccess { response ->
                val newItems = response.data.map { it.toListItem() }
                val lastPage = response.meta?.lastPage ?: 1
                val hasMore = page < lastPage

                _uiState.update {
                    val allItems = if (reset) newItems else it.items + newItems
                    it.copy(
                        items = allItems,
                        isLoading = false,
                        isLoadingMore = false,
                        isEmpty = allItems.isEmpty(),
                        currentPage = page,
                        hasMore = hasMore,
                        error = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = if (reset && it.items.isEmpty()) (error.message ?: "Failed to load businesses") else it.error
                    )
                }
            }
        }
    }

    private fun CrmBusinessDto.toListItem(): CrmBusinessListItem {
        val displayName = name?.trim()?.ifBlank { null } ?: email ?: phone ?: "Unknown"

        val initials = buildString {
            val words = displayName.split(" ").filter { it.isNotBlank() }
            if (words.isNotEmpty()) append(words.first().first().uppercaseChar())
            if (words.size > 1) append(words[1].first().uppercaseChar())
            if (isEmpty()) append("?")
        }

        return CrmBusinessListItem(
            id = id ?: "",
            name = displayName,
            phone = phone,
            email = email,
            initials = initials
        )
    }
}
