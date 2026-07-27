package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.feature.SerializedEquipmentMode
import com.example.rocketplan_android.data.feature.SerializedEquipmentModeProvider
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentAssetEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentPlacementEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RP-FR-027 — the current open placement of an asset, resolved for display
 * (room title + project title + date_in), derived from the local placement + parent rows.
 */
data class CurrentPlacementUi(
    val roomName: String?,
    val projectName: String?,
    val dateIn: String?
)

/**
 * RP-FR-027 — sealed UI state for the per-unit serialized-asset detail screen. Mirrors the
 * neighbourhood convention ([AssetEditUiState], [SerializedRoomUiState]). Reads offline-first
 * from Room via a Flow, gated on the asset's owner-company serialized mode (RP-BUG-369).
 */
sealed class SerializedAssetDetailUiState {
    object Loading : SerializedAssetDetailUiState()

    /** The asset row is absent or soft-deleted — nothing to show. */
    object NotFound : SerializedAssetDetailUiState()

    /**
     * RP-BUG-369: the asset's OWNING company is no longer in serialized mode (flag rolled back to
     * OFF, or the flags fetch is failing → UNKNOWN). This hub hosts the entire write surface
     * (deploy/move/check-out/retire/edit), so it must mount no write UI in that state — mirrors
     * [SerializedPoolUiState.Disabled], which the pool fragment resolves with `navigateUp()`.
     */
    object Disabled : SerializedAssetDetailUiState()

    data class Ready(
        val asset: OfflineEquipmentAssetEntity,
        /** Non-null only while the unit is deployed (has an open placement). */
        val currentPlacement: CurrentPlacementUi?
    ) : SerializedAssetDetailUiState() {
        val isDeployed: Boolean get() = currentPlacement != null
    }
}

/** RP-FR-027 — a target room the user can deploy/move a unit to. */
data class DeployRoomChoice(val projectId: Long, val roomId: Long, val roomName: String, val projectName: String)

