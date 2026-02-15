package com.example.rocketplan_android.ui.crm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.model.CrmCustomFieldDefinitionDto
import com.example.rocketplan_android.data.model.CrmOpportunityDto
import com.example.rocketplan_android.data.model.CrmPipelineStageDto
import com.example.rocketplan_android.data.repository.CrmContactRepository
import com.example.rocketplan_android.data.repository.CrmOpportunityRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CrmOpportunityDetailUiState(
    val opportunity: CrmOpportunityDto? = null,
    val customFieldDefinitions: List<CrmCustomFieldDefinitionDto> = emptyList(),
    val pipelineStages: List<CrmPipelineStageDto> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

sealed class CrmOpportunityDetailEvent {
    data object OpportunityNotFound : CrmOpportunityDetailEvent()
    data object OpportunityDeleted : CrmOpportunityDetailEvent()
    data object StageMoved : CrmOpportunityDetailEvent()
    data class ShowError(val message: String) : CrmOpportunityDetailEvent()
}

class CrmOpportunityDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CrmOpportunityRepository
    private val contactRepository: CrmContactRepository

    private val _uiState = MutableStateFlow(CrmOpportunityDetailUiState())
    val uiState: StateFlow<CrmOpportunityDetailUiState> = _uiState

    private val _events = MutableSharedFlow<CrmOpportunityDetailEvent>()
    val events: SharedFlow<CrmOpportunityDetailEvent> = _events

    init {
        val app = application as RocketPlanApplication
        repository = CrmOpportunityRepository(app.authRepository, app.remoteLogger)
        contactRepository = CrmContactRepository(app.authRepository, app.remoteLogger)
    }

    fun loadOpportunity(opportunityId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val defsJob = launch {
                contactRepository.getCustomFieldDefinitions("opportunity").onSuccess { defs ->
                    _uiState.update { it.copy(customFieldDefinitions = defs.sortedBy { d -> d.position ?: Int.MAX_VALUE }) }
                }
            }

            // Load pipeline stages
            val pipelinesJob = launch {
                repository.getPipelines().onSuccess { pipelines ->
                    // We'll match the pipeline once we have the opportunity
                    _uiState.update { it.copy(pipelineStages = pipelines.flatMap { p -> p.stages ?: emptyList() }) }
                }
            }

            repository.getOpportunity(opportunityId).onSuccess { opportunity ->
                defsJob.join()
                pipelinesJob.join()

                // Find stages for this opportunity's pipeline
                val allStages = _uiState.value.pipelineStages
                val pipelineStages = allStages.filter { it.pipelineId == opportunity.pipelineId }
                    .sortedBy { it.sortOrder }

                _uiState.update {
                    it.copy(
                        opportunity = opportunity,
                        pipelineStages = pipelineStages,
                        isLoading = false
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isLoading = false) }
                _events.emit(CrmOpportunityDetailEvent.OpportunityNotFound)
            }
        }
    }

    fun moveToStage(opportunityId: String, stageId: String) {
        viewModelScope.launch {
            val opportunity = _uiState.value.opportunity ?: return@launch
            val pipelineId = opportunity.pipelineId ?: return@launch

            repository.updateOpportunityStage(opportunityId, pipelineId, stageId, opportunity)
                .onSuccess {
                    _events.emit(CrmOpportunityDetailEvent.StageMoved)
                    // Reload to get updated data
                    loadOpportunity(opportunityId)
                }.onFailure {
                    _events.emit(CrmOpportunityDetailEvent.ShowError("Failed to update stage"))
                }
        }
    }

    fun deleteOpportunity(opportunityId: String) {
        viewModelScope.launch {
            repository.deleteOpportunity(opportunityId).onSuccess {
                _events.emit(CrmOpportunityDetailEvent.OpportunityDeleted)
            }.onFailure {
                _events.emit(CrmOpportunityDetailEvent.ShowError("Failed to delete opportunity"))
            }
        }
    }
}
