package com.example.rocketplan_android.ui.projects.lossinfo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.model.ClaimDto
import com.example.rocketplan_android.data.model.DamageCauseDto
import com.example.rocketplan_android.data.model.DamageTypeDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ClaimListItem(
    val claim: ClaimDto,
    val locationName: String? = null
)

data class ProjectLossInfoUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val projectTitle: String? = null,
    val projectCode: String? = null,
    val projectCreatedAt: String? = null,
    val property: PropertyDto? = null,
    val damageTypes: List<DamageTypeDto> = emptyList(),
    val selectedDamageTypeIds: Set<Long> = emptySet(),
    val selectedDamageCause: DamageCauseDto? = null,
    val damageCauses: List<DamageCauseDto> = emptyList(),
    val damageCategory: Int? = null,
    val lossClass: Int? = null,
    val lossDate: Date? = null,
    val callReceived: Date? = null,
    val crewDispatched: Date? = null,
    val arrivedOnSite: Date? = null,
    val affectedLocations: List<String> = emptyList(),
    val claims: List<ClaimListItem> = emptyList()
)

private data class ProjectLossInfoPayload(
    val project: OfflineProjectEntity,
    val property: PropertyDto,
    val damageTypes: List<DamageTypeDto>,
    val damageCauses: List<DamageCauseDto>,
    val projectClaims: List<ClaimDto>,
    val locationClaims: Map<Long, List<ClaimDto>>,
    val locations: List<OfflineLocationEntity>
)

private class ProjectLossInfoRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun load(projectId: Long): ProjectLossInfoPayload = withContext(ioDispatcher) {
        val project = localDataService.getProject(projectId)
            ?: throw IllegalStateException("Project $projectId not found locally")
        val projectServerId = project.serverId ?: project.projectId

        val locations = localDataService.getLocations(projectId)
        val propertyId = resolvePropertyId(projectId, project, projectServerId)
        val property = api.getProperty(propertyId)
        val damageTypes = api.getProjectDamageTypes(projectServerId)
        val damageCauses = api.getDamageCauses(projectServerId).data
        val projectClaims = api.getProjectClaims(projectServerId).data

        val locationClaims = mutableMapOf<Long, List<ClaimDto>>()
        locations.forEach { location ->
            val serverId = location.serverId ?: location.locationId
            val claims = api.getLocationClaims(serverId).data
            if (claims.isNotEmpty()) {
                locationClaims[serverId] = claims
            }
        }

        ProjectLossInfoPayload(
            project = project,
            property = property,
            damageTypes = damageTypes,
            damageCauses = damageCauses,
            projectClaims = projectClaims,
            locationClaims = locationClaims,
            locations = locations
        )
    }

    private suspend fun resolvePropertyId(
        projectId: Long,
        project: OfflineProjectEntity,
        projectServerId: Long
    ): Long {
        // Check if we have a cached property with a valid serverId
        project.propertyId?.let { localPropertyId ->
            val cached = localDataService.getProperty(localPropertyId)
            cached?.serverId?.let { return it }
        }

        // Fetch from API if no valid serverId
        val properties = api.getProjectProperties(projectServerId).data
        val propertyDto = properties.firstOrNull()
            ?: throw IllegalStateException("No property found for project $projectServerId")

        val entity = propertyDto.toEntity()
        localDataService.saveProperty(entity)
        localDataService.attachPropertyToProject(
            projectId = projectId,
            propertyId = entity.propertyId,
            propertyType = propertyDto.propertyType
        )
        return entity.serverId ?: entity.propertyId
    }

    private fun PropertyDto.toEntity(): OfflinePropertyEntity {
        val timestamp = Date()
        return OfflinePropertyEntity(
            propertyId = id,
            serverId = id,
            uuid = uuid ?: UUID.randomUUID().toString(),
            address = address ?: "",
            city = city,
            state = state,
            zipCode = postalCode,
            latitude = latitude,
            longitude = longitude,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            createdAt = DateUtils.parseApiDate(createdAt) ?: timestamp,
            updatedAt = DateUtils.parseApiDate(updatedAt) ?: timestamp,
            lastSyncedAt = timestamp
        )
    }
}

class ProjectLossInfoViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val repository = ProjectLossInfoRepository(
        api = RetrofitClient.createService<OfflineSyncApi>(),
        localDataService = rocketPlanApp.localDataService
    )

    private val _uiState = MutableStateFlow(ProjectLossInfoUiState())
    val uiState: StateFlow<ProjectLossInfoUiState> = _uiState

    private val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val dateTimeFormatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            runCatching { repository.load(projectId) }
                .onSuccess { payload ->
                    _uiState.value = payload.toUiState()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Unable to load loss info"
                    )
                }
        }
    }

    private fun ProjectLossInfoPayload.toUiState(): ProjectLossInfoUiState {
        val propertyDamageTypes = property.propertyDamageTypes.orEmpty()
        val selectedDamageTypeIds = propertyDamageTypes.mapNotNull { it.id }.toSet()

        val claims = mutableListOf<ClaimListItem>().apply {
            projectClaims.forEach { add(ClaimListItem(it, null)) }
            locations.forEach { location ->
                val serverId = location.serverId ?: location.locationId
                val locationClaimList = locationClaims[serverId].orEmpty()
                if (locationClaimList.isNotEmpty()) {
                    locationClaimList.forEach { add(ClaimListItem(it, location.title)) }
                }
            }
        }

        return ProjectLossInfoUiState(
            isLoading = false,
            projectTitle = listOfNotNull(
                project.addressLine1?.takeIf { it.isNotBlank() },
                project.title.takeIf { it.isNotBlank() },
                project.alias?.takeIf { it.isNotBlank() }
            ).firstOrNull(),
            projectCode = project.uid?.takeIf { it.isNotBlank() }
                ?: project.projectNumber,
            projectCreatedAt = project.createdAt?.let { dateFormatter.format(it) },
            property = property,
            damageTypes = damageTypes,
            selectedDamageTypeIds = selectedDamageTypeIds,
            selectedDamageCause = property.damageCause,
            damageCauses = damageCauses,
            damageCategory = property.damageCategory,
            lossClass = property.lossClass,
            lossDate = DateUtils.parseApiDate(property.lossDate),
            callReceived = DateUtils.parseApiDate(property.callReceived),
            crewDispatched = DateUtils.parseApiDate(property.crewDispatched),
            arrivedOnSite = DateUtils.parseApiDate(property.arrivedOnSite),
            affectedLocations = locations.map { it.title },
            claims = claims
        )
    }

    fun formatDate(date: Date?): String =
        date?.let { dateFormatter.format(it) } ?: "—"

    fun formatDateTime(date: Date?): String =
        date?.let { dateTimeFormatter.format(it) } ?: "—"

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ProjectLossInfoViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return ProjectLossInfoViewModel(application, projectId) as T
            }
        }
    }
}
