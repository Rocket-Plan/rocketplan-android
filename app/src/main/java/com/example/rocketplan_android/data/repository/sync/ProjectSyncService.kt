package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.repository.mapper.latestTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.updatedSinceParam
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

// Checkpoint keys for incremental sync
private fun companyProjectsKey(companyId: Long, assignedOnly: Boolean) =
    if (assignedOnly) "company_projects_${companyId}_assigned" else "company_projects_$companyId"
private fun userProjectsKey(userId: Long) = "user_projects_$userId"

/**
 * Service responsible for synchronizing project list data between the server and local database.
 * Handles company projects and user projects sync with incremental checkpoint support.
 *
 * Extracted from OfflineSyncRepository to improve maintainability and testability.
 */
class ProjectSyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val syncCheckpointStore: SyncCheckpointStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private fun now() = Date()

    /**
     * Syncs all projects for a company from the server.
     * Uses incremental sync via checkpoint unless forceFullSync is true.
     *
     * @return Set of server project IDs that were synced
     */
    suspend fun syncCompanyProjects(
        companyId: Long,
        assignedToMe: Boolean = false,
        forceFullSync: Boolean = false
    ): Set<Long> = withContext(ioDispatcher) {
        val checkpointKey = companyProjectsKey(companyId, assignedToMe)
        val updatedSince = if (forceFullSync) null else syncCheckpointStore.updatedSinceParam(checkpointKey)
        val allProjects = localDataService.getAllProjects()
        val existingByServerId = allProjects.filter { it.serverId != null }.associateBy { it.serverId }
        val existingByUuid = allProjects.associateBy { it.uuid }
        val projects = fetchAllPages { page ->
            api.getCompanyProjects(
                companyId = companyId,
                page = page,
                updatedSince = updatedSince,
                assignedToMe = if (assignedToMe) "1" else null
            )
        }
        localDataService.saveProjects(
            projects.map { dto ->
                val existing = existingByServerId[dto.id] ?: existingByUuid[dto.uuid ?: dto.uid]
                dto.toEntity(existing = existing, fallbackCompanyId = companyId)
            }
        )

        // Reconcile full company sync against local data to remove stale entries.
        if (forceFullSync && !assignedToMe) {
            val serverProjectIds = projects.map { it.id }.toSet()
            val localProjects = localDataService.getAllProjects()
            val localProjectsForCompany = localProjects.filter { it.companyId == companyId }
            val orphanedProjects = localProjectsForCompany.filter { project ->
                project.serverId != null &&
                    project.serverId !in serverProjectIds &&
                    !project.isDirty
            }
            val foreignCompanyProjects = localProjects.filter { project ->
                project.companyId != null &&
                    project.companyId != companyId &&
                    project.serverId != null &&
                    !project.isDirty
            }
            val toRemove = (orphanedProjects + foreignCompanyProjects).distinctBy { it.projectId }
            if (toRemove.isNotEmpty()) {
                Log.d(
                    TAG,
                    "🧹 [syncCompanyProjects] Removing ${toRemove.size} stale projects (orphans=${orphanedProjects.size}, foreignCompany=${foreignCompanyProjects.size})"
                )
                toRemove.forEach { stale ->
                    Log.d(
                        TAG,
                        "   - Removing stale: ${stale.uuid} (serverId=${stale.serverId}, companyId=${stale.companyId})"
                    )
                }
                val cleared = toRemove.map {
                    it.copy(
                        isDeleted = true,
                        isDirty = false,
                        syncStatus = SyncStatus.SYNCED,
                        lastSyncedAt = now()
                    )
                }
                localDataService.saveProjects(cleared)
            }
        }

        projects.latestTimestamp { it.updatedAt }
            ?.let { syncCheckpointStore.updateCheckpoint(checkpointKey, it) }
        projects.map { it.id }.toSet()
    }

    /**
     * Syncs projects assigned to a specific user.
     */
    suspend fun syncUserProjects(userId: Long) = withContext(ioDispatcher) {
        val checkpointKey = userProjectsKey(userId)
        val updatedSince = syncCheckpointStore.updatedSinceParam(checkpointKey)
        val allProjects = localDataService.getAllProjects()
        val existingByServerId = allProjects.filter { it.serverId != null }.associateBy { it.serverId }
        val existingByUuid = allProjects.associateBy { it.uuid }
        val projects = fetchAllPages { page ->
            api.getUserProjects(userId = userId, page = page, updatedSince = updatedSince)
        }
        localDataService.saveProjects(
            projects.map { dto ->
                val existing = existingByServerId[dto.id] ?: existingByUuid[dto.uuid ?: dto.uid]
                dto.toEntity(existing = existing)
            }
        )
        projects.latestTimestamp { it.updatedAt }
            ?.let { syncCheckpointStore.updateCheckpoint(checkpointKey, it) }
    }

    /**
     * Fetches all pages of a paginated API response.
     */
    internal suspend fun <T> fetchAllPages(
        fetch: suspend (page: Int) -> PaginatedResponse<T>
    ): List<T> {
        val results = mutableListOf<T>()
        var page = 1
        while (true) {
            val response = fetch(page)
            results += response.data
            val current = response.meta?.currentPage ?: page
            val last = response.meta?.lastPage ?: current
            val hasMore = current < last && response.data.isNotEmpty()
            if (!hasMore) {
                break
            }
            page = current + 1
        }
        return results
    }

    companion object {
        private const val TAG = "API"
    }
}
