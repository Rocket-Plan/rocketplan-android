package com.example.rocketplan_android.ui.rocketdry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.model.FileToUpload
import com.example.rocketplan_android.util.UuidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ExternalAtmosphericLogsUiState {
    object Loading : ExternalAtmosphericLogsUiState()
    data class Ready(
        val projectAddress: String,
        val logs: List<AtmosphericLogItem>
    ) : ExternalAtmosphericLogsUiState()
}

/**
 * Holds dialog values while navigating to camera for photo capture.
 * Used to restore dialog state when returning from camera.
 */
data class PendingLogCapture(
    val humidity: Double?,
    val temperature: Double?,
    val pressure: Double?,
    val windSpeed: Double?
)

class ExternalAtmosphericLogsViewModel(
    application: Application,
    private val projectId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val imageProcessorRepository = rocketPlanApp.imageProcessorRepository

    private val _uiState = MutableStateFlow<ExternalAtmosphericLogsUiState>(ExternalAtmosphericLogsUiState.Loading)
    val uiState: StateFlow<ExternalAtmosphericLogsUiState> = _uiState

    // Holds dialog values while navigating to camera
    private val _pendingLogCapture = MutableStateFlow<PendingLogCapture?>(null)
    val pendingLogCapture: StateFlow<PendingLogCapture?> = _pendingLogCapture

    /**
     * Save current dialog values before navigating to camera.
     */
    fun savePendingCapture(humidity: Double?, temperature: Double?, pressure: Double?, windSpeed: Double?) {
        _pendingLogCapture.value = PendingLogCapture(humidity, temperature, pressure, windSpeed)
        android.util.Log.d("ExternalAtmosLogsVM", "📷 Saved pending capture: h=$humidity t=$temperature p=$pressure w=$windSpeed")
    }

    /**
     * Clear pending capture after dialog is shown or cancelled.
     */
    fun clearPendingCapture() {
        _pendingLogCapture.value = null
        android.util.Log.d("ExternalAtmosLogsVM", "📷 Cleared pending capture")
    }

    init {
        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                localDataService.observeAtmosphericLogsForProject(projectId)
            ) { projects, logs ->
                Pair(projects, logs)
            }.collect { (projects, logs) ->
                val project = projects.firstOrNull { it.projectId == projectId }
                if (project == null) {
                    _uiState.value = ExternalAtmosphericLogsUiState.Loading
                } else {
                    // Filter for external logs only (roomId == null)
                    val filtered = logs.filter { it.roomId == null && it.isExternal && !it.isDeleted }

                    // Debug logging to diagnose photo issues
                    filtered.forEach { log ->
                        android.util.Log.d(
                            "ExternalAtmosLogsVM",
                            "📊 Log data: logId=${log.logId}, serverId=${log.serverId}, " +
                            "photoUrl=${log.photoUrl}, photoLocalPath=${log.photoLocalPath}, " +
                            "assemblyId=${log.photoAssemblyId}, uploadStatus=${log.photoUploadStatus}, " +
                            "syncStatus=${log.syncStatus}"
                        )
                    }

                    val externalLogs = filtered
                        .sortedByDescending { it.date.time }
                        .mapIndexed { index, log -> log.toUiItem(filtered.size - index) }

                    _uiState.value = ExternalAtmosphericLogsUiState.Ready(
                        projectAddress = buildProjectAddress(project),
                        logs = externalLogs
                    )
                }
            }
        }
    }

    fun addExternalAtmosphericLog(
        humidity: Double,
        temperature: Double,
        pressure: Double,
        windSpeed: Double,
        photoLocalPath: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = Date()
            val hasPhoto = !photoLocalPath.isNullOrBlank()
            val logUuid = UuidUtils.generateUuidV7()

            // Create assembly at photo capture time (before log syncs)
            // The assembly will be promoted when the log gets a serverId
            var photoAssemblyId: String? = null
            if (hasPhoto && photoLocalPath != null) {
                val filename = "atmos_${logUuid}_${System.currentTimeMillis()}.jpg"
                val fileToUpload = FileToUpload(
                    uri = Uri.parse(photoLocalPath),
                    filename = filename,
                    deleteOnCompletion = false
                )
                val assemblyResult = imageProcessorRepository.createAssembly(
                    roomId = null, // External atmospheric logs are project-level
                    projectId = projectId,
                    filesToUpload = listOf(fileToUpload),
                    templateId = "atmospheric_log",
                    entityType = "AtmosphericLog", // Must match iOS/server expected format
                    entityId = null, // No serverId yet - will be populated after sync
                    entityUuid = logUuid
                )
                assemblyResult.onSuccess { assemblyId ->
                    photoAssemblyId = assemblyId
                    android.util.Log.d(
                        "ExternalAtmosLogsVM",
                        "📸 Created WAITING_FOR_ENTITY assembly for atmospheric log photo: assemblyId=$assemblyId logUuid=$logUuid"
                    )
                }.onFailure { error ->
                    android.util.Log.e(
                        "ExternalAtmosLogsVM",
                        "❌ Failed to create assembly for atmospheric log photo: logUuid=$logUuid",
                        error
                    )
                }
            }

            val log = OfflineAtmosphericLogEntity(
                logId = -System.currentTimeMillis(),
                uuid = logUuid,
                projectId = projectId,
                roomId = null,
                date = now,
                relativeHumidity = humidity,
                temperature = temperature,
                pressure = pressure,
                windSpeed = windSpeed,
                isExternal = true,
                photoLocalPath = if (hasPhoto) photoLocalPath else null,
                photoUploadStatus = if (photoAssemblyId != null) "queued" else "none",
                photoAssemblyId = photoAssemblyId,
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            android.util.Log.d(
                "ExternalAtmosLogsVM",
                "🧪 Saving external atmospheric log: uuid=${log.uuid} projectId=$projectId assemblyId=$photoAssemblyId"
            )
            runCatching { localDataService.saveAtmosphericLogs(listOf(log)) }
                .onSuccess {
                    android.util.Log.d(
                        "ExternalAtmosLogsVM",
                        "✅ External atmospheric log saved: uuid=${log.uuid}"
                    )
                    runCatching {
                        val savedLog = localDataService.getAtmosphericLogByUuid(log.uuid)
                        if (savedLog != null) {
                            offlineSyncRepository.enqueueAtmosphericLogSync(savedLog)
                        }
                    }
                }
                .onFailure {
                    android.util.Log.e(
                        "ExternalAtmosLogsVM",
                        "❌ Failed to save external atmospheric log uuid=${log.uuid}",
                        it
                    )
                }
        }
    }

    fun deleteAtmosphericLog(logId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            android.util.Log.d("ExternalAtmosLogsVM", "🗑️ Deleting atmospheric log: logId=$logId")
            runCatching {
                val log = localDataService.getAtmosphericLog(logId)
                if (log != null) {
                    val deletedLog = log.copy(
                        isDeleted = true,
                        updatedAt = Date(),
                        syncStatus = SyncStatus.PENDING,
                        isDirty = true
                    )
                    localDataService.saveAtmosphericLogs(listOf(deletedLog))
                    offlineSyncRepository.enqueueAtmosphericLogSync(deletedLog)
                    android.util.Log.d("ExternalAtmosLogsVM", "✅ Atmospheric log deleted: logId=$logId")
                }
            }.onFailure {
                android.util.Log.e("ExternalAtmosLogsVM", "❌ Failed to delete atmospheric log: logId=$logId", it)
            }
        }
    }

    private fun OfflineAtmosphericLogEntity.toUiItem(index: Int): AtmosphericLogItem =
        AtmosphericLogItem(
            logId = logId,
            roomId = roomId,
            roomName = null,
            dateTime = formatDateTimeWithIndex(date, index),
            humidity = relativeHumidity,
            temperature = temperature,
            pressure = pressure ?: 0.0,
            windSpeed = windSpeed ?: 0.0,
            isExternal = isExternal,
            photoUrl = photoUrl,
            photoLocalPath = photoLocalPath,
            createdAt = formatDateTime(createdAt),
            updatedAt = formatDateTime(updatedAt)
        )

    private fun buildProjectAddress(project: OfflineProjectEntity): String {
        val address = project.addressLine1?.takeIf { it.isNotBlank() }
        val title = project.title.takeIf { it.isNotBlank() }
        val alias = project.alias?.takeIf { it.isNotBlank() }
        return listOfNotNull(address, title, alias).firstOrNull()
            ?: "Project ${project.projectId}"
    }

    private fun formatDateTimeWithIndex(date: Date, index: Int): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
            .replace("AM", "am")
            .replace("PM", "pm")
        return "#$index, $formatted"
    }

    private fun formatDateTime(date: Date): String {
        val formatter = SimpleDateFormat("MMM d, h:mma", Locale.getDefault())
        val formatted = formatter.format(date)
        return formatted
            .replace("AM", "am")
            .replace("PM", "pm")
    }

    companion object {
        fun provideFactory(
            application: Application,
            projectId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ExternalAtmosphericLogsViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return ExternalAtmosphericLogsViewModel(application, projectId) as T
            }
        }
    }
}
