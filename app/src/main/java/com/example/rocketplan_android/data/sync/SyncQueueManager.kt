package com.example.rocketplan_android.data.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class SyncQueueManager(
    private val authRepository: AuthRepository,
    private val syncRepository: OfflineSyncRepository,
    private val localDataService: LocalDataService,
    private val photoCacheScheduler: PhotoCacheScheduler,
    private val remoteLogger: RemoteLogger
) {

    private var photoSyncRealtimeManager: PhotoSyncRealtimeManager? = null

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
    private val initialSyncStarted = AtomicBoolean(false)

    // Track active project sync jobs for cancellation
    private val activeProjectSyncJobs = mutableMapOf<Long, Job>()
    @Volatile
    private var foregroundProjectId: Long? = null
    // Track projects with pending photo sync jobs to avoid duplicates
    private val pendingPhotoSyncs = mutableSetOf<Long>()
    private val _photoSyncingProjects = MutableStateFlow<Set<Long>>(emptySet())
    val photoSyncingProjects: StateFlow<Set<Long>> = _photoSyncingProjects

    init {
        scope.launch { processLoop() }
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

    suspend fun ensureInitialSync() {
        if (initialSyncStarted.compareAndSet(false, true)) {
            enqueue(SyncJob.EnsureUserContext)
            enqueue(SyncJob.SyncProjects(force = false))
        }
    }

    fun refreshProjects() {
        scope.launch {
            enqueue(SyncJob.EnsureUserContext)
            enqueue(SyncJob.SyncProjects(force = true))
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

            // Queue a full sync (with photos) at high priority
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
                        skipPhotos = false
                    )
                )
            }
        }
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

    fun clear() {
        scope.launch {
            mutex.withLock {
                queue.clear()
                taskIndex.clear()
                pendingPhotoSyncs.clear()
                updatePhotoSyncingProjectsLocked()
                initialSyncStarted.set(false)
            }
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
        }
        notifier.tryEmit(Unit)
    }

    private suspend fun processLoop() {
        while (true) {
            val next = mutex.withLock {
                queue.poll()?.also { taskIndex.remove(it.key) }
            }

            if (next == null) {
                _isActive.value = false
                notifier.first()
                continue
            }

            _isActive.value = true
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
            }
        }
    }

    private suspend fun execute(job: SyncJob) {
        when (job) {
            SyncJob.EnsureUserContext -> {
                authRepository.ensureUserContext()
                var userId = authRepository.getStoredUserId()
                if (userId == null) {
                    authRepository.refreshUserContext().getOrElse { error -> throw error }
                    userId = authRepository.getStoredUserId()
                }
                // Subscribe to Pusher for photo upload notifications
                userId?.let { id ->
                    Log.d(TAG, "📷 Setting up Pusher subscription for user $id")
                    photoSyncRealtimeManager?.subscribeForUser(id.toInt())
                }
                Unit
            }

            is SyncJob.SyncProjects -> {
                val hasForeground = mutex.withLock { foregroundProjectId != null }
                if (hasForeground) {
                    Log.d(TAG, "⏸️ Foreground project sync running; deferring background project queue.")
                    return
                }
                if (job.force) {
                    authRepository.refreshUserContext().getOrElse { error -> throw error }
                    Unit
                } else {
                    authRepository.ensureUserContext()
                }

                val userId = authRepository.getStoredUserId()
                val companyId = authRepository.getStoredCompanyId()

                if (userId == null && companyId == null) {
                    val message = "Missing user/company context during sync. Prompting relogin."
                    remoteLogger.log(LogLevel.ERROR, TAG, message)
                    throw IllegalStateException("Please log in again.")
                }

                // Only sync user-assigned projects (matching iOS default behavior)
                // iOS uses getUserProjects() by default, not getCompanyProjects()
                userId?.let { syncRepository.syncUserProjects(it) }

                val projects = localDataService.getAllProjects()
                if (projects.isEmpty()) {
                    remoteLogger.log(LogLevel.INFO, TAG, "Sync returned no projects.")
                }

                projects.forEachIndexed { index, project ->
                    enqueue(SyncJob.SyncProjectGraph(project.projectId, prio = 2 + index))
                }
            }

            is SyncJob.SyncProjectGraph -> {
                var syncSucceeded = false
                val syncJob = scope.launch {
                    try {
                        syncRepository.syncProjectGraph(job.projectId, skipPhotos = job.skipPhotos)
                        syncSucceeded = true
                        photoCacheScheduler.schedulePrefetch()
                    } finally {
                        mutex.withLock {
                            activeProjectSyncJobs.remove(job.projectId)
                            // Remove from pendingPhotoSyncs when full sync completes
                            if (!job.skipPhotos) {
                                pendingPhotoSyncs.remove(job.projectId)
                                updatePhotoSyncingProjectsLocked()
                            }
                        }
                        notifier.tryEmit(Unit)
                    }
                }

                mutex.withLock {
                    activeProjectSyncJobs[job.projectId] = syncJob
                }

                // Wait for completion
                syncJob.join()

                // If fast sync succeeded, queue photo sync (unless already pending)
                if (syncSucceeded && job.skipPhotos) {
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
                        Log.d(TAG, "⏭️ Fast sync completed for project ${job.projectId}, queueing photo sync at priority $FOREGROUND_PHOTO_PRIORITY")
                        enqueue(
                            SyncJob.SyncProjectGraph(
                                projectId = job.projectId,
                                prio = FOREGROUND_PHOTO_PRIORITY,
                                skipPhotos = false
                            )
                        )
                    }
                }

                // Only clear foreground and resume background after FULL sync completes
                val shouldResumeBackground = mutex.withLock {
                    if (foregroundProjectId == job.projectId && !job.skipPhotos) {
                        foregroundProjectId = null
                        true
                    } else {
                        false
                    }
                }

                if (shouldResumeBackground) {
                    Log.d(TAG, "✅ Foreground project ${job.projectId} completed (including photos), resuming background sync")
                    enqueue(SyncJob.SyncProjects(force = false))
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
    }

    private suspend fun focusProjectSync(projectId: Long) {
        try {
            syncRepository.syncDeletedRecords()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ Failed to sync deleted records before focusing project $projectId", t)
        }
        val jobsToCancel = mutableListOf<Job>()
        val shouldEnqueueFast = mutex.withLock {
            if (pendingPhotoSyncs.contains(projectId)) {
                Log.d(TAG, "📷 Photo sync already queued for project $projectId; skipping extra FAST request")
                return@withLock false
            }
            if (foregroundProjectId != projectId) {
                foregroundProjectId = projectId
            }
            activeProjectSyncJobs.forEach { (id, job) ->
                if (id != projectId) {
                    jobsToCancel += job
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
            true
        }
        if (!shouldEnqueueFast) {
            return
        }
        jobsToCancel.forEach { it.cancel() }
        Log.d(TAG, "🚀 Foreground sync for project $projectId (FAST mode - rooms only)")
        enqueue(SyncJob.SyncProjectGraph(projectId = projectId, prio = FOREGROUND_PRIORITY, skipPhotos = true))
    }

    private fun updatePhotoSyncingProjectsLocked() {
        _photoSyncingProjects.value = pendingPhotoSyncs.toSet()
    }
}
