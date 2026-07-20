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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RP-FR-026 — a status filter for the company pool. No RETIRED: the backend company index
 * soft-deletes retired units and never returns them (only the timeline uses withTrashed), and
 * locally-retired rows are isDeleted=true and excluded — so a Retired filter could never populate.
 */
enum class PoolStatusFilter(val serverStatus: String?) {
    ALL(null),
    AVAILABLE("available"),
    DEPLOYED("deployed"),
    MAINTENANCE("maintenance")
}

/** RP-FR-026 — one row in the company pool list. */
data class PoolListItem(
    val assetId: Long,
    val name: String,
    val detail: String,
    val status: String
)

sealed class SerializedPoolUiState {
    object Loading : SerializedPoolUiState()

    /** Flag ON — list every company unit (filtered client-side). */
    data class Ready(val items: List<PoolListItem>) : SerializedPoolUiState()

    /** Flag UNKNOWN (never fetched / fetch failed / no active company) — mount nothing, offer retry. */
    object Unavailable : SerializedPoolUiState()

    /** Flag OFF — the company is count-based; this serialized screen must not mount, pop back. */
    object Disabled : SerializedPoolUiState()
}

/**
 * RP-FR-026 — standalone, company-wide serialized-equipment pool. Mirrors
 * [SerializedRoomEquipmentViewModel]: observes the owner-company mode and mounts the serialized
 * list only when ON, offers a retryable state on UNKNOWN (never falls back to legacy), and pops
 * back on OFF (there is no legacy company-wide pool). The list reads from Room via a Flow and is
 * narrowed by a client-side status filter + search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SerializedEquipmentPoolViewModel(
    application: Application,
    /** Requested company; <= 0 means "resolve the active company". */
    private val requestedCompanyId: Long = -1L
) : AndroidViewModel(application) {

    private val app = application as RocketPlanApplication
    private val localDataService = app.localDataService
    private val offlineSyncRepository = app.offlineSyncRepository
    private val authRepository = app.authRepository
    private val secureStorage = app.secureStorage
    private val modeProvider = SerializedEquipmentModeProvider(secureStorage)

    private val _uiState = MutableStateFlow<SerializedPoolUiState>(SerializedPoolUiState.Loading)
    val uiState: StateFlow<SerializedPoolUiState> = _uiState

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    private val statusFilter = MutableStateFlow(PoolStatusFilter.ALL)
    private val searchQuery = MutableStateFlow("")

    private var companyId: Long? = null
    private var contentJob: Job? = null

    @Volatile
    private var currentMode: SerializedEquipmentMode = SerializedEquipmentMode.UNKNOWN

    init {
        resolve()
    }

    fun setStatusFilter(filter: PoolStatusFilter) {
        statusFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun resolve() {
        contentJob?.cancel()
        contentJob = viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                requestedCompanyId.takeIf { it > 0 } ?: secureStorage.getCompanyIdSync()
            }
            if (resolved == null) {
                _uiState.value = SerializedPoolUiState.Unavailable
                return@launch
            }
            companyId = resolved

            var pulled = false
            modeProvider.observeMode(resolved)
                .flatMapLatest { mode ->
                    currentMode = mode
                    when (mode) {
                        SerializedEquipmentMode.OFF -> { pulled = false; flowOf(SerializedPoolUiState.Disabled) }
                        SerializedEquipmentMode.UNKNOWN -> { pulled = false; flowOf(SerializedPoolUiState.Unavailable) }
                        SerializedEquipmentMode.ON -> {
                            if (!pulled) {
                                pulled = true
                                val pull = offlineSyncRepository.refreshSerializedPool(resolved)
                                if (pull.isFailure) {
                                    app.remoteLogger.log(
                                        com.example.rocketplan_android.logging.LogLevel.WARN, "equip_ui",
                                        "Serialized pool pull failed (UI)",
                                        mapOf("companyId" to resolved.toString())
                                    )
                                    val hasCache = localDataService.observeEquipmentAssetsForCompany(resolved)
                                        .first().isNotEmpty()
                                    if (!hasCache) return@flatMapLatest flowOf(SerializedPoolUiState.Unavailable)
                                    _events.emit(app.getString(com.example.rocketplan_android.R.string.serialized_pool_stale_cache))
                                }
                            }
                            contentFlow(resolved)
                        }
                    }
                }
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.remoteLogger.log(
                        com.example.rocketplan_android.logging.LogLevel.WARN, "equip_ui",
                        "Serialized pool resolve flow threw",
                        mapOf(
                            "companyId" to resolved.toString(),
                            "error" to (e::class.java.simpleName + ": " + (e.message ?: ""))
                        )
                    )
                    _uiState.value = SerializedPoolUiState.Unavailable
                }
                .collect { _uiState.value = it }
        }
    }

    fun retry() {
        viewModelScope.launch {
            _uiState.value = SerializedPoolUiState.Loading
            // The flags endpoint is active-company scoped; the pool is the active company's pool.
            runCatching { authRepository.refreshFeatureFlags() }
            resolve()
        }
    }

    /** Re-fetch backend flag authority on foreground so a server-side flip is picked up. */
    fun refreshMode() {
        viewModelScope.launch { runCatching { authRepository.refreshFeatureFlags() } }
    }

    private fun contentFlow(companyId: Long): kotlinx.coroutines.flow.Flow<SerializedPoolUiState> =
        combine(
            localDataService.observeEquipmentAssetsForCompany(companyId),
            statusFilter,
            searchQuery
        ) { assets, filter, query ->
            val q = query.trim().lowercase()
            val items = assets
                .asSequence()
                .filter { !it.isDeleted }
                .filter { filter.serverStatus == null || it.status == filter.serverStatus }
                .filter { q.isEmpty() || it.matchesSearch(q) }
                .map { it.toItem() }
                .sortedBy { it.name.lowercase() }
                .toList()
            SerializedPoolUiState.Ready(items)
        }

    /** Company equipment catalog items (each carries the catalog_uuid needed to register). */
    suspend fun catalogChoices(): List<CatalogChoice> {
        val company = companyId ?: return emptyList()
        return offlineSyncRepository.fetchEquipmentCatalog(company).getOrNull()
            ?.mapNotNull { dto ->
                dto.catalogUuid?.let { CatalogChoice(it, dto.displayName ?: dto.name ?: "Equipment") }
            }
            ?: emptyList()
    }

    /** RP-FR-026: register a new unit into the pool (register-only, NOT register-and-deploy). */
    fun register(name: String, catalogUuid: String, serialNumber: String?) {
        if (currentMode != SerializedEquipmentMode.ON) {
            _events.tryEmit(app.getString(com.example.rocketplan_android.R.string.equipment_mode_changed))
            return
        }
        val company = companyId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                offlineSyncRepository.registerEquipmentAssetOffline(
                    companyId = company, name = name, catalogUuid = catalogUuid, serialNumber = serialNumber
                )
            }.onSuccess {
                _events.emit(app.getString(com.example.rocketplan_android.R.string.serialized_pool_registered))
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.remoteLogger.log(
                    com.example.rocketplan_android.logging.LogLevel.WARN, "equip_ui",
                    "Serialized pool register failed",
                    mapOf(
                        "companyId" to company.toString(),
                        "error" to (e::class.java.simpleName + ": " + (e.message ?: ""))
                    )
                )
                _events.emit(app.getString(com.example.rocketplan_android.R.string.serialized_equipment_edit_failed))
            }
        }
    }

    private fun OfflineEquipmentAssetEntity.matchesSearch(q: String): Boolean =
        (name?.lowercase()?.contains(q) == true) ||
            (serialNumber?.lowercase()?.contains(q) == true) ||
            (assetTag?.lowercase()?.contains(q) == true)

    private fun OfflineEquipmentAssetEntity.toItem() =
        PoolListItem(assetId, name ?: "Equipment", detailLine(), status)

    private fun OfflineEquipmentAssetEntity.detailLine(): String =
        listOfNotNull(
            serialNumber?.takeIf { it.isNotBlank() }?.let { "SN $it" },
            assetTag?.takeIf { it.isNotBlank() }?.let { "Tag $it" }
        ).joinToString(" · ")

    companion object {
        fun provideFactory(
            application: Application,
            companyId: Long = -1L
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(SerializedEquipmentPoolViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return SerializedEquipmentPoolViewModel(application, companyId) as T
            }
        }
    }
}
