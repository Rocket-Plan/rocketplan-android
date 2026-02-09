package com.example.rocketplan_android.ui.crew

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineProjectUserEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CrewMember(
    val userId: Long,
    val name: String,
    val email: String,
    val isAdmin: Boolean,
    val isCurrentUser: Boolean,
    val isPending: Boolean = false
)

data class CompanyEmployee(
    val userId: Long,
    val name: String,
    val email: String,
    val isAlreadyCrew: Boolean
)

sealed class CrewUiState {
    object Loading : CrewUiState()
    data class Ready(
        val crewMembers: List<CrewMember>,
        val isCurrentUserAdmin: Boolean,
        val isEmpty: Boolean
    ) : CrewUiState()
    data class Error(val message: String) : CrewUiState()
}

sealed class AddCrewUiState {
    object Hidden : AddCrewUiState()
    object Loading : AddCrewUiState()
    data class Ready(
        val employees: List<CompanyEmployee>,
        val selectedIds: Set<Long>,
        val isAdding: Boolean
    ) : AddCrewUiState()
}

class CrewViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueEnqueuer = rocketPlanApp.offlineSyncRepository.syncQueueEnqueuer
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val secureStorage = rocketPlanApp.secureStorage

    private val _uiState = MutableStateFlow<CrewUiState>(CrewUiState.Loading)
    val uiState: StateFlow<CrewUiState> = _uiState

    private val _addCrewState = MutableStateFlow<AddCrewUiState>(AddCrewUiState.Hidden)
    val addCrewState: StateFlow<AddCrewUiState> = _addCrewState

    private var currentUserId: Long = 0L
    private var currentCompanyId: Long = 0L
    private var serverProjectId: Long? = null
    private var isCurrentUserAdmin = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ensureContext()
            observeCrew()
        }
    }

    private fun observeCrew() {
        val sProjectId = serverProjectId
        if (sProjectId == null) {
            _uiState.value = CrewUiState.Error("Project not synced yet")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            localDataService.observeProjectUsers(sProjectId).collect { entities ->
                val crewMembers = entities.map { entity ->
                    CrewMember(
                        userId = entity.userServerId,
                        name = listOfNotNull(entity.firstName, entity.lastName)
                            .joinToString(" ")
                            .ifBlank { entity.email },
                        email = entity.email,
                        isAdmin = entity.isAdmin,
                        isCurrentUser = entity.userServerId == currentUserId,
                        isPending = entity.isPendingAdd
                    )
                }

                _uiState.value = CrewUiState.Ready(
                    crewMembers = crewMembers,
                    isCurrentUserAdmin = isCurrentUserAdmin,
                    isEmpty = crewMembers.isEmpty()
                )
            }
        }
    }

    fun refreshCrew() {
        // Trigger a project sync to refresh crew data from server
        syncQueueManager.processPendingOperations()
    }

    fun openAddCrewPicker() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _addCrewState.value = AddCrewUiState.Loading
                ensureContext()

                val sProjectId = serverProjectId ?: return@launch
                val currentCrew = localDataService.getProjectUsersSync(sProjectId)
                val currentCrewServerIds = currentCrew.map { it.userServerId }.toSet()

                val allUsers = localDataService.getUsersForCompany(currentCompanyId)
                val employees = allUsers.map { entity ->
                    CompanyEmployee(
                        userId = entity.serverId ?: 0L,
                        name = listOfNotNull(entity.firstName, entity.lastName)
                            .joinToString(" ")
                            .ifBlank { entity.email },
                        email = entity.email,
                        isAlreadyCrew = currentCrewServerIds.contains(entity.serverId)
                    )
                }.filter { it.userId > 0 && !it.isAlreadyCrew }

                _addCrewState.value = AddCrewUiState.Ready(
                    employees = employees,
                    selectedIds = emptySet(),
                    isAdding = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load employees", e)
                _addCrewState.value = AddCrewUiState.Hidden
            }
        }
    }

    fun dismissAddCrewPicker() {
        _addCrewState.value = AddCrewUiState.Hidden
    }

    fun toggleEmployeeSelection(userId: Long) {
        val current = _addCrewState.value
        if (current is AddCrewUiState.Ready && !current.isAdding) {
            val newSelected = if (current.selectedIds.contains(userId)) {
                current.selectedIds - userId
            } else {
                current.selectedIds + userId
            }
            _addCrewState.value = current.copy(selectedIds = newSelected)
        }
    }

    fun confirmAddCrew() {
        val current = _addCrewState.value
        if (current !is AddCrewUiState.Ready || current.selectedIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _addCrewState.value = current.copy(isAdding = true)
                val sProjectId = serverProjectId ?: return@launch

                // Get employee details for optimistic local insert
                val allUsers = localDataService.getUsersForCompany(currentCompanyId)
                val userMap = allUsers.associateBy { it.serverId }

                for (userId in current.selectedIds) {
                    val user = userMap[userId]
                    // Optimistic insert into Room with isPendingAdd = true
                    val entity = OfflineProjectUserEntity(
                        projectServerId = sProjectId,
                        userServerId = userId,
                        firstName = user?.firstName,
                        lastName = user?.lastName,
                        email = user?.email ?: "",
                        isAdmin = false,
                        isPendingAdd = true
                    )
                    localDataService.upsertProjectUser(entity)

                    // Enqueue sync operation
                    syncQueueEnqueuer.enqueueProjectUserAdd(sProjectId, userId)
                }

                _addCrewState.value = AddCrewUiState.Hidden

                // Trigger immediate sync
                syncQueueManager.processPendingOperations()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add crew", e)
                _addCrewState.value = current.copy(isAdding = false)
            }
        }
    }

    fun removeCrewMember(userId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sProjectId = serverProjectId ?: return@launch

                // Optimistic: mark as pending remove (hides from UI via DAO query)
                localDataService.markProjectUserPendingRemove(sProjectId, userId)

                // Enqueue sync operation
                syncQueueEnqueuer.enqueueProjectUserRemove(sProjectId, userId)

                // Trigger immediate sync
                syncQueueManager.processPendingOperations()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove crew member", e)
            }
        }
    }

    private suspend fun ensureContext() {
        if (currentUserId == 0L) {
            currentUserId = secureStorage.getUserIdSync() ?: 0L
        }
        if (currentCompanyId == 0L) {
            currentCompanyId = secureStorage.getCompanyIdSync() ?: 0L
        }
        if (serverProjectId == null) {
            val project = localDataService.getProject(projectId)
            serverProjectId = project?.serverId
        }
        if (!isCurrentUserAdmin && currentUserId > 0L) {
            isCurrentUserAdmin = localDataService.isUserCompanyAdmin(currentUserId)
        }
    }

    companion object {
        private const val TAG = "CrewViewModel"

        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CrewViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return CrewViewModel(application, projectId) as T
            }
        }
    }
}
