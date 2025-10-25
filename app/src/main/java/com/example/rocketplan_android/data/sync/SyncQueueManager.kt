package com.example.rocketplan_android.data.sync

import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.work.PhotoCacheScheduler
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    init {
        scope.launch { processLoop() }
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
            enqueue(SyncJob.SyncProjectGraph(projectId = projectId, prio = 0))
        }
    }

    fun clear() {
        scope.launch {
            mutex.withLock {
                queue.clear()
                taskIndex.clear()
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
                val userId = authRepository.getStoredUserId()
                if (userId == null) {
                    authRepository.refreshUserContext().getOrElse { error -> throw error }
                }
                Unit
            }

            is SyncJob.SyncProjects -> {
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

                userId?.let { syncRepository.syncUserProjects(it) }
                companyId?.let { syncRepository.syncCompanyProjects(it) }

                val projects = localDataService.getAllProjects()
                if (projects.isEmpty()) {
                    remoteLogger.log(LogLevel.INFO, TAG, "Sync returned no projects.")
                }

                projects.forEachIndexed { index, project ->
                    enqueue(SyncJob.SyncProjectGraph(project.projectId, prio = 2 + index))
                }
            }

            is SyncJob.SyncProjectGraph -> {
                syncRepository.syncProjectGraph(job.projectId)
                photoCacheScheduler.schedulePrefetch()
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
    }
}
