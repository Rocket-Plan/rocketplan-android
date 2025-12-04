package com.example.rocketplan_android.realtime

import com.example.rocketplan_android.data.sync.SyncQueueManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import java.util.concurrent.ConcurrentHashMap

class NotesRealtimeManager(
    private val pusherService: PusherService,
    private val syncQueueManager: SyncQueueManager,
    private val remoteLogger: RemoteLogger?
) {

    private val projectNoteIds = mutableMapOf<Long, MutableSet<Long>>()
    private val projectChannels = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Keep Pusher subscriptions aligned with the currently visible notes for a project.
     */
    @Synchronized
    fun updateProjectSubscriptions(projectId: Long, noteIds: Set<Long>) {
        subscribeProjectChannel(projectId)

        val tracked = projectNoteIds.getOrPut(projectId) { mutableSetOf() }
        val toAdd = noteIds - tracked
        val toRemove = tracked - noteIds

        toAdd.forEach { subscribeNoteChannels(projectId, it) }
        toRemove.forEach { unsubscribeNoteChannels(it) }

        tracked.clear()
        tracked.addAll(noteIds)
    }

    @Synchronized
    fun clearProject(projectId: Long) {
        if (projectChannels.remove(projectId)) {
            val channel = PusherConfig.noteCreatedChannel(projectId)
            pusherService.unsubscribe(channel)
        }
        projectNoteIds.remove(projectId)?.forEach { noteId ->
            unsubscribeNoteChannels(noteId)
        }
    }

    private fun subscribeProjectChannel(projectId: Long) {
        if (!projectChannels.add(projectId)) return
        pusherService.bindGenericEvent(
            channelName = PusherConfig.noteCreatedChannel(projectId),
            eventName = PusherConfig.NOTE_CREATED_EVENT
        ) {
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Note created event received",
                metadata = mapOf("project_id" to projectId.toString())
            )
            syncQueueManager.refreshProjectMetadata(projectId)
        }
    }

    private fun subscribeNoteChannels(projectId: Long, noteId: Long) {
        val handlers = listOf(
            PusherConfig.noteUpdatedChannel(noteId) to PusherConfig.NOTE_UPDATED_EVENT,
            PusherConfig.noteDeletedChannel(noteId) to PusherConfig.NOTE_DELETED_EVENT,
            PusherConfig.noteFlaggedChannel(noteId) to PusherConfig.NOTE_FLAGGED_EVENT,
            PusherConfig.noteBookmarkedChannel(noteId) to PusherConfig.NOTE_BOOKMARKED_EVENT
        )

        handlers.forEach { (channel, event) ->
            pusherService.bindGenericEvent(channelName = channel, eventName = event) {
                remoteLogger?.log(
                    level = LogLevel.DEBUG,
                    tag = TAG,
                    message = "Note event received",
                    metadata = mapOf(
                        "project_id" to projectId.toString(),
                        "note_id" to noteId.toString(),
                        "event" to event
                    )
                )
                syncQueueManager.refreshProjectMetadata(projectId)
            }
        }
    }

    private fun unsubscribeNoteChannels(noteId: Long) {
        listOf(
            PusherConfig.noteUpdatedChannel(noteId),
            PusherConfig.noteDeletedChannel(noteId),
            PusherConfig.noteFlaggedChannel(noteId),
            PusherConfig.noteBookmarkedChannel(noteId)
        ).forEach { channel ->
            pusherService.unsubscribe(channel)
        }
    }

    companion object {
        private const val TAG = "NotesRealtimeManager"
    }
}
