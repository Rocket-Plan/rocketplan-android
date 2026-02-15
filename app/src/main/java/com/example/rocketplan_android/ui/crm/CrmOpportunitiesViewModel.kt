package com.example.rocketplan_android.ui.crm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmOpportunityDto
import com.example.rocketplan_android.data.model.CrmPipelineDto
import com.example.rocketplan_android.data.model.CrmPipelineStageDto
import com.example.rocketplan_android.data.repository.CrmOpportunityRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpportunityCard(
    val id: String,
    val name: String,
    val contactId: String?,
    val contactName: String?,
    val monetaryValue: Double?,
    val status: String?,
    val source: String?,
    val assignedTo: String?,
    val customFields: List<Pair<String, String>> = emptyList()
)

data class KanbanColumn(
    val stageId: String,
    val stageName: String,
    val sortOrder: Int,
    val opportunities: List<OpportunityCard>,
    val monetaryTotal: Double = 0.0
)

data class CrmOpportunitiesUiState(
    val pipelines: List<CrmPipelineDto> = emptyList(),
    val selectedPipelineId: String? = null,
    val columns: List<KanbanColumn> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val selectedStatuses: Set<String> = setOf("open", "won", "abandoned")
) {
    val selectedPipelineName: String?
        get() = pipelines.find { it.id == selectedPipelineId }?.name
}

sealed class CrmOpportunitiesEvent {
    data class ShowError(val message: String) : CrmOpportunitiesEvent()
    data object StageMoved : CrmOpportunitiesEvent()
}

