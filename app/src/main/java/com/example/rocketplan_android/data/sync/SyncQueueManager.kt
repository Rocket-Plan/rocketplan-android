package com.example.rocketplan_android.data.sync

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.rocketplan_android.config.AppConfig
import com.example.rocketplan_android.data.local.DeletionTombstoneCache
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.data.repository.SyncSegment
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.realtime.ProjectRealtimeManager
import com.example.rocketplan_android.realtime.PhotoSyncRealtimeManager
import com.example.rocketplan_android.work.PhotoCacheScheduler
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

@OptIn(FlowPreview::class)
class SyncQueueManager(
    private val authRepository: AuthRepository,
    private val syncRepository: OfflineSyncRepository,
    private val localDataService: LocalDataService,
    private val photoCacheScheduler: PhotoCacheScheduler,
    private val remoteLogger: RemoteLogger,
    private val connectivityManager: ConnectivityManager? = null
) {

    private var photoSyncRealtimeManager: PhotoSyncRealtimeManager? = null
    private var projectRealtimeManager: ProjectRealtimeManager? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val queue = PriorityQueue<QueuedTask>(
        compareBy<QueuedTask> { it.priority }.thenBy { it.enqueuedAt }
    )
    private val taskIndex = mutableMapOf<String, QueuedTask>()
    private val notifier = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    /** Exposes the currently executing sync job for UI status display */
    private val _currentSyncJob = MutableStateFlow<SyncJob?>(null)
    val currentSyncJob: StateFlow<SyncJob?> = _currentSyncJob

    /** Detailed sync progress for UI display - includes project UID and phase */
    data class SyncProgress(
        val job: SyncJob,
        val projectUid: String?,
        val phaseDescription: String
    )
    private val _currentSyncProgress = MutableStateFlow<SyncProgress?>(null)
    val currentSyncProgress: StateFlow<SyncProgress?> = _currentSyncProgress
    private val initialSyncStarted = AtomicBoolean(false)
    private val _initialSyncCompleted = MutableStateFlow(false)
    val initialSyncCompleted: StateFlow<Boolean> = _initialSyncCompleted
    private val assignedProjectIds = MutableStateFlow<Set<Long>>(emptySet())
    val assignedProjects: StateFlow<Set<Long>> = assignedProjectIds
    private val _assignedProjectsLoaded = MutableStateFlow(false)
    val assignedProjectsLoaded: StateFlow<Boolean> = _assignedProjectsLoaded

    // Track active project sync jobs for cancellation
    private val activeProjectSyncJobs = mutableMapOf<Long, Job>()
    private val activeProjectModes = mutableMapOf<Long, SyncJob.ProjectSyncMode>()
    @Volatile
    private var foregroundProjectId: Long? = null
    // Track projects with pending photo sync jobs to avoid duplicates
    private val pendingPhotoSyncs = mutableSetOf<Long>()
    private val _photoSyncingProjects = MutableStateFlow<Set<Long>>(emptySet())
    val photoSyncingProjects: StateFlow<Set<Long>> = _photoSyncingProjects
    private val _projectSyncingProjects = MutableStateFlow<Set<Long>>(emptySet())
    val projectSyncingProjects: StateFlow<Set<Long>> = _projectSyncingProjects
    private val _projectEssentialsFailed = MutableStateFlow<Set<Long>>(emptySet())
    val projectEssentialsFailed: StateFlow<Set<Long>> = _projectEssentialsFailed
    private val pendingSyncProjectsForce = AtomicBoolean(false)
    @Volatile
    private var lastForegroundSyncAt = -1L

    // Track projects with updates for incremental sync
    private val pendingUpdatedProjectIds = mutableSetOf<Long>()

    init {
        scope.launch { processLoop() }
        scope.launch { observePendingOperations() }
        scope.launch { scheduledRetryTicker() }
    }

    /**
     * Periodically checks for backoff-scheduled operations that are now due.
     * This ensures ops with future scheduledAt don't stall indefinitely waiting
     * for another DB change to trigger observePendingOperations.
     */
    private suspend fun scheduledRetryTicker() {
        while (true) {
            delay(SCHEDULED_RETRY_CHECK_INTERVAL_MS)
            try {
                if (localDataService.hasDueScheduledOperations()) {
                    Log.d(TAG, "⏰ Scheduled retry ticker: found due operations, triggering process")
                    enqueue(SyncJob.ProcessPendingOperations)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Scheduled retry ticker check failed", e)
            }
        }
    }

    /**
     * Sets up the photo sync realtime manager and starts listening for Pusher events.
     * Call this after both SyncQueueManager and PhotoSyncRealtimeManager are initialized.
     */
    fun setPhotoSyncRealtimeManager(manager: PhotoSyncRealtimeManager) {
        this.photoSyncRealtimeManager = manager
        scope.launch {
            manager.photoUploadCompleted.collect {
                Log.d(TAG, "📷 Received photo upload completed event from Pusher")
                refreshCurrentProjectPhotos()
            }
        }
    }

    fun setProjectRealtimeManager(manager: ProjectRealtimeManager) {
        this.projectRealtimeManager = manager
    }

    suspend fun ensureInitialSync() {
        if (initialSyncStarted.compareAndSet(false, true)) {
            enqueue(SyncJob.EnsureUserContext)
            enqueue(SyncJob.ProcessPendingOperations)
            enqueue(SyncJob.SyncDeletedRecords)  // Sync deletions from server
            enqueue(SyncJob.SyncProjects(force = true))  // Full sync on first login
        }
    }

    fun refreshProjects() {
        scope.launch {
            enqueue(SyncJob.EnsureUserContext)
            enqueue(SyncJob.ProcessPendingOperations)
            enqueue(SyncJob.SyncDeletedRecords)  // Sync deletions from server
            enqueue(SyncJob.SyncProjects(force = true))
        }
    }

    /**
     * Incremental refresh: only syncs projects that have actually changed.
     * Uses /api/sync/updated to identify changed projects, then queues only those for sync.
     * Falls back to full sync if the updated records fetch fails.
     */
    fun refreshProjectsIncremental() {
        scope.launch {
            enqueue(SyncJob.EnsureUserContext)
            enqueue(SyncJob.ProcessPendingOperations)
            enqueue(SyncJob.SyncDeletedRecords)
            enqueue(SyncJob.SyncUpdatedRecords)
            enqueue(SyncJob.SyncProjects(force = false))
        }
    }

    /**
     * Called when the app comes to foreground. Pushes any pending local changes
     * and syncs projects if data is stale (older than threshold).
     */
    fun syncOnForeground() {
        scope.launch {
            // Reset any FAILED operations to give them another chance now that
            // dependencies may have resolved (e.g., server IDs populated)
            resetFailedOperations()

            // Always push pending local changes
            enqueue(SyncJob.ProcessPendingOperations)

            // Skip pull if network unavailable
            if (!isNetworkAvailable()) {
                Log.d(TAG, "⏭️ Skipping foreground sync (no network)")
                return@launch
            }

            // Check if we should pull updates (avoid hammering server on rapid foreground/background)
            val now = System.currentTimeMillis()
            val (shouldSync, elapsed) = mutex.withLock {
                val elapsed = if (lastForegroundSyncAt < 0) null else now - lastForegroundSyncAt
                if (elapsed == null || elapsed > AppConfig.FOREGROUND_SYNC_THRESHOLD_MS) {
                    lastForegroundSyncAt = now
                    true to elapsed
                } else {
                    false to elapsed
                }
            }
            if (shouldSync) {
                Log.d(TAG, "🔄 Foreground sync triggered (last sync was ${elapsed?.let { "${it / 1000}s ago" } ?: "never"})")
                remoteLogger.log(
                    LogLevel.INFO,
                    TAG,
                    "Foreground sync triggered",
                    buildMap {
                        put("syncType", "foreground")
                        put("didPull", "true")
                        elapsed?.let { put("elapsedMs", it.toString()) }
                    }
                )
                enqueue(SyncJob.EnsureUserContext)
                enqueue(SyncJob.SyncProjects(force = false))
            } else {
                Log.d(TAG, "⏭️ Skipping foreground sync (${elapsed?.let { "${it / 1000}s" } ?: "?"} since last)")
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return true // Assume available if no manager provided
        return runCatching {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    fun processPendingOperations() {
        scope.launch {
            enqueue(SyncJob.ProcessPendingOperations)
        }
    }

    /**
     * Resets FAILED operations to PENDING for retry.
     * Called when network is restored or app comes to foreground.
     */
    suspend fun resetFailedOperations() {
        try {
            val resetCount = localDataService.resetFailedOperationsForRetry()
            if (resetCount > 0) {
                remoteLogger.log(
                    LogLevel.INFO,
                    TAG,
                    "Reset failed operations for retry",
                    mapOf("reset_count" to resetCount.toString())
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to reset failed operations", e)
        }
    }

    fun refreshProjectMetadata(projectId: Long) {
        scope.launch {
            enqueue(
                SyncJob.SyncProjectGraph(
                    projectId = projectId,
                    prio = 1,
                    skipPhotos = true,
                    mode = SyncJob.ProjectSyncMode.METADATA_ONLY
                )
            )
        }
    }

    fun prioritizeProject(projectId: Long) {
        scope.launch {
            focusProjectSync(projectId)
        }
    }

    /**
     * Triggers a photo sync for the currently viewed project.
     * Called when we receive a Pusher notification that photos were uploaded by another device.
     */
    fun refreshCurrentProjectPhotos() {
        scope.launch {
            val projectId = mutex.withLock { foregroundProjectId } ?: run {
                Log.d(TAG, "📷 No foreground project to refresh photos for")
                return@launch
            }

            Log.d(TAG, "📷 Refreshing photos for current project $projectId (triggered by Pusher)")

            // Queue a photo-only sync at high priority
            val shouldEnqueue = mutex.withLock {
                if (pendingPhotoSyncs.contains(projectId)) {
                    Log.d(TAG, "⏭️ Photo sync already pending for project $projectId, skipping duplicate")
                    false
                } else {
                    pendingPhotoSyncs.add(projectId)
                    updatePhotoSyncingProjectsLocked()
                    true
                }
            }

            if (shouldEnqueue) {
                enqueue(
                    SyncJob.SyncProjectGraph(
                        projectId = projectId,
                        prio = FOREGROUND_PHOTO_PRIORITY,
                        skipPhotos = false,
                        mode = SyncJob.ProjectSyncMode.PHOTOS_ONLY
                    )
                )
            }
        }
    }

    /**
     * Returns true if a sync job for the given project is currently running or queued.
     * Used by UI flows to avoid kicking duplicate syncs or mutating data while a sync is in flight.
     */
    suspend fun isProjectSyncInFlight(projectId: Long): Boolean = mutex.withLock {
        activeProjectSyncJobs.containsKey(projectId) ||
            taskIndex.containsKey("project_$projectId") ||
            pendingPhotoSyncs.contains(projectId)
    }

    /**
     * Cancel the ongoing project sync for a given project.
     * This cancels the active coroutine and removes the job from queue if pending.
     */
    fun cancelProjectSync(projectId: Long) {
        scope.launch {
            mutex.withLock {
                // Cancel active job if running
                activeProjectSyncJobs[projectId]?.let { job ->
                    Log.d(TAG, "🛑 Cancelling active sync for project $projectId")
                    job.cancel()
                    activeProjectSyncJobs.remove(projectId)
                    activeProjectModes.remove(projectId)
                }

                // Remove from queue if pending
                val key = "project_$projectId"
                taskIndex[key]?.let { task ->
                    Log.d(TAG, "🗑️ Removing queued sync for project $projectId")
                    queue.remove(task)
                    taskIndex.remove(key)
                }

                // Clear pending photo sync flag
                pendingPhotoSyncs.remove(projectId)
                updatePhotoSyncingProjectsLocked()
            }
        }
    }

    fun pauseProjectPhotoSync(projectId: Long) {
        scope.launch {
            mutex.withLock {
                val activeMode = activeProjectModes[projectId]
                if (activeMode?.includesPhotos() == true) {
                    activeProjectSyncJobs[projectId]?.let { job ->
                        Log.d(TAG, "🛑 Pausing active photo sync for project $projectId (state stays partial; resume will rerun content)")
                        job.cancel()
                        activeProjectSyncJobs.remove(projectId)
                        activeProjectModes.remove(projectId)
                    }
                }

                val key = "project_$projectId"
                taskIndex[key]?.let { task ->
                    val syncJob = task.job
                    if (syncJob is SyncJob.SyncProjectGraph && syncJob.mode.includesPhotos()) {
                        Log.d(TAG, "🗑️ Removing queued photo sync for project $projectId")
                        queue.remove(task)
                        taskIndex.remove(key)
                        pendingPhotoSyncs.remove(projectId)
                        updatePhotoSyncingProjectsLocked()
                    }
                }
            }
        }
    }

    fun resumeProjectPhotoSync(projectId: Long, priority: Int = FOREGROUND_PHOTO_PRIORITY) {
        scope.launch {
            val shouldEnqueue = mutex.withLock {
                if (pendingPhotoSyncs.contains(projectId)) {
                    false
                } else {
                    pendingPhotoSyncs.add(projectId)
                    updatePhotoSyncingProjectsLocked()
                    true
                }
            }

            if (shouldEnqueue) {
                Log.d(TAG, "📷 Resuming photo sync for project $projectId at priority $priority")
                enqueue(
                    SyncJob.SyncProjectGraph(
                        projectId = projectId,
                        prio = priority,
                        skipPhotos = false,
                        mode = SyncJob.ProjectSyncMode.CONTENT_ONLY
                    )
                )
            }
        }
    }

    fun clear() {
        scope.launch {
            mutex.withLock {
                // Cancel all active sync jobs to prevent them from continuing
                // with stale auth or wrong company context after logout/switch
                activeProjectSyncJobs.values.forEach { job ->
                    Log.d(TAG, "🛑 Cancelling active sync job during clear()")
                    job.cancel()
                }
                activeProjectSyncJobs.clear()
                activeProjectModes.clear()
                foregroundProjectId = null

                queue.clear()
                taskIndex.clear()
                pendingPhotoSyncs.clear()
                updatePhotoSyncingProjectsLocked()
                initialSyncStarted.set(false)
                _initialSyncCompleted.value = false
                assignedProjectIds.value = emptySet()
                _assignedProjectsLoaded.value = false
                pendingUpdatedProjectIds.clear()
                lastForegroundSyncAt = -1L
            }
            projectRealtimeManager?.clear()
            _isActive.value = false
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun enqueue(job: SyncJob) {
        mutex.withLock {
            val existing = taskIndex[job.key]
            if (existing != null) {
                if (existing.priority <= job.priority) {
                    return
                } else {
                    queue.remove(existing)
                }
            }
            val queued = QueuedTask(job.key, job, job.priority, System.currentTimeMillis())
            queue.add(queued)
            taskIndex[job.key] = queued
            updateProjectSyncingProjectsLocked()
            notifier.tryEmit(Unit)
        }
    }

    private suspend fun processLoop() {
        while (true) {
            val next = mutex.withLock {
                queue.poll()?.also {
                    taskIndex.remove(it.key)
                    updateProjectSyncingProjectsLocked()
                }
            }

            if (next == null) {
                _isActive.value = false
                _currentSyncJob.value = null
                try {
                    notifier.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Notifier error in processLoop", e)
                    delay(1000)  // Back off before retrying
                }
                continue
            }

            _isActive.value = true
            _currentSyncJob.value = next.job
            try {
                execute(next.job)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val message = "Sync job ${next.key} failed: ${t.message}"
                remoteLogger.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = message,
                    metadata = mapOf(
                        "jobKey" to next.key,
                        "priority" to next.priority.toString()
                    )
                )
                _errors.tryEmit(message)

                // Cleanup any stuck state for project sync jobs to prevent memory leaks
                // This is a defensive cleanup in case the finally block didn't run
                if (next.job is SyncJob.SyncProjectGraph) {
                    val projectId = (next.job as SyncJob.SyncProjectGraph).projectId
                    mutex.withLock {
                        activeProjectSyncJobs.remove(projectId)
                        activeProjectModes.remove(projectId)
                        pendingPhotoSyncs.remove(projectId)
                        updatePhotoSyncingProjectsLocked()
                        updateProjectSyncingProjectsLocked()
                    }
                    Log.d(TAG, "🧹 Cleaned up stuck state for failed project sync $projectId")
                }
            } finally {
                _currentSyncJob.value = null
                _currentSyncProgress.value = null
            }
        }
    }

    private suspend fun execute(job: SyncJob) {
        // Update progress with generic descriptions for non-project jobs
        when (job) {
            is SyncJob.EnsureUserContext -> _currentSyncProgress.value = SyncProgress(job, null, "Loading user data...")
            is SyncJob.SyncProjects -> _currentSyncProgress.value = SyncProgress(job, null, "Loading project list...")
            is SyncJob.SyncDeletedRecords -> _currentSyncProgress.value = SyncProgress(job, null, "Checking for deletions...")
            is SyncJob.SyncUpdatedRecords -> _currentSyncProgress.value = SyncProgress(job, null, "Checking for updates...")
            is SyncJob.ProcessPendingOperations -> _currentSyncProgress.value = null // Don't show for pending ops
            is SyncJob.SyncProjectGraph -> {} // Handled below with project lookup
        }

        when (job) {
            SyncJob.EnsureUserContext -> {
                authRepository.ensureUserContext()
                var userId = authRepository.getStoredUserId()
                if (userId == null && isNetworkAvailable()) {
                    authRepository.refreshUserContext().getOrElse { error -> throw error }
                    userId = authRepository.getStoredUserId()
                }
                // Subscribe to Pusher for photo upload notifications (only if online)
                if (isNetworkAvailable()) {
                    userId?.let { id ->
                        Log.d(TAG, "📷 Setting up Pusher subscription for user $id")
                        photoSyncRealtimeManager?.subscribeForUser(id.toInt())
                        val companies = authRepository.getUserCompanies().getOrElse { emptyList() }
                            .map { it.id }
                            .toSet()
                        projectRealtimeManager?.updateUserContext(id, companies)
                    }
                }
                Unit
            }
            SyncJob.ProcessPendingOperations -> {
                // Only ensure user context if online (ensureUserContext may call network)
                if (isNetworkAvailable()) {
                    authRepository.ensureUserContext()
                }
                val pendingResult = syncRepository.processPendingOperations()
                pendingResult.createdProjects.forEach { created ->
                    enqueue(
                        SyncJob.SyncProjectGraph(
                            projectId = created.localProjectId,
                            prio = 1,
                            skipPhotos = true,
                            mode = SyncJob.ProjectSyncMode.ESSENTIALS_ONLY
                        )
                    )
                }
            }

            is SyncJob.SyncProjects -> {
                // Skip network-dependent sync operations when offline
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "⏭️ Skipping SyncProjects (no network)")
                    remoteLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Sync skipped - offline",
                        mapOf("jobType" to "SyncProjects", "force" to job.force.toString())
                    )
                    return
                }

                val hasForeground = mutex.withLock { foregroundProjectId != null }
                if (hasForeground) {
                    Log.d(TAG, "⏸️ Foreground project sync running; deferring background project queue.")
                    if (job.force) {
                        pendingSyncProjectsForce.set(true)
                    }
                    return
                }
                if (job.force) {
                    authRepository.refreshUserContext().getOrElse { error -> throw error }
                    Unit
                } else {
                    authRepository.ensureUserContext()
                }

                var companyId = authRepository.getStoredCompanyId()
                if (companyId == null) {
                    Log.w(TAG, "⚠️ Missing companyId in storage, refreshing user context before sync")
                    authRepository.refreshUserContext().getOrElse { error -> throw error }
                    companyId = authRepository.getStoredCompanyId()
                }

                if (companyId == null) {
                    val message = "Missing company context during sync. Prompting relogin."
                    remoteLogger.log(LogLevel.ERROR, TAG, message)
                    throw IllegalStateException("Please log in again.")
                }

                // First, sync projects assigned to the current user (used for My Projects tab)
                val assignedIds = syncRepository.syncCompanyProjects(
                    companyId,
                    assignedToMe = true,
                    forceFullSync = job.force
                )
                // Only update assignedProjectIds on full sync.
                // Incremental syncs return only recently-updated projects, which would
                // cause us to lose track of older assigned projects if we replaced the set.
                if (job.force) {
                    assignedProjectIds.value = assignedIds
                } else {
                    // For incremental sync, merge new assigned IDs with existing ones
                    assignedProjectIds.value = assignedProjectIds.value + assignedIds
                }
                _assignedProjectsLoaded.value = true

                // Then sync all projects for the active company (used for WIP/all)
                syncRepository.syncCompanyProjects(
                    companyId,
                    assignedToMe = false,
                    forceFullSync = job.force
                )

                val projects = localDataService.getAllProjects()
                    .filter { it.companyId == companyId }
                if (projects.isEmpty()) {
                    remoteLogger.log(LogLevel.INFO, TAG, "Sync returned no projects.")
                }

                projectRealtimeManager?.updateProjects(projects.map { it.projectId }.toSet())

                // Check if we have specific updated project IDs from SyncUpdatedRecords
                val updatedServerIds = mutex.withLock {
                    pendingUpdatedProjectIds.toSet().also { pendingUpdatedProjectIds.clear() }
                }

                // If incremental sync identified specific changed projects, only sync those
                if (!job.force && updatedServerIds.isNotEmpty()) {
                    val changedProjects = projects.filter { project ->
                        project.serverId?.let { updatedServerIds.contains(it) } == true
                    }

                    Log.d(TAG, "📊 Incremental sync: ${changedProjects.size} changed projects (of ${updatedServerIds.size} updated IDs)")

                    if (changedProjects.isEmpty()) {
                        Log.d(TAG, "✅ No local projects match updated IDs, skipping individual syncs")
                    } else {
                        changedProjects.forEachIndexed { index, project ->
                            enqueue(
                                SyncJob.SyncProjectGraph(
                                    projectId = project.projectId,
                                    prio = 2 + index,
                                    skipPhotos = true,
                                    mode = SyncJob.ProjectSyncMode.ESSENTIALS_ONLY,
                                    skipContentSync = false // Changed projects get full sync
                                )
                            )
                        }

                        remoteLogger.log(
                            LogLevel.INFO,
                            TAG,
                            "Incremental sync queued changed projects",
                            mapOf(
                                "changedCount" to changedProjects.size.toString(),
                                "updatedIdsCount" to updatedServerIds.size.toString()
                            )
                        )
                    }

                    // Mark initial sync as completed
                    if (!_initialSyncCompleted.value) {
                        _initialSyncCompleted.value = true
                        Log.d(TAG, "✅ Initial project sync completed")
                    }
                    return
                }

                // Fall through to full sync if no updated IDs available (force=true or fallback)
                val now = System.currentTimeMillis()
                val recentCutoff = now - RECENT_SYNC_THRESHOLD_MS
                val eligible = projects.filter { project ->
                    val lastSynced = project.lastSyncedAt?.time
                    // Skip recently synced projects to avoid immediate re-queue
                    lastSynced == null || lastSynced < recentCutoff
                }

                // Sort by most recent (updatedAt descending) to prioritize recent projects
                val sortedByRecency = eligible.sortedByDescending { it.updatedAt?.time ?: 0L }

                // Full sync for: all assigned projects + top N unassigned projects
                val currentAssignedIds = assignedProjectIds.value
                val assignedForFullSync = sortedByRecency.filter { currentAssignedIds.contains(it.projectId) }.map { it.projectId }.toSet()
                val unassignedForFullSync = sortedByRecency.filter { !currentAssignedIds.contains(it.projectId) }.take(MAX_UNASSIGNED_FULL_SYNC).map { it.projectId }.toSet()
                val allForFullSync = assignedForFullSync + unassignedForFullSync

                Log.d(TAG, "📊 Queuing ${eligible.size} projects: ${assignedForFullSync.size} assigned + ${unassignedForFullSync.size} unassigned for full sync, ${eligible.size - allForFullSync.size} essentials-only")

                sortedByRecency.forEachIndexed { index, project ->
                    val skipContent = !allForFullSync.contains(project.projectId)
                    enqueue(
                        SyncJob.SyncProjectGraph(
                            projectId = project.projectId,
                            prio = 2 + index,
                            skipPhotos = true,
                            mode = SyncJob.ProjectSyncMode.ESSENTIALS_ONLY,
                            skipContentSync = skipContent
                        )
                    )
                }

                // Log batch summary instead of per-project
                if (sortedByRecency.isNotEmpty()) {
                    remoteLogger.log(
                        LogLevel.INFO,
                        TAG,
                        "Queued projects for background sync",
                        mapOf(
                            "totalQueued" to sortedByRecency.size.toString(),
                            "assignedFullSync" to assignedForFullSync.size.toString(),
                            "unassignedFullSync" to unassignedForFullSync.size.toString(),
                            "essentialsOnlyCount" to (sortedByRecency.size - allForFullSync.size).toString()
                        )
                    )
                }

                val skipped = projects - eligible.toSet()
                if (skipped.isNotEmpty()) {
                    Log.d(TAG, "Skipped ${skipped.size} projects due to recent sync")
                }

                // Mark initial sync as completed so UI can show empty state if needed
                if (!_initialSyncCompleted.value) {
                    _initialSyncCompleted.value = true
                    Log.d(TAG, "✅ Initial project sync completed")
                }
            }

            is SyncJob.SyncDeletedRecords -> {
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "⏭️ Skipping SyncDeletedRecords (no network)")
                    return
                }
                syncRepository.syncDeletedRecords()
                    .onFailure { error ->
                        Log.e(TAG, "❌ Failed to sync deleted records", error)
                    }
                // Prune expired tombstones after sync cycle to prevent memory growth
                DeletionTombstoneCache.pruneExpired()
            }

            is SyncJob.SyncUpdatedRecords -> {
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "⏭️ Skipping SyncUpdatedRecords (no network)")
                    return
                }
                syncRepository.getUpdatedProjectIds()
                    .onSuccess { changedIds ->
                        if (changedIds.isNotEmpty()) {
                            mutex.withLock {
                                pendingUpdatedProjectIds.addAll(changedIds)
                            }
                            Log.d(TAG, "📊 [SyncUpdatedRecords] Found ${changedIds.size} changed projects")
                        } else {
                            Log.d(TAG, "📊 [SyncUpdatedRecords] No project changes detected")
                        }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "❌ [SyncUpdatedRecords] Failed to fetch updated records, will use full sync", error)
                        // Set force flag so SyncProjects falls back to full sync
                        pendingSyncProjectsForce.set(true)
                    }
            }

            is SyncJob.SyncProjectGraph -> {
                // Skip network-dependent sync operations when offline
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "⏭️ Skipping SyncProjectGraph for project ${job.projectId} (no network)")
                    remoteLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Sync skipped - offline",
                        mapOf(
                            "jobType" to "SyncProjectGraph",
                            "projectId" to job.projectId.toString(),
                            "mode" to job.mode.name
                        )
                    )
                    return
                }

                val mode = job.mode

                // Look up project UID once for detailed progress display
                val project = localDataService.getProject(job.projectId)
                val projectUid = project?.uid

                // Check for deleted items in this project before syncing
                val projectServerId = project?.serverId
                if (projectServerId != null) {
                    syncRepository.syncDeletedRecordsForProject(projectServerId, job.projectId)
                        .onFailure { error ->
                            Log.w(TAG, "⚠️ [SyncProjectGraph] Failed to sync deleted records for project ${job.projectId}", error)
                        }
                }
                val phaseDescription = when (mode) {
                    SyncJob.ProjectSyncMode.FULL -> "full sync"
                    SyncJob.ProjectSyncMode.ESSENTIALS_ONLY -> "rooms"
                    SyncJob.ProjectSyncMode.CONTENT_ONLY -> "contents"
                    SyncJob.ProjectSyncMode.PHOTOS_ONLY -> "photos"
                    SyncJob.ProjectSyncMode.METADATA_ONLY -> "metadata"
                }
                val displayText = if (projectUid != null) {
                    "$projectUid - $phaseDescription"
                } else {
                    "Project ${job.projectId} - $phaseDescription"
                }
                _currentSyncProgress.value = SyncProgress(job, projectUid, displayText)
                var syncSucceeded = false
                val syncJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        when (mode) {
                            SyncJob.ProjectSyncMode.ESSENTIALS_ONLY -> {
                                val results = syncRepository.syncProjectSegments(
                                    job.projectId,
                                    listOf(SyncSegment.PROJECT_ESSENTIALS),
                                    source = "SyncQueueManager"
                                )
                                syncSucceeded = results.all { it.success }.also { success ->
                                    if (!success) {
                                        logSegmentFailures(job.projectId, results)
                                    }
                                }
                            }
                            SyncJob.ProjectSyncMode.CONTENT_ONLY -> {
                                val results = syncRepository.syncProjectContent(job.projectId, source = "SyncQueueManager")
                                syncSucceeded = results.all { it.success }.also { success ->
                                    if (!success) {
                                        logSegmentFailures(job.projectId, results)
                                    }
                                }
                                photoCacheScheduler.schedulePrefetch()
                            }
                            SyncJob.ProjectSyncMode.METADATA_ONLY -> {
                                val results = syncRepository.syncProjectSegments(
                                    job.projectId,
                                    listOf(SyncSegment.PROJECT_METADATA),
                                    source = "SyncQueueManager"
                                )
                                syncSucceeded = results.all { it.success }.also { success ->
                                    if (!success) {
                                        logSegmentFailures(job.projectId, results)
                                    }
                                }
                            }
                            SyncJob.ProjectSyncMode.PHOTOS_ONLY -> {
                                val results = syncRepository.syncProjectSegments(
                                    job.projectId,
                                    listOf(
                                        SyncSegment.ALL_ROOM_PHOTOS,
                                        SyncSegment.PROJECT_LEVEL_PHOTOS
                                    ),
                                    source = "SyncQueueManager"
                                )
                                syncSucceeded = results.all { it.success }.also { success ->
                                    if (!success) {
                                        logSegmentFailures(job.projectId, results)
                                    }
                                }
                                photoCacheScheduler.schedulePrefetch()
                            }
                            SyncJob.ProjectSyncMode.FULL -> {
                                val results = syncRepository.syncProjectGraph(job.projectId, skipPhotos = false, source = "SyncQueueManager")
                                syncSucceeded = results.all { it.success }.also { success ->
                                    if (!success) {
                                        logSegmentFailures(job.projectId, results)
                                    }
                                }
                                photoCacheScheduler.schedulePrefetch()
                            }
                        }
                    } finally {
                        mutex.withLock {
                            activeProjectSyncJobs.remove(job.projectId)
                            activeProjectModes.remove(job.projectId)
                            if (mode.includesPhotos()) {
                                pendingPhotoSyncs.remove(job.projectId)
                                updatePhotoSyncingProjectsLocked()
                            }
                            updateProjectSyncingProjectsLocked()
                            // Track essentials sync failures for UI (only for modes that sync essentials)
                            if (mode == SyncJob.ProjectSyncMode.ESSENTIALS_ONLY || mode == SyncJob.ProjectSyncMode.FULL) {
                                if (syncSucceeded) {
                                    _projectEssentialsFailed.value -= job.projectId
                                } else {
                                    _projectEssentialsFailed.value += job.projectId
                                }
                            }
                        }
                        notifier.tryEmit(Unit)
                    }
                }

                // Register the job BEFORE starting so clear() can always find it.
                // LAZY start ensures no execution happens until after registration.
                mutex.withLock {
                    activeProjectSyncJobs[job.projectId] = syncJob
                    activeProjectModes[job.projectId] = mode
                    updateProjectSyncingProjectsLocked()
                }
                syncJob.start()

                // Wait for completion
                syncJob.join()

                // If fast sync succeeded, queue photo sync (unless skipContentSync or already pending)
                if (syncSucceeded && mode == SyncJob.ProjectSyncMode.ESSENTIALS_ONLY) {
                    // Skip content sync for unassigned projects outside top N
                    if (job.skipContentSync) {
                        Log.d(TAG, "⏭️ Fast sync completed for project ${job.projectId}, skipping content sync (not assigned and not in top $MAX_UNASSIGNED_FULL_SYNC unassigned)")
                    } else {
                        val shouldEnqueuePhotos = mutex.withLock {
                            if (pendingPhotoSyncs.contains(job.projectId)) {
                                Log.d(TAG, "⏭️ Photo sync already pending for project ${job.projectId}, skipping duplicate")
                                false
                            } else {
                                // Drop any queued FAST job so the full photo run can start immediately.
                                taskIndex[job.key]?.let { existing ->
                                    Log.d(TAG, "♻️ Replacing queued FAST job with FULL sync for project ${job.projectId}")
                                    queue.remove(existing)
                                    taskIndex.remove(existing.key)
                                }
                                pendingPhotoSyncs.add(job.projectId)
                                updatePhotoSyncingProjectsLocked()
                                true
                            }
                        }
                        if (shouldEnqueuePhotos) {
                            val followUpPrio = job.prio + 1
                            Log.d(TAG, "⏭️ Fast sync completed for project ${job.projectId}, queueing photo sync at priority $followUpPrio")
                            enqueue(
                                SyncJob.SyncProjectGraph(
                                    projectId = job.projectId,
                                    prio = followUpPrio,
                                    skipPhotos = false,
                                    mode = SyncJob.ProjectSyncMode.CONTENT_ONLY
                                )
                            )
                        }
                    }
                }

                // Only clear foreground and resume background after FULL sync completes
                val shouldResumeBackground = mutex.withLock {
                    if (foregroundProjectId == job.projectId && mode != SyncJob.ProjectSyncMode.ESSENTIALS_ONLY) {
                        foregroundProjectId = null
                        true
                    } else {
                        false
                    }
                }

                if (shouldResumeBackground) {
                    Log.d(TAG, "✅ Foreground project ${job.projectId} completed (including photos), resuming background sync")
                    val forcePending = pendingSyncProjectsForce.getAndSet(false)
                    enqueue(SyncJob.SyncProjects(force = forcePending))
                }
            }
        }
    }

    private suspend fun observePendingOperations() {
        localDataService.observeSyncOperations(SyncStatus.PENDING)
            .debounce(750)
            .collect { ops ->
                if (ops.isNotEmpty()) {
                    enqueue(SyncJob.ProcessPendingOperations)
                }
            }
    }

    private fun SyncJob.ProjectSyncMode.includesPhotos(): Boolean =
        this == SyncJob.ProjectSyncMode.CONTENT_ONLY ||
            this == SyncJob.ProjectSyncMode.PHOTOS_ONLY ||
            this == SyncJob.ProjectSyncMode.FULL

    private fun logSegmentFailures(projectId: Long, results: List<com.example.rocketplan_android.data.repository.SyncResult>) {
        results.filterNot { it.success }.forEach { result ->
            when (result) {
                is com.example.rocketplan_android.data.repository.SyncResult.Failure -> {
                    Log.w(
                        TAG,
                        "⚠️ Segment ${result.segment} failed for project $projectId (items=${result.itemsSynced}, duration=${result.durationMs}ms)",
                        result.error
                    )
                }
                is com.example.rocketplan_android.data.repository.SyncResult.Incomplete -> {
                    Log.w(
                        TAG,
                        "⚠️ Segment ${result.segment} incomplete (${result.reason}) for project $projectId (items=${result.itemsSynced}, duration=${result.durationMs}ms)",
                        result.error
                    )
                }
                else -> {
                    // no-op
                }
            }
        }
    }

    private data class QueuedTask(
        val key: String,
        val job: SyncJob,
        val priority: Int,
        val enqueuedAt: Long
    )

    companion object {
        private const val TAG = "SyncQueueManager"
        private const val FOREGROUND_PRIORITY = -1
        private const val FOREGROUND_PHOTO_PRIORITY = 0
        // Avoid re-queuing projects that have synced very recently when building background queues
        private const val RECENT_SYNC_THRESHOLD_MS = 5 * 60 * 1000L
        // Interval to check for backoff-scheduled operations that are now due
        private const val SCHEDULED_RETRY_CHECK_INTERVAL_MS = 30_000L
        // Full sync for all assigned projects + this many unassigned projects (by recency)
        // Other projects get essentials-only (fast sync) for navigation
        private const val MAX_UNASSIGNED_FULL_SYNC = 5
    }

    private suspend fun focusProjectSync(projectId: Long) {
        val project = localDataService.getProject(projectId)
        if (project?.serverId == null) {
            Log.d(TAG, "⏭️ Skipping foreground sync for unsynced project $projectId (no serverId yet)")
            return
        }

        // Check if project has updates since last sync
        // But always sync if essentials (locations) haven't been fetched yet
        val lastSyncedAt = project.lastSyncedAt
        val hasLocations = localDataService.getLocations(projectId).isNotEmpty()
        if (lastSyncedAt != null && hasLocations && isNetworkAvailable()) {
            val sinceIso = DateUtils.formatApiDate(lastSyncedAt)
            val hasUpdates = syncRepository.hasProjectUpdates(project.serverId, sinceIso)
            if (!hasUpdates) {
                Log.d(TAG, "⏭️ No updates for project $projectId since $sinceIso, skipping sync")
                return
            }
        } else if (!hasLocations) {
            Log.d(TAG, "🔄 Project $projectId has no locations, forcing essentials sync")
        }

        try {
            val result = syncRepository.syncDeletedRecords()
            result.exceptionOrNull()?.let { error ->
                Log.w(TAG, "⚠️ Failed to sync deleted records before focusing project $projectId", error)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ Failed to sync deleted records before focusing project $projectId", t)
        }
        val shouldEnqueueFast = mutex.withLock {
            if (pendingPhotoSyncs.contains(projectId)) {
                Log.d(TAG, "📷 Photo sync already queued for project $projectId; skipping extra FAST request")
                return@withLock false
            }
            if (foregroundProjectId != projectId) {
                foregroundProjectId = projectId
            }
            // Cancel jobs for other projects - the finally block in execute() will handle cleanup
            // We don't remove from activeProjectSyncJobs here because cancellation is cooperative
            // and the job may still be running; the finally block will clean up when it actually stops
            activeProjectSyncJobs.forEach { (id, job) ->
                if (id != projectId) {
                    Log.d(TAG, "🛑 Cancelling active sync for project $id (focusing on $projectId)")
                    job.cancel()
                }
            }
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                val syncJob = task.job
                if (syncJob is SyncJob.SyncProjectGraph && syncJob.projectId != projectId) {
                    iterator.remove()
                    taskIndex.remove(task.key)
                }
            }
            updateProjectSyncingProjectsLocked()
            true
        }
        if (!shouldEnqueueFast) {
            return
        }
        Log.d(TAG, "🚀 Foreground sync for project $projectId (FAST mode - rooms only)")
        // Foreground projects always get full sync (skipContentSync=false)
        enqueue(SyncJob.SyncProjectGraph(projectId = projectId, prio = FOREGROUND_PRIORITY, skipPhotos = true, skipContentSync = false))
    }

    private fun updatePhotoSyncingProjectsLocked() {
        _photoSyncingProjects.value = pendingPhotoSyncs.toSet()
        updateProjectSyncingProjectsLocked()
    }

    private fun updateProjectSyncingProjectsLocked() {
        val activeProjects = activeProjectModes
            .filterValues {
                it != SyncJob.ProjectSyncMode.PHOTOS_ONLY &&
                    it != SyncJob.ProjectSyncMode.CONTENT_ONLY
            }
            .keys
        val queuedProjects = taskIndex.values.mapNotNull { task ->
            val job = task.job as? SyncJob.SyncProjectGraph ?: return@mapNotNull null
            job.projectId.takeIf {
                job.mode != SyncJob.ProjectSyncMode.PHOTOS_ONLY &&
                    job.mode != SyncJob.ProjectSyncMode.CONTENT_ONLY
            }
        }
        // Note: pendingPhotoSyncs intentionally excluded - photo uploads shouldn't block room creation
        val newSet = (activeProjects + queuedProjects).toSet()
        val oldSet = _projectSyncingProjects.value
        if (newSet != oldSet) {
            Log.d(TAG, "🔄 projectSyncingProjects changed: $oldSet → $newSet")
        }
        _projectSyncingProjects.value = newSet
    }
}
