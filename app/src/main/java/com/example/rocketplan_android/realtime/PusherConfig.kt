package com.example.rocketplan_android.realtime

import com.example.rocketplan_android.config.AppConfig

/**
 * Centralizes Pusher configuration so both dev/staging and prod build variants stay in sync
 * with the iOS implementation.
 */
object PusherConfig {
    private const val PROD_KEY = "85e07c3ecff502d261e3"
    private const val NON_PROD_KEY = "f17c0735529857c8b4e9"
    const val CLUSTER = "us2"
    const val PHOTO_EVENT = "App\\Events\\ImageProcessorPhotoUpdated"
    const val ASSEMBLY_EVENT = "App\\Events\\ImageProcessorUpdated"
    const val PHOTO_UPLOAD_COMPLETED_EVENT = "App\\Events\\Websockets\\PhotoUploadingCompletedAnnouncement"

    fun channelNameForAssembly(assemblyId: String): String =
        "imageprocessornotification.AssemblyId.$assemblyId"

    fun channelNameForPhotoUploadCompleted(userId: Int): String =
        "PhotoUploadingCompletedAnnouncement.User.$userId"

    fun appKey(): String = if (AppConfig.isProduction) PROD_KEY else NON_PROD_KEY
}
