package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.offline.AddWorkScopeItemsRequest
import com.example.rocketplan_android.data.model.offline.WorkScopeItemRequest
import com.example.rocketplan_android.data.model.offline.WorkScopeSheetDto
import com.example.rocketplan_android.data.repository.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Handles work scope sync and catalog operations.
 */
class WorkScopeSyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun syncWorkScopesForProject(projectId: Long): Int = withContext(ioDispatcher) {
        val start = System.currentTimeMillis()
        val roomIds = localDataService.getServerRoomIdsForProject(projectId).distinct()
        if (roomIds.isEmpty()) {
            Log.d(TAG, "[syncWorkScopes] No server room IDs for project $projectId; skipping work scope sync")
            return@withContext 0
        }

        // Fetch work scopes for all rooms in parallel (matching iOS DispatchGroup pattern)
        val total = coroutineScope {
            roomIds.map { roomId ->
                async { syncRoomWorkScopes(projectId, roomId) }
            }.awaitAll().sum()
        }

        val duration = System.currentTimeMillis() - start
        Log.d(
            TAG,
            "[syncWorkScopes] Synced $total work scope items across ${roomIds.size} rooms for project $projectId in ${duration}ms (parallel)"
        )
        total
    }

    suspend fun fetchWorkScopeCatalog(companyId: Long): List<WorkScopeSheetDto> = withContext(ioDispatcher) {
        val start = System.currentTimeMillis()
        runCatching { api.getWorkScopeCatalog(companyId).data }
            .onFailure { Log.e(TAG, "[workScopeCatalog] Failed for companyId=$companyId", it) }
            .getOrElse { emptyList() }
            .also { sheets ->
                val duration = System.currentTimeMillis() - start
                Log.d(TAG, "[workScopeCatalog] Fetched ${sheets.size} sheets for companyId=$companyId in ${duration}ms")
            }
    }

    suspend fun addWorkScopeItems(
        projectId: Long,
        roomId: Long,
        items: List<WorkScopeItemRequest>
    ): Boolean = withContext(ioDispatcher) {
        if (items.isEmpty()) return@withContext true
        val body = AddWorkScopeItemsRequest(selectedItems = items)
        val start = System.currentTimeMillis()
        val response = runCatching { api.addRoomWorkScopeItems(roomId, body) }
            .onFailure { Log.e(TAG, "[addWorkScopeItems] Failed roomId=$roomId projectId=$projectId", it) }
            .getOrNull()
        val duration = System.currentTimeMillis() - start
        if (response?.isSuccessful == true) {
            Log.d(TAG, "[addWorkScopeItems] Pushed ${items.size} items for roomId=$roomId in ${duration}ms")
            true
        } else {
            Log.e(TAG, "[addWorkScopeItems] Non-success response roomId=$roomId projectId=$projectId code=${response?.code()}")
            false
        }
    }

    suspend fun syncRoomWorkScopes(projectId: Long, roomId: Long): Int = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val response = runCatching { api.getRoomWorkScope(roomId) }
            .onFailure { Log.e(TAG, "[syncRoomWorkScopes] Failed for roomId=$roomId (projectId=$projectId)", it) }
            .getOrNull() ?: return@withContext 0

        val entities = response.data.mapNotNull { it.toEntity(defaultProjectId = projectId, defaultRoomId = roomId) }
        if (entities.isNotEmpty()) {
            localDataService.saveWorkScopes(entities)
        }
        val duration = System.currentTimeMillis() - startTime
        Log.d(
            TAG,
            "[syncRoomWorkScopes] Saved ${entities.size} scope items for roomId=$roomId (projectId=$projectId) in ${duration}ms"
        )
        entities.size
    }

    companion object {
        private const val TAG = "API"
    }
}
