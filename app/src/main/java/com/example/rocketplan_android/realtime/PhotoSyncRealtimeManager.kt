package com.example.rocketplan_android.realtime

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Manages real-time photo sync notifications via Pusher.
 * Subscribes to PhotoUploadingCompletedAnnouncement events to notify when
 * photos are uploaded by other users/devices.
 *
 * This mirrors the iOS implementation in ProjectViewModel.registerUserToUploadedPhotos()
 */
class PhotoSyncRealtimeManager(
    private val pusherService: PusherService
) {

    private val _photoUploadCompleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val photoUploadCompleted: SharedFlow<Unit> = _photoUploadCompleted.asSharedFlow()

    private var subscribedUserId: Int? = null

    /**
     * Subscribes to photo upload completed events for the given user.
     * When another device/user uploads photos, this will emit an event.
     */
    fun subscribeForUser(userId: Int) {
        if (subscribedUserId == userId) {
            Log.d(TAG, "Already subscribed to photo sync for user $userId")
            return
        }

        // Unsubscribe from previous user if any
        unsubscribe()

        val channelName = PusherConfig.channelNameForPhotoUploadCompleted(userId)
        Log.d(TAG, "📷 Subscribing to photo sync notifications: $channelName")

        pusherService.bindGenericEvent(
            channelName = channelName,
            eventName = PusherConfig.PHOTO_UPLOAD_COMPLETED_EVENT
        ) {
            Log.d(TAG, "📷 Photo upload completed event received - triggering sync")
            _photoUploadCompleted.tryEmit(Unit)
        }

        subscribedUserId = userId
    }

    /**
     * Unsubscribes from photo upload notifications.
     */
    fun unsubscribe() {
        subscribedUserId?.let { userId ->
            val channelName = PusherConfig.channelNameForPhotoUploadCompleted(userId)
            Log.d(TAG, "📷 Unsubscribing from photo sync notifications: $channelName")
            pusherService.unsubscribe(channelName)
        }
        subscribedUserId = null
    }

    companion object {
        private const val TAG = "PhotoSyncRealtimeManager"
    }
}
