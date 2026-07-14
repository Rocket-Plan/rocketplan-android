package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.feature.SerializedEquipmentMode
import com.example.rocketplan_android.data.feature.SerializedEquipmentModeProvider
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * RP-FR-019 — serialized room-equipment screen state, gated by the per-company
 * [SerializedEquipmentMode]:
 *  - OFF     → host shows the legacy count UI ([LegacyMode]).
 *  - UNKNOWN → host shows a retryable placeholder ([Unavailable]) — never legacy.
 *  - ON      → serialized [Ready] content.
 */
sealed class SerializedRoomUiState {
    object Loading : SerializedRoomUiState()

    /** Flag OFF — the host should mount the legacy count-based equipment UI. */
    object LegacyMode : SerializedRoomUiState()

    /** Flag UNKNOWN (never fetched / fetch failed) — mount neither, offer retry. */
    object Unavailable : SerializedRoomUiState()

    data class Ready(
        val roomName: String,
        val deployed: List<RoomAssetItem>,
        val pool: List<PoolAssetItem>
    ) : SerializedRoomUiState()
}

data class RoomAssetItem(
    val assetId: Long,
    val name: String,
    val detail: String,
    val status: String
)

data class PoolAssetItem(
    val assetId: Long,
    val name: String,
    val detail: String
)

class SerializedRoomEquipmentViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val localDataService = app.localDataService
    private val offlineSyncRepository = app.offlineSyncRepository
    private val authRepository = app.authRepository
    private val modeProvider = SerializedEquipmentModeProvider(app.secureStorage)

    private val _uiState = MutableStateFlow<SerializedRoomUiState>(SerializedRoomUiState.Loading)
    val uiState: StateFlow<SerializedRoomUiState> = _uiState

    private var contentJob: Job? = null

    init {
        resolve()
    }

    /** Re-resolve the mode and (when ON) start observing content. */
    fun resolve() {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            val companyId = app.secureStorage.getCompanyIdSync()
            if (companyId == null) {
                _uiState.value = SerializedRoomUiState.Unavailable
                return@launch
            }
            when (modeProvider.modeFor(companyId)) {
                SerializedEquipmentMode.OFF -> _uiState.value = SerializedRoomUiState.LegacyMode
                SerializedEquipmentMode.UNKNOWN -> _uiState.value = SerializedRoomUiState.Unavailable
                SerializedEquipmentMode.ON -> observeContent(companyId)
            }
        }
    }

    /** UNKNOWN-state recovery: re-fetch the flag, then re-resolve. */
    fun retry() {
        viewModelScope.launch {
            _uiState.value = SerializedRoomUiState.Loading
            runCatching { authRepository.refreshFeatureFlags() }
            resolve()
        }
    }

    private suspend fun observeContent(companyId: Long) {
        combine(
            localDataService.observeRooms(projectId),
            localDataService.observeOpenPlacementsForRoom(roomId),
            localDataService.observeEquipmentAssetsForCompany(companyId)
        ) { rooms, placements, assets ->
            val room = rooms.firstOrNull { it.roomId == roomId }
            val assetsById = assets.associateBy { it.assetId }
            val deployed = placements.mapNotNull { placement ->
                assetsById[placement.assetId]?.let { asset -> asset.toRoomItem(placement) }
            }.sortedBy { it.name.lowercase() }
            val pool = assets
                .filter { it.status == "available" && !it.isDeleted }
                .map { it.toPoolItem() }
                .sortedBy { it.name.lowercase() }
            SerializedRoomUiState.Ready(
                roomName = room?.title ?: "Room",
                deployed = deployed,
                pool = pool
            )
        }.collect { _uiState.value = it }
    }

    fun registerAndDeploy(name: String, serialNumber: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val companyId = app.secureStorage.getCompanyIdSync() ?: return@launch
            val asset = offlineSyncRepository.registerEquipmentAssetOffline(
                companyId = companyId,
                name = name,
                serialNumber = serialNumber
            )
            offlineSyncRepository.deployEquipmentAssetOffline(
                assetLocalId = asset.assetId,
                roomLocalId = roomId,
                projectLocalId = projectId
            )
        }
    }

    fun deployFromPool(assetId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            offlineSyncRepository.deployEquipmentAssetOffline(
                assetLocalId = assetId,
                roomLocalId = roomId,
                projectLocalId = projectId
            )
        }
    }

    fun retire(assetId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            offlineSyncRepository.retireEquipmentAssetOffline(assetId)
        }
    }

    private fun OfflineEquipmentAssetEntity.toRoomItem(placement: OfflineEquipmentPlacementEntity) =
        RoomAssetItem(
            assetId = assetId,
            name = name ?: "Equipment",
            detail = detailLine(),
            status = status
        )

    private fun OfflineEquipmentAssetEntity.toPoolItem() =
        PoolAssetItem(
            assetId = assetId,
            name = name ?: "Equipment",
            detail = detailLine()
        )

    private fun OfflineEquipmentAssetEntity.detailLine(): String =
        listOfNotNull(
            serialNumber?.takeIf { it.isNotBlank() }?.let { "SN $it" },
            assetTag?.takeIf { it.isNotBlank() }?.let { "Tag $it" }
        ).joinToString(" · ")

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long,
            roomId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SerializedRoomEquipmentViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return SerializedRoomEquipmentViewModel(application, projectId, roomId) as T
            }
        }
    }
}
