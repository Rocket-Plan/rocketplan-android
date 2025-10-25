package com.example.rocketplan_android.data.sync

sealed class SyncJob(
    val priority: Int,
    val key: String
) {
    data object EnsureUserContext : SyncJob(priority = 0, key = "ensure_user")

    data class SyncProjects(val force: Boolean = false) :
        SyncJob(priority = if (force) 0 else 1, key = "sync_projects")

    data class SyncProjectGraph(val projectId: Long, val prio: Int = 3) :
        SyncJob(priority = prio, key = "project_$projectId")
}
