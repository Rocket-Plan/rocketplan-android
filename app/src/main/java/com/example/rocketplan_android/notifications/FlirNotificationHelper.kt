package com.example.rocketplan_android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.Gravity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Centralized helper to ensure ACE-required metadata is always added before showing notifications.
 */
object FlirNotificationHelper {
    const val CHANNEL_GENERAL = "rocketplan-general"
    private const val CHANNEL_GENERAL_NAME = "General"

    /**
     * Creates a notification channel if needed. No-op on pre-O devices.
     */
    fun ensureChannel(
        context: Context,
        channelId: String = CHANNEL_GENERAL,
        channelName: String = CHANNEL_GENERAL_NAME
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "RocketPlan notifications (includes ACE metadata)"
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Post a notification with the required FLIR ACE metadata attached.
     *
     * The builder should already include a small icon and any app-specific content.
     */
    fun notify(
        context: Context,
        notificationId: Int,
        builder: NotificationCompat.Builder,
        channelId: String = CHANNEL_GENERAL,
        channelName: String = CHANNEL_GENERAL_NAME,
        buttonText: String = "Close",
        secondaryButtonText: String = "Redirect",
        redirectPackageName: String = context.packageName,
        gravity: Int = Gravity.TOP,
        closeOption: Boolean = true
    ) {
        ensureChannel(context, channelId, channelName)

        val finalBuilder = builder
            .setChannelId(channelId)
            .addFlirAceMetadata(
                buttonText = buttonText,
                secondaryButtonText = secondaryButtonText,
                redirectPackageName = redirectPackageName,
                gravity = gravity,
                closeOption = closeOption
            )

        NotificationManagerCompat.from(context).notify(notificationId, finalBuilder.build())
    }
}
