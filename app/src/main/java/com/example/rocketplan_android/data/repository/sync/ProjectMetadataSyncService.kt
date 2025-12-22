package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.data.repository.SyncSegment
import com.example.rocketplan_android.data.repository.mapper.latestTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toMaterialEntity
import com.example.rocketplan_android.data.repository.mapper.updatedSinceParam
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val NOTES_PAGE_LIMIT = 30

private fun projectNotesKey(projectId: Long) = "project_notes_$projectId"
private fun projectDamagesKey(projectId: Long) = "project_damages_$projectId"
private fun projectAtmosLogsKey(projectId: Long) = "project_atmos_logs_$projectId"

/**
 * Syncs project metadata: notes, equipment, damages, logs, and work scopes.
 * Extracted from OfflineSyncRepository to keep metadata sync cohesive.
 */
class ProjectMetadataSyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val syncCheckpointStore: SyncCheckpointStore,
    private val workScopeSyncService: WorkScopeSyncService,
    private val resolveServerProjectId: suspend (Long) -> Long?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val gson = Gson()

    suspend fun syncProjectMetadata(projectId: Long): SyncResult = withContext(ioDispatcher) {
        val serverProjectId = resolveServerProjectId(projectId)
            ?: return@withContext SyncResult.failure(
                SyncSegment.PROJECT_METADATA,
                IllegalStateException("Project $projectId has not been synced to server"),
                0
            )
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[syncProjectMetadata] Starting for project $projectId (server=$serverProjectId)")
        var itemCount = 0

        val notesCheckpointKey = projectNotesKey(projectId)
        val notesSince = syncCheckpointStore.updatedSinceParam(notesCheckpointKey)
        Log.d(
            TAG,
            "[syncProjectMetadata] Fetching notes with pagination (limit=$NOTES_PAGE_LIMIT) since ${notesSince ?: "beginning"}"
        )
        runCatching {
            fetchAllPages { page ->
                api.getProjectNotes(
                    projectId = serverProjectId,
                    page = page,
                    limit = NOTES_PAGE_LIMIT,
                    updatedSince = notesSince
                )
            }
        }
            .onSuccess { notes ->
                localDataService.saveNotes(notes.mapNotNull { it.toEntity() })
                itemCount += notes.size
                Log.d(TAG, "[syncProjectMetadata] Saved ${notes.size} notes (paginated)")
                notes.latestTimestamp { it.updatedAt }
                    ?.let { syncCheckpointStore.updateCheckpoint(notesCheckpointKey, it) }
            }
            .onFailure { Log.e(TAG, "[syncProjectMetadata] Failed to fetch notes", it) }
        ensureActive()

        runCatching { api.getProjectEquipment(serverProjectId) }
            .onSuccess { response ->
                val equipment = response.data
                localDataService.saveEquipment(equipment.map { it.toEntity() })
                itemCount += equipment.size
                Log.d(TAG, "[syncProjectMetadata] Saved ${equipment.size} equipment (single response)")
            }
            .onFailure { Log.e(TAG, "[syncProjectMetadata] Failed to fetch equipment", it) }
        ensureActive()

        val damagesCheckpointKey = projectDamagesKey(projectId)
        val damagesSince = syncCheckpointStore.updatedSinceParam(damagesCheckpointKey)
        val projectDamagesFetched = runCatching { api.getProjectDamageMaterials(serverProjectId, updatedSince = damagesSince) }
            .onSuccess { response ->
                val damages = response.data
                Log.d(TAG, "[syncProjectMetadata] Raw damages from API: ${damages.size}, roomIds=${damages.take(5).map { it.roomId }}")
                val entities = damages.mapNotNull { it.toEntity(defaultProjectId = projectId) }
                val (scopedDamages, unscopedDamages) = entities.partition { it.roomId != null }
                if (unscopedDamages.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "[syncProjectMetadata] Dropping ${unscopedDamages.size} damages with no roomId (projectId=$projectId)"
                    )
                }
                localDataService.saveDamages(scopedDamages)
                val materialEntities = damages.map { it.toMaterialEntity() }
                if (materialEntities.isNotEmpty()) {
                    localDataService.saveMaterials(materialEntities)
                }
                itemCount += scopedDamages.size
                Log.d(TAG, "[syncProjectMetadata] Saved ${scopedDamages.size} damages (from ${damages.size} API results)")
                damages.latestTimestamp { it.updatedAt }
                    ?.let { syncCheckpointStore.updateCheckpoint(damagesCheckpointKey, it) }
            }
            .onFailure { error ->
                Log.e(TAG, "[syncProjectMetadata] Failed to fetch damages", error)
            }
            .isSuccess
        ensureActive()

        if (!projectDamagesFetched) {
            Log.w(TAG, "[syncProjectMetadata] Project damage fetch failed; falling back to per-room damage sync")
        }
        val damagesFromProject = localDataService.observeDamages(projectId).first().takeIf { it.isNotEmpty() }
        val hasRoomScopedDamages = damagesFromProject?.any { it.roomId != null } == true
        if (!hasRoomScopedDamages) {
            if (projectDamagesFetched) {
                Log.w(
                    TAG,
                    "[syncProjectMetadata] Project damages missing roomIds; falling back to per-room damage sync (projectId=$projectId)"
                )
            }
            val damageCount = syncDamagesForProject(projectId)
            itemCount += damageCount
        }

        val moistureCount = syncMoistureLogsForProject(projectId)
        itemCount += moistureCount
        ensureActive()

        val workScopeCount = workScopeSyncService.syncWorkScopesForProject(projectId)
        itemCount += workScopeCount
        ensureActive()

        val atmosCheckpointKey = projectAtmosLogsKey(projectId)
        val atmosSince = syncCheckpointStore.updatedSinceParam(atmosCheckpointKey)
        runCatching { api.getProjectAtmosphericLogs(serverProjectId, updatedSince = atmosSince) }
            .onSuccess { response ->
                val logs = response.data
                localDataService.saveAtmosphericLogs(logs.map { it.toEntity(defaultRoomId = null) })
                itemCount += logs.size
                Log.d(TAG, "[syncProjectMetadata] Saved ${logs.size} atmospheric logs")
                logs.latestTimestamp { it.updatedAt }
                    ?.let { syncCheckpointStore.updateCheckpoint(atmosCheckpointKey, it) }
            }
            .onFailure { Log.e(TAG, "[syncProjectMetadata] Failed to fetch atmospheric logs", it) }
        ensureActive()

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "[syncProjectMetadata] Synced $itemCount items in ${duration}ms")
        SyncResult.success(SyncSegment.PROJECT_METADATA, itemCount, duration)
    }

    suspend fun syncRoomDamages(projectId: Long, roomId: Long): Int = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val response = runCatching { api.getRoomDamageMaterials(roomId) }
            .onFailure { error ->
                Log.e(TAG, "[syncRoomDamages] Failed for roomId=$roomId (projectId=$projectId)", error)
            }
            .getOrNull() ?: return@withContext 0

        val damages = response.data
        if (damages.isEmpty()) {
            Log.d(TAG, "[syncRoomDamages] No damages returned for roomId=$roomId (projectId=$projectId)")
            return@withContext 0
        }

        val entities = damages.mapNotNull { it.toEntity(defaultProjectId = projectId, defaultRoomId = roomId) }
        if (entities.isNotEmpty()) {
            localDataService.saveDamages(entities)
            val materialEntities = damages.map { it.toMaterialEntity() }
            if (materialEntities.isNotEmpty()) {
                localDataService.saveMaterials(materialEntities)
            }
        }
        val duration = System.currentTimeMillis() - startTime
        Log.d(
            TAG,
            "[syncRoomDamages] Saved ${entities.size} damages for roomId=$roomId (projectId=$projectId) in ${duration}ms"
        )
        entities.size
    }

    suspend fun syncRoomMoistureLogs(projectId: Long, roomId: Long): Int = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val response = runCatching { api.getRoomMoistureLogs(roomId, include = "damageMaterial") }
            .onFailure { error ->
                Log.e(TAG, "[syncRoomMoistureLogs] Failed for roomId=$roomId (projectId=$projectId)", error)
            }
            .getOrNull() ?: return@withContext 0

        val logs: List<MoistureLogDto> = when {
            response.data == null -> emptyList()
            response.data.isJsonArray -> {
                gson.fromJson(response.data, Array<MoistureLogDto>::class.java)?.toList() ?: emptyList()
            }
            response.data.isJsonObject -> {
                listOfNotNull(gson.fromJson(response.data, MoistureLogDto::class.java))
            }
            else -> {
                Log.w(
                    TAG,
                    "[syncRoomMoistureLogs] Unexpected JSON format for roomId=$roomId: ${response.data?.javaClass?.simpleName}"
                )
                emptyList()
            }
        }

        if (logs.isEmpty()) {
            Log.d(TAG, "[syncRoomMoistureLogs] No moisture logs returned for roomId=$roomId (projectId=$projectId)")
            return@withContext 0
        }

        val entities = logs.mapNotNull { it.toEntity() }
        localDataService.saveMoistureLogs(entities)
        val duration = System.currentTimeMillis() - startTime
        Log.d(
            TAG,
            "[syncRoomMoistureLogs] Saved ${entities.size} moisture logs for roomId=$roomId (projectId=$projectId) in ${duration}ms"
        )
        entities.size
    }

    private suspend fun syncDamagesForProject(projectId: Long): Int {
        val start = System.currentTimeMillis()
        val roomIds = localDataService.getServerRoomIdsForProject(projectId).distinct()
        if (roomIds.isEmpty()) {
            Log.d(TAG, "[syncDamages] No server room IDs for project $projectId; skipping damage sync")
            return 0
        }
        var total = 0
        roomIds.forEach { roomId ->
            coroutineContext.ensureActive()
            total += syncRoomDamages(projectId, roomId)
        }
        val duration = System.currentTimeMillis() - start
        Log.d(
            TAG,
            "[syncDamages] Synced $total damages across ${roomIds.size} rooms for project $projectId in ${duration}ms"
        )
        return total
    }

    private suspend fun syncMoistureLogsForProject(projectId: Long): Int {
        val start = System.currentTimeMillis()
        val roomIds = localDataService.getServerRoomIdsForProject(projectId).distinct()
        if (roomIds.isEmpty()) {
            Log.d(TAG, "[syncMoistureLogs] No server room IDs for project $projectId; skipping moisture log sync")
            return 0
        }
        var total = 0
        roomIds.forEach { roomId ->
            coroutineContext.ensureActive()
            total += syncRoomMoistureLogs(projectId, roomId)
        }
        val duration = System.currentTimeMillis() - start
        Log.d(
            TAG,
            "[syncMoistureLogs] Synced $total moisture logs across ${roomIds.size} rooms for project $projectId in ${duration}ms"
        )
        return total
    }

    private suspend fun <T> fetchAllPages(
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
