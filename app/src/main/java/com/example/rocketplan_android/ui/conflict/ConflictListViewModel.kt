package com.example.rocketplan_android.ui.conflict

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.repository.ConflictItem
import com.example.rocketplan_android.data.repository.ConflictRepository
import com.example.rocketplan_android.data.repository.ConflictResolution
import com.example.rocketplan_android.data.repository.sync.FreshTimestampService
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * UI state for the conflict list screen.
 */
sealed class ConflictListUiState {
    data object Loading : ConflictListUiState()
    data class Success(val conflicts: List<ConflictItem>) : ConflictListUiState()
    data class Error(val message: String) : ConflictListUiState()
}

/**
 * ViewModel for the conflict list screen.
 */
class ConflictListViewModel(application: Application) : AndroidViewModel(application) {

    private val conflictRepository: ConflictRepository
    private val freshTimestampService: FreshTimestampService

    private val _uiState = MutableStateFlow<ConflictListUiState>(ConflictListUiState.Loading)
    val uiState: StateFlow<ConflictListUiState> = _uiState.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    init {
        val app = application as RocketPlanApplication
        val api = RetrofitClient.createService<OfflineSyncApi>()
        freshTimestampService = FreshTimestampService(api)
        conflictRepository = ConflictRepository(
            localDataService = app.localDataService,
            gson = Gson(),
            freshTimestampService = freshTimestampService
        )
        observeConflicts()
    }

    private fun observeConflicts() {
        conflictRepository.observeConflicts()
            .onEach { conflicts ->
                _uiState.value = ConflictListUiState.Success(conflicts)
            }
            .catch { error ->
                _uiState.value = ConflictListUiState.Error(error.message ?: "Unknown error")
            }
            .launchIn(viewModelScope)
    }

    /**
     * Resolves a single conflict.
     * For KEEP_LOCAL, uses fresh timestamp fetching to prevent immediate re-conflict.
     */
    fun resolveConflict(conflictId: String, resolution: ConflictResolution) {
        viewModelScope.launch {
            _resolving.value = true
            try {
                when (resolution) {
                    ConflictResolution.KEEP_LOCAL -> {
                        // Use enhanced resolution with fresh timestamp fetching
                        val success = conflictRepository.resolveKeepLocalWithFreshTimestamp(conflictId)
                        if (!success) {
                            // Max requeue attempts exceeded - auto-dismiss to prevent infinite loops
                            // The conflict has been retried too many times and keeps failing
                            android.util.Log.w("ConflictListViewModel",
                                "Conflict $conflictId exceeded max requeue attempts, auto-dismissing")
                            conflictRepository.resolveDismiss(conflictId)
                        }
                    }
                    ConflictResolution.KEEP_SERVER -> conflictRepository.resolveKeepServer(conflictId)
                    ConflictResolution.DISMISS -> conflictRepository.resolveDismiss(conflictId)
                }
            } finally {
                _resolving.value = false
            }
        }
    }

    /**
     * Resolves all conflicts with the same strategy.
     * For KEEP_LOCAL, uses fresh timestamp fetching to prevent immediate re-conflict.
     */
    fun resolveAll(resolution: ConflictResolution) {
        viewModelScope.launch {
            _resolving.value = true
            try {
                val currentState = _uiState.value
                if (currentState is ConflictListUiState.Success) {
                    currentState.conflicts.forEach { conflict ->
                        when (resolution) {
                            ConflictResolution.KEEP_LOCAL -> {
                                // Use enhanced resolution with fresh timestamp fetching
                                val success = conflictRepository.resolveKeepLocalWithFreshTimestamp(conflict.conflictId)
                                if (!success) {
                                    // Max requeue attempts exceeded - auto-dismiss to prevent infinite loops
                                    android.util.Log.w("ConflictListViewModel",
                                        "Conflict ${conflict.conflictId} exceeded max requeue attempts, auto-dismissing")
                                    conflictRepository.resolveDismiss(conflict.conflictId)
                                }
                            }
                            ConflictResolution.KEEP_SERVER -> conflictRepository.resolveKeepServer(conflict.conflictId)
                            ConflictResolution.DISMISS -> conflictRepository.resolveDismiss(conflict.conflictId)
                        }
                    }
                }
            } finally {
                _resolving.value = false
            }
        }
    }

    /**
     * Gets a single conflict for detail view.
     */
    suspend fun getConflict(conflictId: String): ConflictItem? {
        return conflictRepository.getConflict(conflictId)
    }
}