@OptIn(FlowPreview::class)
class CrmOpportunitiesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CrmOpportunityRepository

    private val _uiState = MutableStateFlow(CrmOpportunitiesUiState())
    val uiState: StateFlow<CrmOpportunitiesUiState> = _uiState

    private val _events = MutableSharedFlow<CrmOpportunitiesEvent>()
    val events: SharedFlow<CrmOpportunitiesEvent> = _events

    // Unfiltered columns for optimistic UI updates
    private var allColumns: List<KanbanColumn> = emptyList()

    // All loaded opportunity DTOs for stage move data preservation
    private var allOpportunityDtos: MutableMap<String, CrmOpportunityDto> = mutableMapOf()

    private val searchQueryFlow = MutableStateFlow("")

    init {
        val app = application as RocketPlanApplication
        repository = CrmOpportunityRepository(app.authRepository, app.remoteLogger)

        // Debounced server-side search
        viewModelScope.launch {
            searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    _uiState.update { it.copy(searchQuery = query) }
                    val pipelineId = _uiState.value.selectedPipelineId
                    if (pipelineId != null) {
                        loadOpportunities(pipelineId, search = query.takeIf { it.isNotBlank() })
                    }
                }
        }

        loadPipelines()
    }

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun toggleStatus(status: String) {
        val normalized = status.lowercase()
        _uiState.update { state ->
            val newStatuses = if (normalized in state.selectedStatuses) {
                state.selectedStatuses - normalized
            } else {
                state.selectedStatuses + normalized
            }
            state.copy(
                selectedStatuses = newStatuses,
                columns = filterColumnsByStatus(allColumns, newStatuses)
            )
        }
    }

    fun selectPipeline(pipelineId: String) {
        _uiState.update { it.copy(selectedPipelineId = pipelineId) }
        loadOpportunities(pipelineId, search = _uiState.value.searchQuery.takeIf { it.isNotBlank() })
    }

    fun refresh() {
        val pipelineId = _uiState.value.selectedPipelineId
        if (pipelineId != null) {
            loadOpportunities(pipelineId, search = _uiState.value.searchQuery.takeIf { it.isNotBlank() })
        } else {
            loadPipelines()
        }
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return
        val pipelineId = state.selectedPipelineId ?: return
        loadOpportunitiesPage(pipelineId, page = state.currentPage + 1, search = state.searchQuery.takeIf { it.isNotBlank() })
    }

    private fun loadPipelines() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            repository.getPipelines().onSuccess { pipelines ->
                val firstId = pipelines.firstOrNull()?.id
                _uiState.update {
                    it.copy(
                        pipelines = pipelines,
                        selectedPipelineId = firstId,
                        isLoading = firstId == null,
                        error = null
                    )
                }
                if (firstId != null) {
                    loadOpportunities(firstId)
                } else {
                    _uiState.update { it.copy(isLoading = false, columns = emptyList()) }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to load pipelines")
                }
            }
        }
    }

    private fun loadOpportunities(pipelineId: String, search: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val pipeline = _uiState.value.pipelines.find { it.id == pipelineId }
            val stages = pipeline?.stages?.sortedBy { it.sortOrder } ?: emptyList()

            val result = repository.getOpportunities(pipelineId = pipelineId, search = search, page = 1)

            result.onSuccess { response ->
                val opportunities = response.data
                val lastPage = response.meta?.lastPage ?: 1
                val hasMore = 1 < lastPage

                // Cache DTOs
                opportunities.forEach { dto -> dto.id?.let { allOpportunityDtos[it] = dto } }

                val columns = buildColumns(stages, opportunities)
                allColumns = columns

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        columns = filterColumnsByStatus(columns, it.selectedStatuses),
                        currentPage = 1,
                        hasMore = hasMore,
                        error = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, error = error.message ?: "Failed to load opportunities")
                }
            }
        }
    }

    private fun loadOpportunitiesPage(pipelineId: String, page: Int, search: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }

            val pipeline = _uiState.value.pipelines.find { it.id == pipelineId }
            val stages = pipeline?.stages?.sortedBy { it.sortOrder } ?: emptyList()

            val result = repository.getOpportunities(pipelineId = pipelineId, search = search, page = page)

            result.onSuccess { response ->
                val newOpportunities = response.data
                val lastPage = response.meta?.lastPage ?: 1
                val hasMore = page < lastPage

                // Cache DTOs
                newOpportunities.forEach { dto -> dto.id?.let { allOpportunityDtos[it] = dto } }

                // Merge new cards into existing columns
                val newCardsByStage = newOpportunities.groupBy { it.pipelineStageId }
                val mergedColumns = allColumns.map { column ->
                    val additionalCards = newCardsByStage[column.stageId]?.map { it.toCard() } ?: emptyList()
                    if (additionalCards.isNotEmpty()) {
                        val allOpps = column.opportunities + additionalCards
                        column.copy(
                            opportunities = allOpps,
                            monetaryTotal = allOpps.sumOf { it.monetaryValue ?: 0.0 }
                        )
                    } else {
                        column
                    }
                }

                allColumns = mergedColumns
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        columns = filterColumnsByStatus(mergedColumns, it.selectedStatuses),
                        currentPage = page,
                        hasMore = hasMore
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    private fun buildColumns(stages: List<CrmPipelineStageDto>, opportunities: List<CrmOpportunityDto>): List<KanbanColumn> {
        val oppsByStage = opportunities.groupBy { it.pipelineStageId }

        val columns = stages.map { stage ->
            val stageOpps = oppsByStage[stage.id]?.map { it.toCard() } ?: emptyList()
            KanbanColumn(
                stageId = stage.id ?: "",
                stageName = stage.name ?: "Unknown",
                sortOrder = stage.sortOrder ?: 0,
                opportunities = stageOpps,
                monetaryTotal = stageOpps.sumOf { it.monetaryValue ?: 0.0 }
            )
        }

        // If no stages from pipeline DTO, build columns from opportunities
        return if (columns.isEmpty() && opportunities.isNotEmpty()) {
            oppsByStage.map { (stageId, opps) ->
                val cards = opps.map { it.toCard() }
                KanbanColumn(
                    stageId = stageId ?: "unknown",
                    stageName = stageId ?: "Unknown",
                    sortOrder = 0,
                    opportunities = cards,
                    monetaryTotal = cards.sumOf { it.monetaryValue ?: 0.0 }
                )
            }
        } else {
            columns
        }
    }

    fun deleteOpportunity(opportunityId: String) {
        viewModelScope.launch {
            repository.deleteOpportunity(opportunityId).onSuccess {
                refresh()
            }.onFailure {
                _uiState.update { s -> s.copy(error = "Failed to delete opportunity") }
            }
        }
    }

    fun moveOpportunityToStage(opportunityId: String, newStageId: String) {
        val pipelineId = _uiState.value.selectedPipelineId ?: return

        // Find the card and its source column
        var sourceColumnIndex = -1
        var card: OpportunityCard? = null
        for ((index, column) in allColumns.withIndex()) {
            val found = column.opportunities.find { it.id == opportunityId }
            if (found != null) {
                card = found
                sourceColumnIndex = index
                break
            }
        }
        if (card == null || sourceColumnIndex == -1) return

        // Don't move to same stage
        if (allColumns[sourceColumnIndex].stageId == newStageId) return

        // Optimistic update: move card from source to target column
        val previousColumns = allColumns.toList()
        val updatedColumns = allColumns.map { column ->
            when (column.stageId) {
                allColumns[sourceColumnIndex].stageId -> {
                    val remaining = column.opportunities.filter { it.id != opportunityId }
                    column.copy(
                        opportunities = remaining,
                        monetaryTotal = remaining.sumOf { it.monetaryValue ?: 0.0 }
                    )
                }
                newStageId -> {
                    val updated = column.opportunities + card
                    column.copy(
                        opportunities = updated,
                        monetaryTotal = updated.sumOf { it.monetaryValue ?: 0.0 }
                    )
                }
                else -> column
            }
        }

        allColumns = updatedColumns
        _uiState.update { it.copy(columns = filterColumnsByStatus(updatedColumns, it.selectedStatuses)) }

        // Call API
        viewModelScope.launch {
            val dto = allOpportunityDtos[opportunityId]
            if (dto != null) {
                repository.updateOpportunityStage(opportunityId, pipelineId, newStageId, dto)
            } else {
                // Fallback: build request from card data
                val request = com.example.rocketplan_android.data.model.CrmOpportunityRequest(
                    name = card.name,
                    pipelineId = pipelineId,
                    pipelineStageId = newStageId,
                    contactId = card.contactId,
                    monetaryValue = card.monetaryValue,
                    status = card.status,
                    source = card.source,
                    assignedTo = card.assignedTo
                )
                repository.updateOpportunity(opportunityId, request)
            }.onSuccess {
                // Update the cached DTO with new stage
                allOpportunityDtos[opportunityId]?.let { oldDto ->
                    allOpportunityDtos[opportunityId] = oldDto.copy(pipelineStageId = newStageId)
                }
                _events.emit(CrmOpportunitiesEvent.StageMoved)
            }.onFailure {
                // Revert optimistic update
                allColumns = previousColumns
                _uiState.update { s -> s.copy(columns = filterColumnsByStatus(previousColumns, s.selectedStatuses)) }
                _events.emit(CrmOpportunitiesEvent.ShowError("Failed to move opportunity"))
            }
        }
    }

    private fun filterColumnsByStatus(columns: List<KanbanColumn>, statuses: Set<String>): List<KanbanColumn> {
        return columns.map { column ->
            val filtered = column.opportunities.filter { card ->
                val cardStatus = card.status?.lowercase() ?: "open"
                cardStatus in statuses
            }
            column.copy(
                opportunities = filtered,
                monetaryTotal = filtered.sumOf { it.monetaryValue ?: 0.0 }
            )
        }
    }

    private fun CrmOpportunityDto.toCard(): OpportunityCard {
        val contactName = contact?.let {
            listOfNotNull(
                it.firstName?.trim()?.takeIf { n -> n.isNotBlank() },
                it.lastName?.trim()?.takeIf { n -> n.isNotBlank() }
            ).joinToString(" ").takeIf { n -> n.isNotBlank() }
        }

        val mappedCustomFields = customFields?.mapNotNull { cf ->
            val fieldName = cf.fieldName ?: return@mapNotNull null
            val fieldValue = cf.fieldValue?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            fieldName to fieldValue
        } ?: emptyList()

        return OpportunityCard(
            id = id ?: "",
            name = name ?: "Untitled",
            contactId = contactId ?: contact?.id,
            contactName = contactName,
            monetaryValue = monetaryValue,
            status = status,
            source = source,
            assignedTo = assignedTo,
            customFields = mappedCustomFields
        )
    }
}
