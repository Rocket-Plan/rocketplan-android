package com.example.rocketplan_android.ui.imageprocessor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.ImageProcessorAssemblyEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImageProcessorAssembliesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository =
        (application as RocketPlanApplication).imageProcessorRepository

    private val _uiState = MutableStateFlow<ImageProcessorAssembliesUiState>(ImageProcessorAssembliesUiState.Loading)
    val uiState: StateFlow<ImageProcessorAssembliesUiState> = _uiState.asStateFlow()

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
}

sealed class ImageProcessorAssembliesUiState {
    object Loading : ImageProcessorAssembliesUiState()
    object Empty : ImageProcessorAssembliesUiState()
    data class Content(val assemblies: List<ImageProcessorAssemblyEntity>) :
        ImageProcessorAssembliesUiState()
}
