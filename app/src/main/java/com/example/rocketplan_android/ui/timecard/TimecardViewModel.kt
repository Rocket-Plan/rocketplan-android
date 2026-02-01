package com.example.rocketplan_android.ui.timecard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.OfflineTimecardEntity
import com.example.rocketplan_android.data.local.entity.OfflineTimecardTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

sealed class TimecardUiState {
    object Loading : TimecardUiState()
    data class Ready(
        val projectAddress: String,
        val isClockedIn: Boolean,
        val activeTimecard: TimecardUiItem?,
        val timecards: List<TimecardUiItem>,
        val timecardTypes: List<OfflineTimecardTypeEntity>,
        val todayTotalSeconds: Long,
        val weekTotalSeconds: Long,
        val elapsedSeconds: Long
    ) : TimecardUiState()
    data class Error(val message: String) : TimecardUiState()
}

data class TimecardUiItem(
    val timecardId: Long,
    val uuid: String,
    val timeIn: Date,
    val timeOut: Date?,
    val elapsed: Long?, // seconds
    val notes: String?,
    val timecardTypeId: Int,
    val timecardTypeName: String,
    val isActive: Boolean
)

class TimecardViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val timecardSyncService = rocketPlanApp.timecardSyncService
    private val secureStorage = rocketPlanApp.secureStorage

    private val _uiState = MutableStateFlow<TimecardUiState>(TimecardUiState.Loading)
    val uiState: StateFlow<TimecardUiState> = _uiState

    private val _elapsedSeconds = MutableStateFlow(0L)
    private var timerJob: Job? = null

    private var currentUserId: Long = 0L
    private var currentCompanyId: Long = 0L

    init {
        loadUserContext()
        observeTimecards()
    }

    private fun loadUserContext() {
        viewModelScope.launch(Dispatchers.IO) {
            currentUserId = secureStorage.getUserIdSync() ?: 0L
            currentCompanyId = secureStorage.getCompanyIdSync() ?: 0L
        }
    }

    private fun observeTimecards() {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeTimecardsForProject(projectId),
                localDataService.observeTimecardTypes(),
                _elapsedSeconds
            ) { projects, timecards, types, elapsed ->
                val project = projects.firstOrNull { it.projectId == projectId }
                resolveState(project, timecards, types, elapsed)
            }.collect { state ->
                _uiState.value = state
                // Start/stop timer based on active timecard
                if (state is TimecardUiState.Ready && state.isClockedIn) {
                    startTimer(state.activeTimecard?.timeIn)
                } else {
                    stopTimer()
                }
            }
        }
    }

    private fun resolveState(
        project: OfflineProjectEntity?,
        timecards: List<OfflineTimecardEntity>,
        types: List<OfflineTimecardTypeEntity>,
        elapsedSeconds: Long
    ): TimecardUiState {
        if (project == null) return TimecardUiState.Loading

        val activeTimecard = timecards.firstOrNull { it.timeOut == null && !it.isDeleted }
        val uiTimecards = timecards
            .filter { !it.isDeleted }
            .map { it.toUiItem(it == activeTimecard) }
            .sortedByDescending { it.timeIn }

        val todayTotal = calculateTodayTotal(timecards)
        val weekTotal = calculateWeekTotal(timecards)

        return TimecardUiState.Ready(
            projectAddress = buildProjectAddress(project),
            isClockedIn = activeTimecard != null,
            activeTimecard = activeTimecard?.toUiItem(true),
            timecards = uiTimecards,
            timecardTypes = types.ifEmpty { listOf(OfflineTimecardTypeEntity(1, "Standard", null)) },
            todayTotalSeconds = todayTotal,
            weekTotalSeconds = weekTotal,
            elapsedSeconds = elapsedSeconds
        )
    }

    fun clockIn(timecardTypeId: Int = 1, notes: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val types = localDataService.getTimecardTypes()
            val typeName = types.firstOrNull { it.typeId == timecardTypeId }?.name ?: "Standard"
            timecardSyncService.clockIn(
                projectId = projectId,
                userId = currentUserId,
                companyId = currentCompanyId,
                timecardTypeId = timecardTypeId,
                timecardTypeName = typeName,
                notes = notes
            )
        }
    }

    fun clockOut(notes: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            if (state is TimecardUiState.Ready && state.activeTimecard != null) {
                timecardSyncService.clockOut(
                    timecardId = state.activeTimecard.timecardId,
                    notes = notes
                )
            }
        }
    }

    fun updateTimecard(
        timecardId: Long,
        timeIn: Date? = null,
        timeOut: Date? = null,
        timecardTypeId: Int? = null,
        notes: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val types = localDataService.getTimecardTypes()
            val typeName = timecardTypeId?.let { id ->
                types.firstOrNull { it.typeId == id }?.name
            }
            timecardSyncService.updateTimecard(
                timecardId = timecardId,
                timeIn = timeIn,
                timeOut = timeOut,
                timecardTypeId = timecardTypeId,
                timecardTypeName = typeName,
                notes = notes
            )
        }
    }

    fun deleteTimecard(timecardId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            timecardSyncService.deleteTimecard(timecardId = timecardId)
        }
    }

    private fun startTimer(clockInTime: Date?) {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val startTime = clockInTime?.time ?: now
                _elapsedSeconds.value = (now - startTime) / 1000
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _elapsedSeconds.value = 0L
    }

    private fun calculateTodayTotal(timecards: List<OfflineTimecardEntity>): Long {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        return timecards
            .filter { !it.isDeleted && it.timeIn >= today }
            .sumOf { timecard ->
                if (timecard.timeOut != null) {
                    (timecard.timeOut.time - timecard.timeIn.time) / 1000
                } else {
                    (System.currentTimeMillis() - timecard.timeIn.time) / 1000
                }
            }
    }

    private fun calculateWeekTotal(timecards: List<OfflineTimecardEntity>): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val weekStart = calendar.time

        return timecards
            .filter { !it.isDeleted && it.timeIn >= weekStart }
            .sumOf { timecard ->
                if (timecard.timeOut != null) {
                    (timecard.timeOut.time - timecard.timeIn.time) / 1000
                } else {
                    (System.currentTimeMillis() - timecard.timeIn.time) / 1000
                }
            }
    }

    private fun buildProjectAddress(project: OfflineProjectEntity): String {
        val address = project.addressLine1?.takeIf { it.isNotBlank() }
        val title = project.title.takeIf { it.isNotBlank() }
        val alias = project.alias?.takeIf { it.isNotBlank() }
        return listOfNotNull(address, title, alias).firstOrNull()
            ?: "Project ${project.projectId}"
    }

    private fun OfflineTimecardEntity.toUiItem(isActive: Boolean): TimecardUiItem =
        TimecardUiItem(
            timecardId = timecardId,
            uuid = uuid,
            timeIn = timeIn,
            timeOut = timeOut,
            elapsed = elapsed,
            notes = notes,
            timecardTypeId = timecardTypeId,
            timecardTypeName = timecardTypeName,
            isActive = isActive
        )

    override fun onCleared() {
        super.onCleared()
        stopTimer()
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(TimecardViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return TimecardViewModel(application, projectId) as T
            }
        }
    }
}
