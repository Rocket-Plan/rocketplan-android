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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class SerializedRoomUiState {
    object Loading : SerializedRoomUiState()

    /** Flag OFF — the host should mount the legacy count-based equipment UI. */
    object LegacyMode : SerializedRoomUiState()

    /** Flag UNKNOWN (never fetched / fetch failed / owner unknown) — mount neither, offer retry. */
    object Unavailable : SerializedRoomUiState()

    data class Ready(
        val roomName: String,
        val deployed: List<RoomAssetItem>,
        val pool: List<PoolAssetItem>
    ) : SerializedRoomUiState()
}

data class RoomAssetItem(val assetId: Long, val name: String, val detail: String, val status: String)
data class PoolAssetItem(val assetId: Long, val name: String, val detail: String)

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

    /** One-shot user feedback (review #5): rejected/failed actions. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    /** The company that OWNS this project — mode + all writes resolve against it (review #2). */
    private var ownerCompanyId: Long? = null
    private var contentJob: Job? = null

    init {
        resolve()
    }

    fun resolve() {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            // Review #2: derive the company from the project owner, NOT the active company.
            val companyId = withContext(Dispatchers.IO) { localDataService.getProject(projectId)?.companyId }
            if (companyId == null) {
                _uiState.value = SerializedRoomUiState.Unavailable
                return@launch
            }
            ownerCompanyId = companyId
            when (modeProvider.modeFor(companyId)) {
                SerializedEquipmentMode.OFF -> _uiState.value = SerializedRoomUiState.LegacyMode
                SerializedEquipmentMode.UNKNOWN -> _uiState.value = SerializedRoomUiState.Unavailable
                SerializedEquipmentMode.ON -> {
                    // Review round-2 #1/#5: await the initial authoritative pull. On failure,
                    // show a retry state if there's nothing cached, or render the cache with a
                    // non-destructive "stale" notice if there is.
                    val pull = offlineSyncRepository.refreshSerializedRoom(roomId, companyId)
                    if (pull.isFailure) {
                        val hasCache = localDataService.observeEquipmentAssetsForCompany(companyId)
                            .first().isNotEmpty()
                        if (!hasCache) {
                            _uiState.value = SerializedRoomUiState.Unavailable
                            return@launch
                        }
                        _events.emit("Showing saved data — couldn't refresh from the server.")
                    }
                    observeContent(companyId)
                }
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            _uiState.value = SerializedRoomUiState.Loading
            // Review #4: the flags endpoint is scoped to the ACTIVE company. Only refresh when
            // this project's owner IS the active company; otherwise tell the user to switch.
            val owner = withContext(Dispatchers.IO) { localDataService.getProject(projectId)?.companyId }
            val active = withContext(Dispatchers.IO) { app.secureStorage.getCompanyIdSync() }
            when {
                owner != null && owner == active -> runCatching { authRepository.refreshFeatureFlags() }
                owner != null -> _events.emit("Switch to this project's company to load its equipment.")
            }
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
            val deployed = placements.mapNotNull { p -> assetsById[p.assetId]?.toRoomItem() }
                .sortedBy { it.name.lowercase() }
            val pool = assets.filter { it.status == "available" && !it.isDeleted }
                .map { it.toPoolItem() }
                .sortedBy { it.name.lowercase() }
            SerializedRoomUiState.Ready(room?.title ?: "Room", deployed, pool)
        }.collect { _uiState.value = it }
    }

    fun registerAndDeploy(name: String, catalogUuid: String, serialNumber: String?) {
        val companyId = ownerCompanyId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val asset = offlineSyncRepository.registerEquipmentAssetOffline(
                companyId = companyId, name = name, catalogUuid = catalogUuid, serialNumber = serialNumber
            )
            val deployed = offlineSyncRepository.deployEquipmentAssetOffline(asset.assetId, roomId, projectId)
            if (deployed == null) _events.emit("Registered, but couldn't deploy to this room.")
        }
    }

    fun deployFromPool(assetId: Long) = runAction(assetId, "Couldn't deploy — unit isn't available.") {
        offlineSyncRepository.deployEquipmentAssetOffline(assetId, roomId, projectId) != null
    }

    fun retire(assetId: Long) = runAction(assetId, "Couldn't retire this unit.") {
        offlineSyncRepository.retireEquipmentAssetOffline(assetId) != null
    }

    fun move(assetId: Long, toRoomLocalId: Long) = runAction(assetId, "Couldn't move this unit.") {
        offlineSyncRepository.moveEquipmentAssetOffline(assetId, toRoomLocalId) != null
    }

    fun checkOut(assetId: Long) = runAction(assetId, "Nothing to check out.") {
        offlineSyncRepository.checkOutEquipmentAssetOffline(assetId) != null
    }

    /** Review #3: ignore duplicate taps for an asset while its action is staging. */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    private fun runAction(assetId: Long, failureMessage: String, block: suspend () -> Boolean) {
        if (!inFlight.add(assetId)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = runCatching { block() }.getOrElse {
                    // Review #6: the transaction rolled back — nothing was queued, so it won't auto-retry.
                    _events.emit("Action failed — please retry.")
                    return@launch
                }
                if (!ok) _events.emit(failureMessage)
            } finally {
                inFlight.remove(assetId)
            }
        }
    }

    private fun OfflineEquipmentAssetEntity.toRoomItem() =
        RoomAssetItem(assetId, name ?: "Equipment", detailLine(), status)

    private fun OfflineEquipmentAssetEntity.toPoolItem() =
        PoolAssetItem(assetId, name ?: "Equipment", detailLine())

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
