package com.example.rocketplan_android.ui.imageprocessor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImageProcessorAssembliesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository =
        (application as RocketPlanApplication).imageProcessorRepository
    private val queueManager =
        (application as RocketPlanApplication).imageProcessorQueueManager

    private val _uiState = MutableStateFlow<ImageProcessorAssembliesUiState>(ImageProcessorAssembliesUiState.Loading)
    val uiState: StateFlow<ImageProcessorAssembliesUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImageProcessorAssembliesEvent>()
    val events: SharedFlow<ImageProcessorAssembliesEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.observeAllAssemblies().collect { assemblies ->
                _uiState.value = when {
                    assemblies.isEmpty() -> ImageProcessorAssembliesUiState.Empty
                    else -> ImageProcessorAssembliesUiState.Content(assemblies)
                }
            }
        }
    }

    fun deleteAssembly(assemblyId: String) {
        viewModelScope.launch {
            val result = repository.deleteAssembly(assemblyId)
            result.fold(
                onSuccess = {
                    _events.emit(ImageProcessorAssembliesEvent.AssemblyDeleted(assemblyId))
                    queueManager.processNextQueuedAssembly()
                },
                onFailure = { error ->
                    _events.emit(
                        ImageProcessorAssembliesEvent.DeleteFailed(
                            error.message ?: "Unable to delete this assembly"
                        )
                    )
                }
            )
        }
    }

    fun deleteAllAssemblies() {
        viewModelScope.launch {
            val result = repository.deleteAllAssemblies()
            result.fold(
                onSuccess = { count ->
                    _events.emit(ImageProcessorAssembliesEvent.AssembliesCleared(count))
                    queueManager.processNextQueuedAssembly()
                },
                onFailure = { error ->
                    _events.emit(
                        ImageProcessorAssembliesEvent.DeleteFailed(
                            error.message ?: "Unable to delete assemblies right now"
                        )
                    )
                }
            )
        }
    }

    fun retryAssembly(assemblyId: String) {
        viewModelScope.launch {
            val result = queueManager.retryAssembly(assemblyId)
            result.fold(
                onSuccess = { _events.emit(ImageProcessorAssembliesEvent.RetryQueued) },
                onFailure = { error ->
                    _events.emit(
                        ImageProcessorAssembliesEvent.RetryFailed(
                            error.message ?: "Unable to retry right now"
                        )
                    )
                }
            )
        }
    }
}

sealed class ImageProcessorAssembliesUiState {
    object Loading : ImageProcessorAssembliesUiState()
    object Empty : ImageProcessorAssembliesUiState()
    data class Content(val assemblies: List<ImageProcessorAssemblyEntity>) :
        ImageProcessorAssembliesUiState()
}

sealed class ImageProcessorAssembliesEvent {
    object RetryQueued : ImageProcessorAssembliesEvent()
    data class RetryFailed(val reason: String) : ImageProcessorAssembliesEvent()
    data class AssemblyDeleted(val assemblyId: String) : ImageProcessorAssembliesEvent()
    data class AssembliesCleared(val count: Int) : ImageProcessorAssembliesEvent()
    data class DeleteFailed(val reason: String) : ImageProcessorAssembliesEvent()
}
