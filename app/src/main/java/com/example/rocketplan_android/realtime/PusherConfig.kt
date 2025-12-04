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
    const val PHOTO_ASSEMBLY_RESULT_EVENT = "App\\Events\\PhotoAssemblyUpdated"
    const val PHOTO_UPLOAD_COMPLETED_EVENT = "App\\Events\\Websockets\\PhotoUploadingCompletedAnnouncement"
    const val PROJECT_CREATED_EVENT = "App\\Events\\BroadcastProjectCreatedEvent"
    const val PROJECT_COMPLETED_EVENT = "App\\Events\\BroadcastProjectCompletedEvent"
    const val PROJECT_DELETED_EVENT = "App\\Events\\BroadcastProjectDeletedEvent"
    const val USER_ROLE_CHANGED_EVENT = "App\\Events\\BroadcastUserRoleChangedEvent"
    const val NOTE_CREATED_EVENT = "App\\Events\\BroadcastNoteCreatedEvent"
    const val NOTE_UPDATED_EVENT = "App\\Events\\BroadcastNoteUpdatedEvent"
    const val NOTE_DELETED_EVENT = "App\\Events\\BroadcastNoteDeletedEvent"
    const val NOTE_FLAGGED_EVENT = "App\\Events\\BroadcastNoteFlaggedEvent"
    const val NOTE_BOOKMARKED_EVENT = "App\\Events\\BroadcastNoteBookmarkedEvent"

    fun channelNameForAssembly(assemblyId: String): String =
        "imageprocessornotification.AssemblyId.$assemblyId"

    fun legacyAssemblyChannel(assemblyId: String): String =
        "BroadcastPhotoAssemblyResultEvent.AssemblyId.$assemblyId"

    fun channelNameForPhotoUploadCompleted(userId: Int): String =
        "PhotoUploadingCompletedAnnouncement.User.$userId"

    fun projectCreatedChannel(userId: Long): String =
        "BroadcastProjectCreatedEvent.User.$userId"

    fun projectCompletedChannelForUser(userId: Long): String =
        "BroadcastProjectCompletedEvent.User.$userId"

    fun projectDeletedChannel(userId: Long): String =
        "BroadcastProjectDeletedEvent.User.$userId"

    fun projectCompletedChannelForCompany(companyId: Long): String =
        "BroadcastProjectCompletedEvent.Company.$companyId"

    fun projectCompletedChannelForProject(projectId: Long): String =
        "BroadcastProjectCompletedEvent.Project.$projectId"

    fun userRoleChangedChannel(userId: Long): String =
        "BroadcastUserRoleChangedEvent.User.$userId"

    fun noteCreatedChannel(projectId: Long): String =
        "BroadcastNoteCreatedEvent.Project.$projectId"

    fun noteUpdatedChannel(noteId: Long): String =
        "BroadcastNoteUpdatedEvent.Note.$noteId"

    fun noteDeletedChannel(noteId: Long): String =
        "BroadcastNoteDeletedEvent.Note.$noteId"

    fun noteFlaggedChannel(noteId: Long): String =
        "BroadcastNoteFlaggedEvent.Note.$noteId"

    fun noteBookmarkedChannel(noteId: Long): String =
        "BroadcastNoteBookmarkedEvent.Note.$noteId"

    fun appKey(): String = if (AppConfig.isProduction) PROD_KEY else NON_PROD_KEY
}
