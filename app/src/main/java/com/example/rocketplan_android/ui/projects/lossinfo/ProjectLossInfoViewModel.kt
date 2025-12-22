package com.example.rocketplan_android.ui.projects.lossinfo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.R
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.api.RetrofitClient
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.model.ClaimDto
import com.example.rocketplan_android.data.model.ClaimMutationRequest
import com.example.rocketplan_android.data.model.DamageCauseDto
import com.example.rocketplan_android.data.model.DamageTypeDto
import com.example.rocketplan_android.data.model.PropertyMutationRequest
import com.example.rocketplan_android.data.model.offline.ProjectAddressDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.ui.projects.PropertyType
import com.example.rocketplan_android.data.repository.IncompleteReason
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.util.Log

data class ClaimListItem(
    val claim: ClaimDto,
    val locationName: String? = null
) {
    val policyHolder: String? get() = claim.policyHolder ?: claim.claimInfo?.policyHolder
    val ownershipStatus: String? get() = claim.ownershipStatus ?: claim.claimInfo?.ownershipStatus
    val policyHolderPhone: String? get() = claim.policyHolderPhone ?: claim.claimInfo?.policyHolderPhone
    val policyHolderEmail: String? get() = claim.policyHolderEmail ?: claim.claimInfo?.policyHolderEmail
    val representative: String? get() = claim.representative ?: claim.claimInfo?.representative
    val provider: String? get() = claim.provider ?: claim.claimInfo?.provider
    val insuranceDeductible: String? get() = claim.insuranceDeductible ?: claim.claimInfo?.insuranceDeductible
    val policyNumber: String? get() = claim.policyNumber ?: claim.claimInfo?.policyNumber
    val claimNumber: String? get() = claim.claimNumber ?: claim.claimInfo?.claimNumber
    val adjuster: String? get() = claim.adjuster ?: claim.claimInfo?.adjuster
    val adjusterPhone: String? get() = claim.adjusterPhone ?: claim.claimInfo?.adjusterPhone
    val adjusterEmail: String? get() = claim.adjusterEmail ?: claim.claimInfo?.adjusterEmail
}

private data class ClaimLocation(
    val id: Long,
    val name: String
)

data class LossInfoFormInput(
    val selectedDamageTypeIds: Set<Long>,
    val damageCauseId: Long?,
    val damageCategory: Int?,
    val lossClass: Int?,
    val lossDate: Date?,
    val callReceived: Date?,
    val crewDispatched: Date?,
    val arrivedOnSite: Date?
)

data class PropertyInfoFormInput(
    val propertyType: PropertyType?,
    val referredByName: String?,
    val referredByPhone: String?,
    val isPlatinumAgent: Boolean?,
    val isResidential: Boolean?,
    val isCommercial: Boolean?,
    val asbestosStatusId: Int?,
    val yearBuilt: Int?,
    val buildingName: String?
)

sealed interface ProjectLossInfoEvent {
    data object SaveSuccess : ProjectLossInfoEvent
    data class SaveFailed(val message: String) : ProjectLossInfoEvent
    data class ClaimUpdated(val claim: ClaimDto) : ProjectLossInfoEvent
    data class ClaimUpdateFailed(val message: String) : ProjectLossInfoEvent
    data class PropertyMissing(val message: String) : ProjectLossInfoEvent
}

data class ProjectLossInfoUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val savingClaimId: Long? = null,
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
    val locations: List<ClaimLocation>
)

