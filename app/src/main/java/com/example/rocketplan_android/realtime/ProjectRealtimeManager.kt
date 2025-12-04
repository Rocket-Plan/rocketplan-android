package com.example.rocketplan_android.realtime

import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ProjectRealtimeManager(
    private val pusherService: PusherService,
    private val syncQueueManager: SyncQueueManager,
    private val authRepository: AuthRepository,
    private val remoteLogger: RemoteLogger?
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subscribedUserChannels = mutableSetOf<String>()
    private val subscribedCompanyChannels = mutableSetOf<String>()
    private val subscribedProjectChannels = ConcurrentHashMap.newKeySet<String>()
    private var currentUserId: Long? = null
    private var currentCompanies: Set<Long> = emptySet()

    /**
     * Subscribe to user/company level project events and role changes.
     */
    @Synchronized
    fun updateUserContext(userId: Long, companyIds: Set<Long>) {
        if (currentUserId != null && currentUserId != userId) {
            unsubscribeUserChannels()
        }
        val companiesChanged = currentCompanies != companyIds

        currentUserId = userId
        currentCompanies = companyIds

        subscribeUserChannels(userId)
        if (companiesChanged) {
            unsubscribeCompanyChannels()
            companyIds.forEach { subscribeCompanyChannels(it) }
        }
    }

    /**
     * Subscribe to project-specific completion events so detail views can stay fresh.
     */
    fun updateProjects(projectIds: Set<Long>) {
        projectIds.forEach { projectId ->
            val channel = PusherConfig.projectCompletedChannelForProject(projectId)
            if (subscribedProjectChannels.add(channel)) {
                pusherService.bindGenericEvent(channel, PusherConfig.PROJECT_COMPLETED_EVENT) {
                    remoteLogger?.log(
                        level = LogLevel.INFO,
                        tag = TAG,
                        message = "Project completion event received",
                        metadata = mapOf("project_id" to projectId.toString())
                    )
                    syncQueueManager.refreshProjectMetadata(projectId)
                }
            }
        }
    }

    fun clear() {
        unsubscribeUserChannels()
        unsubscribeCompanyChannels()
        subscribedProjectChannels.forEach { pusherService.unsubscribe(it) }
        subscribedProjectChannels.clear()
        currentUserId = null
        currentCompanies = emptySet()
    }

    private fun subscribeUserChannels(userId: Long) {
        subscribe(
            channel = PusherConfig.projectCreatedChannel(userId),
            eventName = PusherConfig.PROJECT_CREATED_EVENT,
            bucket = subscribedUserChannels
        ) { syncQueueManager.refreshProjects() }

        subscribe(
            channel = PusherConfig.projectCompletedChannelForUser(userId),
            eventName = PusherConfig.PROJECT_COMPLETED_EVENT,
            bucket = subscribedUserChannels
        ) { syncQueueManager.refreshProjects() }

        subscribe(
            channel = PusherConfig.projectDeletedChannel(userId),
            eventName = PusherConfig.PROJECT_DELETED_EVENT,
            bucket = subscribedUserChannels
        ) { syncQueueManager.refreshProjects() }

        subscribe(
            channel = PusherConfig.userRoleChangedChannel(userId),
            eventName = PusherConfig.USER_ROLE_CHANGED_EVENT,
            bucket = subscribedUserChannels
        ) {
            scope.launch {
                remoteLogger?.log(
                    level = LogLevel.INFO,
                    tag = TAG,
                    message = "User role changed via Pusher",
                    metadata = mapOf("user_id" to userId.toString())
                )
                authRepository.refreshUserContext()
                syncQueueManager.refreshProjects()
            }
        }
    }

    private fun subscribeCompanyChannels(companyId: Long) {
        subscribe(
            channel = PusherConfig.projectCompletedChannelForCompany(companyId),
            eventName = PusherConfig.PROJECT_COMPLETED_EVENT,
            bucket = subscribedCompanyChannels
        ) { syncQueueManager.refreshProjects() }
    }

    private fun subscribe(
        channel: String,
        eventName: String,
        bucket: MutableSet<String>,
        onEvent: () -> Unit
    ) {
        val key = "$channel|$eventName"
        if (!bucket.add(key)) return
        pusherService.bindGenericEvent(channel, eventName, onEvent)
    }

    private fun unsubscribeUserChannels() {
        subscribedUserChannels.forEach { entry ->
            val channel = entry.substringBefore('|')
            pusherService.unsubscribe(channel)
        }
        subscribedUserChannels.clear()
    }

    private fun unsubscribeCompanyChannels() {
        subscribedCompanyChannels.forEach { entry ->
            val channel = entry.substringBefore('|')
            pusherService.unsubscribe(channel)
        }
        subscribedCompanyChannels.clear()
    }

    companion object {
        private const val TAG = "ProjectRealtimeManager"
    }
}
