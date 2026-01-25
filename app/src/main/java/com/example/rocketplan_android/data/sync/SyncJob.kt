package com.example.rocketplan_android.data.sync

sealed class SyncJob(
    val priority: Int,
    val key: String
) {
    data object EnsureUserContext : SyncJob(priority = 0, key = "ensure_user")
    data object ProcessPendingOperations : SyncJob(priority = 0, key = "pending_ops")

    data class SyncProjects(val force: Boolean = false) :
        SyncJob(priority = if (force) 0 else 1, key = "sync_projects")

    data object SyncDeletedRecords : SyncJob(priority = 0, key = "sync_deleted_records")

    enum class ProjectSyncMode {
        FULL,
        ESSENTIALS_ONLY,
        CONTENT_ONLY,
        PHOTOS_ONLY,
        METADATA_ONLY
    }

    data class SyncProjectGraph(
        val projectId: Long,
        val prio: Int = 3,
        val skipPhotos: Boolean = false,
        val mode: ProjectSyncMode = if (skipPhotos) ProjectSyncMode.ESSENTIALS_ONLY else ProjectSyncMode.FULL
    ) : SyncJob(priority = prio, key = "project_$projectId")
}
