package com.example.rocketplan_android.ui.crm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmContactDto
import com.example.rocketplan_android.data.repository.CrmBusinessRepository
import com.example.rocketplan_android.data.repository.CrmContactRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CrmContactListItem(
    val id: String,
    val displayName: String,
    val phone: String?,
    val email: String?,
    val type: String?,
    val initials: String,
    val companyName: String? = null
)

data class CrmContactsUiState(
    val items: List<CrmContactListItem> = emptyList(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val selectedType: String? = null,
    val hasMore: Boolean = false,
    val currentPage: Int = 1,
    val isLoadingMore: Boolean = false
)

@OptIn(FlowPreview::class)
class CrmContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CrmContactRepository
    private val businessRepository: CrmBusinessRepository

    private val _uiState = MutableStateFlow(CrmContactsUiState())
    val uiState: StateFlow<CrmContactsUiState> = _uiState

    private val searchQueryFlow = MutableStateFlow("")

    // Cache of businessId -> business name
    private var businessNameCache: MutableMap<String, String> = mutableMapOf()

    init {
        val app = application as RocketPlanApplication
        repository = CrmContactRepository(app.authRepository, app.remoteLogger)
        businessRepository = CrmBusinessRepository(app.authRepository, app.remoteLogger)

        // Debounced server-side search
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

    fun setSelectedType(type: String?) {
        _uiState.update { it.copy(selectedType = type) }
        loadPage(page = 1, reset = true)
    }

    fun refresh() {
        businessNameCache.clear()
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
            val result = repository.getContacts(
                search = state.searchQuery.takeIf { it.isNotBlank() },
                type = state.selectedType,
                page = page,
                limit = 50
            )

            result.onSuccess { response ->
                // Resolve business names for contacts with businessId
                val unresolvedIds = response.data
                    .mapNotNull { it.businessId?.takeIf { id -> id.isNotBlank() && id !in businessNameCache } }
                    .distinct()
                for (bizId in unresolvedIds) {
                    businessRepository.getBusiness(bizId).onSuccess { biz ->
                        biz.name?.takeIf { it.isNotBlank() }?.let { businessNameCache[bizId] = it }
                    }
                }

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
                        error = if (reset && it.items.isEmpty()) (error.message ?: "Failed to load contacts") else it.error
                    )
                }
            }
        }
    }

    private fun CrmContactDto.toListItem(): CrmContactListItem {
        val first = firstName?.trim().orEmpty()
        val last = lastName?.trim().orEmpty()
        val displayName = listOfNotNull(
            first.takeIf { it.isNotBlank() },
            last.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { email ?: phone ?: "Unknown" }

        val initials = buildString {
            if (first.isNotBlank()) append(first.first().uppercaseChar())
            if (last.isNotBlank()) append(last.first().uppercaseChar())
            if (isEmpty() && displayName.isNotBlank()) append(displayName.first().uppercaseChar())
        }.ifBlank { "?" }

        val resolvedCompany = businessId?.takeIf { it.isNotBlank() }?.let { businessNameCache[it] }

        return CrmContactListItem(
            id = id ?: "",
            displayName = displayName,
            phone = phone,
            email = email,
            type = type,
            initials = initials,
            companyName = resolvedCompany
        )
    }
}