private class ProjectLossInfoRepository(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val propertyIncludes = "propertyType,asbestosStatus,propertyDamageTypes,damageCause"

    suspend fun load(projectId: Long): ProjectLossInfoPayload = withContext(ioDispatcher) {
        val project = localDataService.getProject(projectId)
            ?: throw IllegalStateException("Project $projectId not found locally")
        val projectServerId = project.serverId ?: project.projectId

        val projectDetail = runCatching { api.getProjectDetail(projectServerId).data }.getOrNull()
        val propertyId = resolvePropertyId(projectId, project, projectServerId, projectDetail)
        val property = runCatching { api.getProperty(propertyId).data }
            .onFailure { Log.w("API", "⚠️ [LossInfo] Unable to load property $propertyId from server", it) }
            .getOrElse { throw it }
        val locations = buildClaimLocations(projectId, property.id, projectDetail)
        val damageTypes = runCatching { api.getProjectDamageTypes(projectServerId) }
            .onFailure { Log.w("API", "⚠️ [LossInfo] Unable to load damage types for project $projectServerId", it) }
            .getOrDefault(emptyList())
        val damageCauses = runCatching { api.getDamageCauses(projectServerId).data }
            .onFailure { Log.w("API", "⚠️ [LossInfo] Unable to load damage causes for project $projectServerId", it) }
            .getOrDefault(emptyList())
        val projectClaims = runCatching { api.getProjectClaims(projectServerId).data }
            .onFailure { Log.w("API", "⚠️ [LossInfo] Unable to load project claims for $projectServerId", it) }
            .getOrDefault(emptyList())

        val locationClaims = mutableMapOf<Long, List<ClaimDto>>()
        locations.forEach { location ->
            val claims = runCatching { api.getLocationClaims(location.id).data }
                .onFailure { Log.w("API", "⚠️ [LossInfo] Unable to load claims for location ${location.id}", it) }
                .getOrDefault(emptyList())
            if (claims.isNotEmpty()) {
                locationClaims[location.id] = claims
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

    private suspend fun buildClaimLocations(
        projectId: Long,
        propertyServerId: Long,
        projectDetail: ProjectDetailDto?
    ): List<ClaimLocation> {
        val localLocations = localDataService.getLocations(projectId)
            .mapNotNull { entity ->
                entity.serverId?.let { serverId ->
                    ClaimLocation(id = serverId, name = entity.title)
                }
            }
        val remoteLocations = runCatching { api.getPropertyLocations(propertyServerId).data }
            .onFailure { Log.w("API", "⚠️ [LossInfo] Unable to load property locations for claims (propertyId=$propertyServerId)", it) }
            .getOrNull()
            .orEmpty()
            .mapNotNull { dto ->
                val title = listOfNotNull(dto.title, dto.name)
                    .firstOrNull { it.isNotBlank() }
                title?.let { ClaimLocation(id = dto.id, name = it) }
            }

        if (localLocations.isNotEmpty() || remoteLocations.isNotEmpty()) {
            return (localLocations + remoteLocations).distinctBy { it.id }
        }

        Log.d("API", "ℹ️ [LossInfo] No local locations found; falling back to project detail title")
        val fallbackLocation = projectDetail?.title
            ?.let { ClaimLocation(id = projectDetail.id, name = it) }
        return fallbackLocation?.let { listOf(it) } ?: emptyList()
    }

    private suspend fun resolvePropertyId(
        projectId: Long,
        project: OfflineProjectEntity,
        projectServerId: Long,
        projectDetail: ProjectDetailDto?
    ): Long {
        // Check if we have a cached property with a valid serverId
        project.propertyId?.let { localPropertyId ->
            val cached = localDataService.getProperty(localPropertyId)
            cached?.serverId?.let { return it }
        }

        // If project detail has a propertyId and we already cached it, link and return
        projectDetail?.propertyId?.let { serverPropertyId ->
            val cached = localDataService.getProperty(serverPropertyId)
            if (cached != null) {
                val resolvedId = cached.serverId ?: cached.propertyId
                localDataService.attachPropertyToProject(
                    projectId = projectId,
                    propertyId = resolvedId,
                    propertyType = projectDetail.propertyType
                )
                return resolvedId
            }
        }

        // Fetch from API (with fallbacks) if no valid serverId
        val propertyDto = fetchPropertyWithFallback(projectServerId, projectDetail)
            ?: throw IllegalStateException("No property found for project $projectServerId")

        Log.d("API", "🏠 [LossInfo] Property DTO from API: id=${propertyDto.id}, address=${propertyDto.address}, city=${propertyDto.city}, state=${propertyDto.state}, zip=${propertyDto.postalCode}")
        val entity = propertyDto.toEntity(projectAddress = projectDetail?.address, project = project)
        Log.d("API", "🏠 [LossInfo] Property Entity to save: serverId=${entity.serverId}, address=${entity.address}, city=${entity.city}, state=${entity.state}, zip=${entity.zipCode}")
        localDataService.saveProperty(entity)
        val resolvedId = entity.serverId ?: entity.propertyId
        localDataService.attachPropertyToProject(
            projectId = projectId,
            propertyId = resolvedId,
            propertyType = listOfNotNull(
                projectDetail?.propertyType,
                propertyDto.propertyType,
                projectDetail?.properties?.firstOrNull()?.propertyType
            ).firstOrNull()
        )
        return resolvedId
    }

    private suspend fun fetchPropertyWithFallback(
        projectServerId: Long,
        projectDetail: ProjectDetailDto?
    ): PropertyDto? {
        val result = runCatching { api.getProjectProperties(projectServerId, include = propertyIncludes) }
        result.onFailure { error ->
            Log.e("API", "❌ [LossInfo] getProjectProperties failed for project $projectServerId", error)
        }
        val response = result.getOrNull()
        val propertyFromList = response?.data?.firstOrNull()
        if (propertyFromList != null) {
            return propertyFromList
        }

        Log.d("API", "⚠️ [LossInfo] No property in response for project $projectServerId, attempting fallback")
        val detail = projectDetail ?: runCatching { api.getProjectDetail(projectServerId).data }
            .onFailure { Log.w("API", "⚠️ [LossInfo] Unable to load project detail for fallback (project $projectServerId)", it) }
            .getOrNull()
        val fallbackPropertyId = detail?.propertyId ?: detail?.properties?.firstOrNull()?.id

        if (fallbackPropertyId != null) {
            val propertyById = runCatching { api.getProperty(fallbackPropertyId).data }
                .onFailure { Log.e("API", "❌ [LossInfo] getProperty fallback failed for project $projectServerId (propertyId=$fallbackPropertyId)", it) }
                .getOrNull()
            if (propertyById != null) {
                return propertyById
            }
        }

        val embedded = detail?.properties?.firstOrNull()
        if (embedded != null) {
            Log.d("API", "🏠 [LossInfo] Using embedded property from project detail for project $projectServerId: id=${embedded.id}, address=${embedded.address}, city=${embedded.city}, state=${embedded.state}, zip=${embedded.postalCode}")
            return embedded
        }

        return null
    }

    private fun PropertyDto.toEntity(
        projectAddress: ProjectAddressDto? = null,
        project: OfflineProjectEntity? = null
    ): OfflinePropertyEntity {
        val timestamp = Date()
        val resolvedAddress = listOfNotNull(
            address?.takeIf { it.isNotBlank() },
            projectAddress?.address?.takeIf { it.isNotBlank() },
            projectAddress?.address2?.takeIf { it.isNotBlank() },
            project?.addressLine1?.takeIf { it.isNotBlank() },
            project?.addressLine2?.takeIf { it.isNotBlank() }
        ).firstOrNull() ?: ""
        val resolvedCity = city ?: projectAddress?.city
        val resolvedState = state ?: projectAddress?.state
        val resolvedZip = postalCode ?: projectAddress?.zip
        val resolvedLat = latitude ?: projectAddress?.latitude?.toDoubleOrNull()
        val resolvedLng = longitude ?: projectAddress?.longitude?.toDoubleOrNull()
        return OfflinePropertyEntity(
            propertyId = id,
            serverId = id,
            uuid = uuid ?: UUID.randomUUID().toString(),
            address = resolvedAddress,
            city = resolvedCity,
            state = resolvedState,
            zipCode = resolvedZip,
            latitude = resolvedLat,
            longitude = resolvedLng,
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
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val api = RetrofitClient.createService<OfflineSyncApi>()
    private val repository = ProjectLossInfoRepository(
        api = api,
        localDataService = rocketPlanApp.localDataService
    )

    private val _uiState = MutableStateFlow(ProjectLossInfoUiState())
    val uiState: StateFlow<ProjectLossInfoUiState> = _uiState
    private val _events = MutableSharedFlow<ProjectLossInfoEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ProjectLossInfoEvent> = _events

    private val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val dateTimeFormatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    private val claimIdempotencyKeys: MutableMap<Long, String> = mutableMapOf()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                isSaving = false,
                savingClaimId = null,
                errorMessage = null
            )
            val propertyReady = ensurePropertyAttached()
            if (propertyReady.isFailure) {
                val error = propertyReady.exceptionOrNull()
                val message = error?.message ?: "Property still syncing, please try again."
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isSaving = false,
                    savingClaimId = null,
                    errorMessage = message
                )
                _events.tryEmit(ProjectLossInfoEvent.PropertyMissing(message))
                return@launch
            }
            runCatching { repository.load(projectId) }
                .onSuccess { payload ->
                    _uiState.value = payload.toUiState()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isSaving = false,
                        savingClaimId = null,
                        errorMessage = error.message ?: "Unable to load loss info"
                    )
                }
        }
    }

    fun savePropertyInfo(form: PropertyInfoFormInput) {
        viewModelScope.launch {
            if (_uiState.value.isSaving) return@launch
            _uiState.update { it.copy(isSaving = true, savingClaimId = null, errorMessage = null) }

            val result = runCatching { savePropertyInfoInternal(form) }
            result.onSuccess { property ->
                _uiState.update { state ->
                    state.copy(
                        isSaving = false,
                        property = property
                    )
                }
                _events.emit(ProjectLossInfoEvent.SaveSuccess)
            }.onFailure { error ->
                val message = error.message ?: rocketPlanApp.getString(R.string.loss_info_save_failed)
                _uiState.update { it.copy(isSaving = false, errorMessage = message) }
                _events.emit(ProjectLossInfoEvent.SaveFailed(message))
            }
        }
    }

    fun saveLossInfo(form: LossInfoFormInput) {
        viewModelScope.launch {
            if (_uiState.value.isSaving) return@launch
            val previous = _uiState.value
            _uiState.value = previous.copy(
                isSaving = true,
                savingClaimId = null,
                selectedDamageTypeIds = form.selectedDamageTypeIds,
                selectedDamageCause = previous.damageCauses.firstOrNull { it.id == form.damageCauseId },
                damageCategory = form.damageCategory,
                lossClass = form.lossClass,
                lossDate = form.lossDate,
                callReceived = form.callReceived,
                crewDispatched = form.crewDispatched,
                arrivedOnSite = form.arrivedOnSite,
                errorMessage = null
            )

            val result = runCatching {
                saveLossInfoInternal(form, previous.selectedDamageTypeIds)
            }
            result.onSuccess {
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(ProjectLossInfoEvent.SaveSuccess)
                refresh()
            }.onFailure { error ->
                val message = error.message ?: rocketPlanApp.getString(R.string.loss_info_save_failed)
                _uiState.update { it.copy(isSaving = false, errorMessage = message) }
                _events.emit(ProjectLossInfoEvent.SaveFailed(message))
            }
        }
    }

    fun updateClaim(claimId: Long, request: ClaimMutationRequest) {
        viewModelScope.launch {
            if (_uiState.value.savingClaimId == claimId) return@launch
            _uiState.update { it.copy(savingClaimId = claimId, errorMessage = null) }

            val idempotencyKey = claimIdempotencyKeys.getOrPut(claimId) {
                request.idempotencyKey ?: UUID.randomUUID().toString()
            }
            val requestWithKey = request.copy(idempotencyKey = idempotencyKey)

            val result = runCatching { api.updateClaim(claimId, requestWithKey) }
            result.onSuccess { updated ->
                _uiState.update { state ->
                    state.copy(
                        savingClaimId = null,
                        claims = state.claims.map { item ->
                            if (item.claim.id == updated.id) item.copy(claim = updated) else item
                        }
                    )
                }
                _events.emit(ProjectLossInfoEvent.ClaimUpdated(updated))
            }.onFailure { error ->
                val message = error.message ?: rocketPlanApp.getString(R.string.loss_info_claim_save_failed)
                _uiState.update { it.copy(savingClaimId = null, errorMessage = message) }
                _events.emit(ProjectLossInfoEvent.ClaimUpdateFailed(message))
            }
        }
    }

    private fun ProjectLossInfoPayload.toUiState(): ProjectLossInfoUiState {
        val resolvedProperty = property.withResolvedType(project.propertyType)
        val propertyDamageTypes = resolvedProperty.propertyDamageTypes.orEmpty()
        val selectedDamageTypeIds = propertyDamageTypes.mapNotNull { it.id }.toSet()
        val projectDisplayName = listOfNotNull(
            project.addressLine1?.takeIf { it.isNotBlank() },
            project.title.takeIf { it.isNotBlank() },
            project.alias?.takeIf { it.isNotBlank() }
        ).firstOrNull()
            ?: rocketPlanApp.getString(R.string.loss_info_claim_project_tag)

        val claims = mutableListOf<ClaimListItem>().apply {
            projectClaims.forEach { add(ClaimListItem(it, projectDisplayName)) }
            locations.forEach { location ->
                val locationClaimList = locationClaims[location.id].orEmpty()
                if (locationClaimList.isNotEmpty()) {
                    locationClaimList.forEach { add(ClaimListItem(it, location.name)) }
                }
            }
        }

        return ProjectLossInfoUiState(
            isLoading = false,
            projectTitle = projectDisplayName,
            projectCode = project.uid?.takeIf { it.isNotBlank() }
                ?: project.projectNumber,
            projectCreatedAt = project.createdAt?.let { dateFormatter.format(it) },
            property = resolvedProperty,
            damageTypes = damageTypes,
            selectedDamageTypeIds = selectedDamageTypeIds,
            selectedDamageCause = resolvedProperty.damageCause,
            damageCauses = damageCauses,
            damageCategory = resolvedProperty.damageCategory,
            lossClass = resolvedProperty.lossClass,
            lossDate = DateUtils.parseApiDate(resolvedProperty.lossDate),
            callReceived = DateUtils.parseApiDate(resolvedProperty.callReceived),
            crewDispatched = DateUtils.parseApiDate(resolvedProperty.crewDispatched),
            arrivedOnSite = DateUtils.parseApiDate(resolvedProperty.arrivedOnSite),
            affectedLocations = locations.map { it.name },
            claims = claims
        )
    }

    private suspend fun saveLossInfoInternal(
        form: LossInfoFormInput,
        previousDamageTypeIds: Set<Long>
    ) = withContext(Dispatchers.IO) {
        val project = localDataService.getProject(projectId)
            ?: throw IllegalStateException("Project $projectId not found locally")
        val propertyId = project.propertyId
            ?: throw IllegalStateException("Property still syncing, please try again.")
        val propertyTypeId = resolvePropertyTypeId(project)
        val propertyTypeValue = project.propertyType
            ?: _uiState.value.property?.propertyType
            ?: PropertyType.fromApiValue(_uiState.value.property?.propertyType)?.apiValue

        val request = PropertyMutationRequest(
            propertyTypeId = propertyTypeId,
            damageCategory = form.damageCategory,
            lossClass = form.lossClass,
            lossDate = form.lossDate?.let { DateUtils.formatApiDate(it) },
            callReceived = form.callReceived?.let { DateUtils.formatApiDate(it) },
            crewDispatched = form.crewDispatched?.let { DateUtils.formatApiDate(it) },
            arrivedOnSite = form.arrivedOnSite?.let { DateUtils.formatApiDate(it) },
            damageCauseId = form.damageCauseId?.toInt()
        )

        offlineSyncRepository.updateProjectProperty(
            projectId = projectId,
            propertyId = propertyId,
            request = request,
            propertyTypeValue = propertyTypeValue
        ).getOrThrow()

        val propertyServerId = localDataService.getProperty(propertyId)?.serverId
            ?: _uiState.value.property?.id
            ?: throw IllegalStateException("Unable to resolve property for damage types")

        val toAdd = form.selectedDamageTypeIds - previousDamageTypeIds
        val toRemove = previousDamageTypeIds - form.selectedDamageTypeIds

        toAdd.forEach { api.addPropertyDamageType(propertyServerId, it) }
        toRemove.forEach { api.removePropertyDamageType(propertyServerId, it) }
    }

    private suspend fun savePropertyInfoInternal(form: PropertyInfoFormInput): PropertyDto =
        withContext(Dispatchers.IO) {
            val project = localDataService.getProject(projectId)
                ?: throw IllegalStateException("Project $projectId not found locally")
            val propertyId = project.propertyId
                ?: throw IllegalStateException("Property still syncing, please try again.")

            val propertyTypeId = form.propertyType?.propertyTypeId ?: resolvePropertyTypeId(project)
            val propertyTypeValue = form.propertyType?.apiValue
                ?: project.propertyType
                ?: _uiState.value.property?.propertyType
                ?: PropertyType.fromApiValue(_uiState.value.property?.propertyType)?.apiValue

            val request = PropertyMutationRequest(
                propertyTypeId = propertyTypeId,
                isCommercial = form.isCommercial,
                isResidential = form.isResidential,
                yearBuilt = form.yearBuilt,
                name = form.buildingName,
                referredByName = form.referredByName,
                referredByPhone = form.referredByPhone,
                isPlatinumAgent = form.isPlatinumAgent,
                asbestosStatusId = form.asbestosStatusId
            )

            offlineSyncRepository.updateProjectProperty(
                projectId = projectId,
                propertyId = propertyId,
                request = request,
                propertyTypeValue = propertyTypeValue
            ).getOrThrow()

            val propertyServerId = localDataService.getProperty(propertyId)?.serverId
                ?: _uiState.value.property?.id
                ?: throw IllegalStateException("Unable to resolve property for update")

            val updatedProjectType = localDataService.getProject(projectId)?.propertyType ?: project.propertyType
            api.getProperty(propertyServerId).data.withResolvedType(
                projectType = updatedProjectType,
                preferredType = form.propertyType
            )
        }

    private fun resolvePropertyTypeId(project: OfflineProjectEntity): Int {
        _uiState.value.property?.propertyTypeId?.toInt()?.let { return it }
        PropertyType.fromApiValue(project.propertyType)?.propertyTypeId?.let { return it }
        throw IllegalStateException("Missing property type for project $projectId")
    }

    fun formatDate(date: Date?): String =
        date?.let { dateFormatter.format(it) } ?: "—"

    fun formatDateTime(date: Date?): String =
        date?.let { dateTimeFormatter.format(it) } ?: "—"

    private fun PropertyDto.withResolvedType(
        projectType: String?,
        preferredType: PropertyType? = null
    ): PropertyDto {
        val projectTypeValue = projectType?.takeIf { it.isNotBlank() }
        val resolvedEnum = preferredType
            ?: PropertyType.fromApiValue(propertyType)
            ?: PropertyType.entries.firstOrNull { propertyTypeId?.toInt() == it.propertyTypeId }
            ?: PropertyType.fromApiValue(projectTypeValue)
        val resolvedTypeValue = propertyType?.takeIf { it.isNotBlank() }
            ?: resolvedEnum?.apiValue
            ?: projectTypeValue
        val resolvedTypeId = propertyTypeId ?: resolvedEnum?.propertyTypeId?.toLong()

        if (resolvedTypeValue == propertyType && resolvedTypeId == propertyTypeId) return this
        return copy(propertyType = resolvedTypeValue, propertyTypeId = resolvedTypeId)
    }

    /**
     * Ensure the property has been persisted locally before loading loss info to avoid
     * the race where the UI reads before syncProjectEssentials finishes.
     */
    private suspend fun ensurePropertyAttached(): Result<Long> {
        val project = localDataService.getProject(projectId)
            ?: return Result.failure(IllegalStateException("Project $projectId not found locally"))

        project.propertyId?.let { return Result.success(it) }

        val serverId = project.serverId
            ?: return Result.failure(IllegalStateException("Project is not synced yet; property unavailable"))

        when (val syncResult = offlineSyncRepository.syncProjectEssentials(serverId)) {
            is SyncResult.Success -> {
                // Proceed to wait for property to appear locally
            }
            is SyncResult.Incomplete -> {
                val message = when (syncResult.reason) {
                    IncompleteReason.MISSING_PROPERTY -> "Property not set up yet. Please choose a property type to continue."
                }
                return Result.failure(IllegalStateException(message))
            }
            is SyncResult.Failure -> {
                return Result.failure(syncResult.error ?: IllegalStateException("Unable to sync project essentials"))
            }
        }

        val propertyId = awaitPropertyId()
            ?: return Result.failure(IllegalStateException("Property still syncing, please try again."))

        return Result.success(propertyId)
    }

    private suspend fun awaitPropertyId(timeoutMs: Long = PROPERTY_WAIT_TIMEOUT_MS): Long? =
        withTimeoutOrNull(timeoutMs) {
            localDataService.observeProjects()
                .map { projects -> projects.firstOrNull { it.projectId == projectId }?.propertyId }
                .filterNotNull()
                .first()
        }

    companion object {
        private const val PROPERTY_WAIT_TIMEOUT_MS = 5_000L

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