/**
 * RP-FR-027 — per-unit serialized-asset detail screen. Reads the asset + its open placement
 * from Room via Flows (offline-first), exposes [SerializedAssetDetailUiState], and hosts the
 * lifecycle actions by delegating to the existing [com.example.rocketplan_android.data.repository.OfflineSyncRepository]
 * offline methods. A concurrent server edit reflects reactively (the row re-emits when the
 * pull/handler writes back) — no explicit 409 handling here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerializedAssetDetailViewModel(
    application: Application,
    val assetLocalId: Long
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val localDataService = app.localDataService
    private val offlineSyncRepository = app.offlineSyncRepository
    private val modeProvider = SerializedEquipmentModeProvider(app.secureStorage)

    /**
     * RP-BUG-369: latest observed owner-company mode. Gates every write so an in-flight tap cannot
     * slip through between the flag flipping and the fragment navigating away.
     */
    @Volatile
    private var currentMode: SerializedEquipmentMode = SerializedEquipmentMode.UNKNOWN

    private fun requireOn(): Boolean {
        if (currentMode != SerializedEquipmentMode.ON) {
            _events.tryEmit(app.getString(R.string.equipment_mode_changed))
            return false
        }
        return true
    }

    private val _uiState = MutableStateFlow<SerializedAssetDetailUiState>(SerializedAssetDetailUiState.Loading)
    val uiState: StateFlow<SerializedAssetDetailUiState> = _uiState

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    // Deployed date is a date-only value stored as UTC midnight; format in UTC so it never shifts a
    // day on a non-UTC device (matches PlacementHistoryViewModel / RP-BUG-345).
    private val dateInFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    init {
        observeAsset()
    }

    private fun observeAsset() {
        viewModelScope.launch {
            combine(
                localDataService.observeEquipmentAsset(assetLocalId),
                // Open placement is derived from the full placement history (avoids a second query).
                localDataService.observePlacementsForAsset(assetLocalId)
            ) { asset, placements -> asset to placements }
                // RP-BUG-369: OBSERVE the owner-company mode rather than rendering unconditionally.
                // The mode is a property of the asset's own company, so it can only be resolved once
                // the row is loaded. A mid-session flip to OFF/UNKNOWN switches to Disabled, which the
                // fragment resolves by leaving the screen (same contract as the pool).
                .flatMapLatest { (asset, placements) ->
                    if (asset == null || asset.isDeleted) {
                        currentMode = SerializedEquipmentMode.UNKNOWN
                        flowOf(SerializedAssetDetailUiState.NotFound)
                    } else {
                        modeProvider.observeMode(asset.companyId).map { mode ->
                            currentMode = mode
                            if (mode != SerializedEquipmentMode.ON) {
                                SerializedAssetDetailUiState.Disabled
                            } else {
                                val open = placements.firstOrNull { it.isOpen && !it.isDeleted }
                                SerializedAssetDetailUiState.Ready(asset, open?.let { resolvePlacement(it) })
                            }
                        }
                    }
                }
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.remoteLogger.log(
                        com.example.rocketplan_android.logging.LogLevel.WARN, "equip_ui",
                        "Serialized asset detail flow threw",
                        mapOf(
                            "assetId" to assetLocalId.toString(),
                            "error" to (e::class.java.simpleName + ": " + (e.message ?: ""))
                        )
                    )
                    _uiState.value = SerializedAssetDetailUiState.NotFound
                }
                .collect { _uiState.value = it }
        }
    }

    private suspend fun resolvePlacement(placement: OfflineEquipmentPlacementEntity): CurrentPlacementUi =
        withContext(Dispatchers.IO) {
            val roomName = placement.roomId?.let { localDataService.getRoom(it)?.title }
            val projectName = placement.projectId?.let { localDataService.getProject(it)?.title }
            CurrentPlacementUi(
                roomName = roomName,
                projectName = projectName,
                dateIn = placement.dateIn?.let { dateInFormat.format(it) }
            )
        }

    /**
     * Rooms the unit can be deployed to — restricted to the asset's OWN company. A cross-company
     * room is rejected by [OfflineSyncRepository.deployEquipmentAssetOffline] (same-company invariant),
     * so offering it would be a guaranteed-fail choice (RP-BUG-338 class).
     */
    suspend fun deployRoomChoices(): List<DeployRoomChoice> = withContext(Dispatchers.IO) {
        val assetCompanyId = localDataService.getEquipmentAsset(assetLocalId)?.companyId
        val projects = localDataService.observeProjects().first()
            .filter { !it.isDeleted && (assetCompanyId == null || it.companyId == assetCompanyId) }
            // Newest projects first (most recently created); rooms stay alphabetical within a project.
            .sortedByDescending { it.createdAt }
        projects.flatMap { project ->
            localDataService.observeRooms(project.projectId).first()
                .filter { !it.isDeleted }
                .sortedBy { it.title.lowercase() }
                .map { DeployRoomChoice(project.projectId, it.roomId, it.title, project.title) }
        }
    }

    /** Rooms in the current placement's project the unit can be moved to (excludes its current room). */
    suspend fun moveRoomChoices(): List<DeployRoomChoice> = withContext(Dispatchers.IO) {
        val open = localDataService.getOpenPlacementForAsset(assetLocalId) ?: return@withContext emptyList()
        val projectId = open.projectId ?: return@withContext emptyList()
        val project = localDataService.getProject(projectId) ?: return@withContext emptyList()
        localDataService.observeRooms(projectId).first()
            .filter { !it.isDeleted && it.roomId != open.roomId }
            .map { DeployRoomChoice(projectId, it.roomId, it.title, project.title) }
            .sortedBy { it.roomName.lowercase() }
    }

    fun deploy(roomLocalId: Long, projectLocalId: Long) =
        runAction(R.string.serialized_detail_action_deploy_failed) {
            offlineSyncRepository.deployEquipmentAssetOffline(assetLocalId, roomLocalId, projectLocalId) != null
        }

    fun move(toRoomLocalId: Long) =
        runAction(R.string.serialized_detail_action_move_failed) {
            offlineSyncRepository.moveEquipmentAssetOffline(assetLocalId, toRoomLocalId) != null
        }

    fun checkOut() =
        runAction(R.string.serialized_detail_action_check_out_failed) {
            offlineSyncRepository.checkOutEquipmentAssetOffline(assetLocalId) != null
        }

    fun retire() =
        runAction(R.string.serialized_detail_action_retire_failed) {
            offlineSyncRepository.retireEquipmentAssetOffline(assetLocalId) != null
        }

    /** Ignore duplicate taps while an action is staging. */
    @Volatile
    private var actionInFlight = false

    private fun runAction(failureMessageRes: Int, block: suspend () -> Boolean) {
        // RP-BUG-369: refuse writes unless the owner company is still in serialized mode.
        if (!requireOn()) return
        if (actionInFlight) return
        actionInFlight = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = runCatching { block() }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.remoteLogger.log(
                        com.example.rocketplan_android.logging.LogLevel.WARN, "equip_ui",
                        "Serialized asset detail action failed",
                        mapOf(
                            "assetId" to assetLocalId.toString(),
                            "error" to (e::class.java.simpleName + ": " + (e.message ?: ""))
                        )
                    )
                    _events.emit(app.getString(failureMessageRes))
                    return@launch
                }
                if (!ok) _events.emit(app.getString(failureMessageRes))
            } finally {
                actionInFlight = false
            }
        }
    }

    companion object {
        fun provideFactory(application: Application, assetLocalId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SerializedAssetDetailViewModel::class.java)) {
                        "Unknown ViewModel class"
                    }
                    return SerializedAssetDetailViewModel(application, assetLocalId) as T
                }
            }
    }
}
