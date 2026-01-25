package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.repository.mapper.latestTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.updatedSinceParam
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // Note: Deletion sync is handled separately by DeletedRecordsSyncService
        // which uses the /api/sync/deleted endpoint to get explicit deletions from the server.
        // We no longer infer deletions from absence in the project list response.

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
}
