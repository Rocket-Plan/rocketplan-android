package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sealed UI state, matching the neighbourhood convention (`SerializedRoomUiState`,
 * `EquipmentRoomUiState`, `TotalEquipmentUiState`). Save success is a one-shot signal
 * ([finished]), not a state flag.
 */
sealed class AssetEditUiState {
    object Loading : AssetEditUiState()
    data class Ready(
        val asset: OfflineEquipmentAssetEntity,
        val hasOpenPlacement: Boolean,
        val saving: Boolean = false
    ) : AssetEditUiState()
}

class SerializedAssetEditViewModel(
    application: Application,
    val assetLocalId: Long
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val localDataService = app.localDataService
    private val offlineSyncRepository = app.offlineSyncRepository

    private val _uiState = MutableStateFlow<AssetEditUiState>(AssetEditUiState.Loading)
    val uiState: StateFlow<AssetEditUiState> = _uiState

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    /** One-shot: save succeeded → the screen should pop back. */
    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished

    init {
        loadAsset()
    }

    private fun loadAsset() {
        viewModelScope.launch {
            val asset = withContext(Dispatchers.IO) {
                localDataService.getEquipmentAsset(assetLocalId)
            }
            if (asset == null) {
                _events.emit(app.getString(R.string.serialized_equipment_edit_failed))
                _finished.emit(Unit)
                return@launch
            }
            val hasOpenPlacement = withContext(Dispatchers.IO) {
                localDataService.getOpenPlacementForAsset(asset.assetId) != null
            }
            _uiState.value = AssetEditUiState.Ready(asset = asset, hasOpenPlacement = hasOpenPlacement)
        }
    }

    fun save(
        serialNumber: String?,
        assetTag: String?,
        status: String?,
        note: String?,
        manufacturer: String?,
        model: String?,
        vendor: String?,
        purchaseDate: String?,
        purchasePrice: String?,
        warrantyExpiresAt: String?,
        rentalDayRate: String?
    ) {
        val ready = _uiState.value as? AssetEditUiState.Ready ?: return
        val asset = ready.asset
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ready.copy(saving = true)
            try {
                val updated = offlineSyncRepository.updateEquipmentAssetOffline(
                    assetLocalId = asset.assetId,
                    serialNumber = serialNumber?.takeIf { it.isNotBlank() },
                    assetTag = assetTag?.takeIf { it.isNotBlank() },
                    status = status,
                    note = note?.takeIf { it.isNotBlank() },
                    manufacturer = manufacturer?.takeIf { it.isNotBlank() },
                    model = model?.takeIf { it.isNotBlank() },
                    vendor = vendor?.takeIf { it.isNotBlank() },
                    purchaseDate = purchaseDate?.takeIf { it.isNotBlank() },
                    purchasePrice = purchasePrice?.takeIf { it.isNotBlank() },
                    warrantyExpiresAt = warrantyExpiresAt?.takeIf { it.isNotBlank() },
                    rentalDayRate = rentalDayRate?.takeIf { it.isNotBlank() }
                )
                if (updated != null) {
                    _events.emit(app.getString(R.string.serialized_equipment_edit_success))
                    _finished.emit(Unit)
                } else {
                    _events.emit(app.getString(R.string.serialized_equipment_edit_failed))
                    _uiState.value = ready.copy(saving = false)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _events.emit(app.getString(R.string.serialized_equipment_edit_failed))
                _uiState.value = ready.copy(saving = false)
            }
        }
    }

    companion object {
        fun provideFactory(application: Application, assetLocalId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SerializedAssetEditViewModel::class.java)) {
                        "Unknown ViewModel class"
                    }
                    return SerializedAssetEditViewModel(application, assetLocalId) as T
                }
            }
    }
}
