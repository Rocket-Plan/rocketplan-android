package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.ImageProcessorPhotoEntity
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.data.model.ProjectStatus
import com.example.rocketplan_android.data.local.model.ImageProcessorAssemblyWithDetails
import com.example.rocketplan_android.logging.LogLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val syncQueueManager = rocketPlanApp.syncQueueManager
    private val remoteLogger = rocketPlanApp.remoteLogger
    private val authRepository = rocketPlanApp.authRepository
    private val imageProcessorRepository = rocketPlanApp.imageProcessorRepository

    private val _uiState = MutableStateFlow<ProjectsUiState>(ProjectsUiState.Loading)
    val uiState: StateFlow<ProjectsUiState> = _uiState

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing
    private val _activeAssemblyUpload = MutableStateFlow<AssemblyUploadBubbleState?>(null)
    val activeAssemblyUpload: StateFlow<AssemblyUploadBubbleState?> = _activeAssemblyUpload

    private var assemblyProgressJob: Job? = null
    private var trackedAssemblyId: String? = null
    private var latestAssemblyDetails: ImageProcessorAssemblyWithDetails? = null
    private var latestAssemblyPhotos: List<ImageProcessorPhotoEntity> = emptyList()

    init {
        Log.d(TAG, "📱 ProjectsViewModel initialized")

        viewModelScope.launch {
            Log.d(TAG, "🔄 Starting initial sync...")
            syncQueueManager.ensureInitialSync()
        }

        viewModelScope.launch {
            syncQueueManager.errors.collect { message ->
                Log.e(TAG, "❌ Sync error: $message")
                _isRefreshing.value = false
                val currentState = _uiState.value
                if (currentState !is ProjectsUiState.Success || (currentState.myProjects.isEmpty() && currentState.projectsByStatus.values.all { it.isEmpty() })) {
                    _uiState.value = ProjectsUiState.Error(message)
                }
            }
        }

        viewModelScope.launch {
            combine(
                localDataService.observeProjects(),
                authRepository.observeCompanyId(),
                syncQueueManager.assignedProjects,
                syncQueueManager.initialSyncCompleted
            ) { projects, companyId, assignedIds, syncCompleted ->
                val filteredProjects = companyId?.let { id ->
                    projects.filter { it.companyId == id }
                } ?: projects
                ProjectsData(filteredProjects, companyId, assignedIds, syncCompleted)
            }.collect { data ->
                Log.d(TAG, "📊 Received ${data.projects.size} projects from database for company ${data.companyId ?: "unknown"} (assigned=${data.assignedIds.size}, syncCompleted=${data.syncCompleted})")

                val mappedProjects = data.projects.map { it.toListItem() }
                val myProjects = mappedProjects.filter { data.assignedIds.contains(it.projectId) }
                val projectsByStatus = ProjectStatus.orderedStatuses.associateWith { status ->
                    mappedProjects.filter { it.matchesStatus(status) }
                }
                val statusCounts = projectsByStatus.entries.joinToString { "${it.key.apiValue}=${it.value.size}" }

                Log.d(TAG, "✅ Projects - My Projects: ${myProjects.size}, byStatus: [$statusCounts]")

                remoteLogger.log(
                    level = LogLevel.DEBUG,
                    tag = TAG,
                    message = "Projects updated: my=${myProjects.size}, byStatus=[$statusCounts]",
                    metadata = mapOf("source" to "room_update")
                )

                // Keep showing loading state until initial sync completes (unless we have projects)
                if (mappedProjects.isEmpty() && !data.syncCompleted) {
                    // Still loading - keep the loading state
                    Log.d(TAG, "⏳ Waiting for initial sync to complete...")
                } else {
                    _uiState.value = ProjectsUiState.Success(myProjects, projectsByStatus)
                }
                _isRefreshing.value = false
            }
        }

        viewModelScope.launch {
            observeActiveAssemblyUploads()
        }
    }

    fun refreshProjects() {
        Log.d(TAG, "🔄 Manual refresh triggered")
        _uiState.value = ProjectsUiState.Loading
        _isRefreshing.value = true
        remoteLogger.log(
            level = LogLevel.INFO,
            tag = TAG,
            message = "Manual refresh requested by user"
        )
        syncQueueManager.refreshProjects()
    }

    fun prioritizeProject(projectId: Long) {
        Log.d(TAG, "⚡ Prioritizing project: $projectId")
        syncQueueManager.prioritizeProject(projectId)
    }

    private suspend fun observeActiveAssemblyUploads() {
        imageProcessorRepository.observeAllAssembliesWithDetails()
            .collectLatest { assemblies ->
                val activeAssembly = assemblies
                    .filter { isActiveAssemblyStatus(it.assembly.status) }
                    .minWithOrNull(
                        compareBy<ImageProcessorAssemblyWithDetails> { statusPriority(it.assembly.status) }
                            .thenBy { it.assembly.createdAt }
                    )

                if (activeAssembly == null) {
                    trackedAssemblyId = null
                    latestAssemblyDetails = null
                    latestAssemblyPhotos = emptyList()
                    assemblyProgressJob?.cancel()
                    _activeAssemblyUpload.value = null
                    return@collectLatest
                }

                latestAssemblyDetails = activeAssembly
                val newAssemblyId = activeAssembly.assembly.assemblyId

                if (newAssemblyId != trackedAssemblyId) {
                    trackedAssemblyId = newAssemblyId
                    latestAssemblyPhotos = emptyList()
                    assemblyProgressJob?.cancel()
                    assemblyProgressJob = viewModelScope.launch {
                        imageProcessorRepository.observePhotosByAssemblyLocalId(activeAssembly.assembly.id)
                            .collectLatest { photos ->
                                latestAssemblyPhotos = photos
                                latestAssemblyDetails?.let { details ->
                                    _activeAssemblyUpload.value = buildUploadBubbleState(details, photos)
                                }
                            }
                    }
                }

                _activeAssemblyUpload.value = buildUploadBubbleState(
                    activeAssembly,
                    latestAssemblyPhotos
                )
            }
    }

    private fun isActiveAssemblyStatus(statusValue: String): Boolean {
        val status = AssemblyStatus.fromValue(statusValue) ?: return false
        return status !in setOf(
            AssemblyStatus.COMPLETED,
            AssemblyStatus.FAILED,
            AssemblyStatus.CANCELLED,
            AssemblyStatus.PROCESSING
        )
    }

    private fun statusPriority(statusValue: String): Int {
        return when (AssemblyStatus.fromValue(statusValue)) {
            AssemblyStatus.UPLOADING -> 0
            AssemblyStatus.RETRYING -> 1
            AssemblyStatus.WAITING_FOR_CONNECTIVITY,
            AssemblyStatus.WAITING_FOR_ROOM -> 2
            AssemblyStatus.CREATED,
            AssemblyStatus.CREATING -> 3
            AssemblyStatus.QUEUED,
            AssemblyStatus.PENDING -> 4
            else -> 5
        }
    }

    private fun buildUploadBubbleState(
        details: ImageProcessorAssemblyWithDetails,
        photos: List<ImageProcessorPhotoEntity>
    ): AssemblyUploadBubbleState? {
        val assembly = details.assembly
        val status = AssemblyStatus.fromValue(assembly.status) ?: return null
        val totalPhotos = assembly.totalFiles.takeIf { it > 0 } ?: photos.size
        val completed = photos.count { it.status == PhotoStatus.COMPLETED.value }
        val inProgress = photos.count {
            it.status == PhotoStatus.UPLOADING.value || it.status == PhotoStatus.PROCESSING.value
        }
        val totalBytes = photos.sumOf { it.fileSize.coerceAtLeast(0L) }
        val uploadedBytes = photos.sumOf { photo ->
            val cappedSize = photo.fileSize.takeIf { size -> size > 0 } ?: photo.bytesUploaded
            photo.bytesUploaded.coerceAtLeast(0L).coerceAtMost(cappedSize)
        }

        val progressPercent = when {
            totalBytes > 0 -> ((uploadedBytes.toDouble() / totalBytes.toDouble()) * 100)
                .roundToInt()
                .coerceIn(0, 100)
            totalPhotos > 0 -> ((completed.toDouble() / totalPhotos.toDouble()) * 100)
                .roundToInt()
                .coerceIn(0, 100)
            else -> 0
        }

        val projectName = details.projectName?.takeIf { it.isNotBlank() }
            ?: "Project #${assembly.projectId}"
        val roomName = details.roomName?.takeIf { it.isNotBlank() }
            ?: assembly.roomId?.let { "Room #$it" }

        return AssemblyUploadBubbleState(
            assemblyId = assembly.assemblyId,
            projectName = projectName,
            roomName = roomName,
            totalPhotos = totalPhotos,
            completedPhotos = completed,
            inProgressPhotos = inProgress,
            progressPercent = progressPercent,
            status = status
        )
    }

    companion object {
        private const val TAG = "ProjectsViewModel"
    }
}

data class AssemblyUploadBubbleState(
    val assemblyId: String,
    val projectName: String,
    val roomName: String?,
    val totalPhotos: Int,
    val completedPhotos: Int,
    val inProgressPhotos: Int,
    val progressPercent: Int,
    val status: AssemblyStatus
) {
    val isPaused: Boolean
        get() = status == AssemblyStatus.WAITING_FOR_CONNECTIVITY
}

data class ProjectListItem(
    val projectId: Long,
    val title: String,
    val projectCode: String,
    val alias: String? = null,
    val status: String,
    val propertyId: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

sealed class ProjectsUiState {
    object Loading : ProjectsUiState()
    data class Success(
        val myProjects: List<ProjectListItem>,
        val projectsByStatus: Map<ProjectStatus, List<ProjectListItem>>
    ) : ProjectsUiState()
    data class Error(val message: String) : ProjectsUiState()
}

private data class ProjectsData(
    val projects: List<com.example.rocketplan_android.data.local.entity.OfflineProjectEntity>,
    val companyId: Long?,
    val assignedIds: Set<Long>,
    val syncCompleted: Boolean
)
