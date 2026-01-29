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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private const val NOTES_PAGE_LIMIT = 30

private fun projectNotesKey(projectId: Long) = "project_notes_$projectId"
private fun projectDamagesKey(projectId: Long) = "project_damages_$projectId"
private fun projectAtmosLogsKey(projectId: Long) = "project_atmos_logs_$projectId"

/**
 * Syncs project metadata: notes, equipment, damages, logs, and work scopes.
 * Uses DependencySyncQueue for iOS-style parallel execution with dependencies.
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

    /**
     * Sync project metadata using dependency queue for maximum parallelism.
     *
     * Dependency graph (iOS-style):
     * ```
     * [notes] ─────────────────────────────────────┐
     * [equipment] ─────────────────────────────────┤
     * [atmospheric_logs] ──────────────────────────┤
     * [project_damages] ──┬── (if failed) ─────────┤
     *                     │                        │
     *                     ▼                        │
     *              [room_damages_1..N] ────────────┤
     *              [room_moisture_1..N] ───────────┤
     *              [room_workscopes_1..N] ─────────┘
     * ```
     */
    suspend fun syncProjectMetadata(projectId: Long): SyncResult = withContext(ioDispatcher) {
        val serverProjectId = resolveServerProjectId(projectId)
            ?: return@withContext SyncResult.failure(
                SyncSegment.PROJECT_METADATA,
                IllegalStateException("Project $projectId has not been synced to server"),
                0
            )
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[syncProjectMetadata] Starting dependency-based sync for project $projectId (server=$serverProjectId)")

        val itemCount = AtomicInteger(0)
        val queue = DependencySyncQueue(tag = "MetadataSync[$projectId]")

        // Track if project-level damages succeeded (for fallback logic)
        var projectDamagesHaveRoomIds = false

        // === PHASE 1: Independent project-level syncs (no dependencies) ===

        // Notes (paginated, independent)
        queue.addItem("notes") {
            val notesCheckpointKey = projectNotesKey(projectId)
            val notesSince = syncCheckpointStore.updatedSinceParam(notesCheckpointKey)
            runCatching {
                fetchAllPages { page ->
                    api.getProjectNotes(serverProjectId, page, NOTES_PAGE_LIMIT, notesSince)
                }
            }.onSuccess { notes ->
                localDataService.saveNotes(notes.mapNotNull { it.toEntity() })
                itemCount.addAndGet(notes.size)
                notes.latestTimestamp { it.updatedAt }
                    ?.let { syncCheckpointStore.updateCheckpoint(notesCheckpointKey, it) }
            }.isSuccess
        }

        // Equipment (single request, independent)
        queue.addItem("equipment") {
            runCatching { api.getProjectEquipment(serverProjectId) }
                .onSuccess { response ->
                    localDataService.saveEquipment(response.data.map { it.toEntity() })
                    itemCount.addAndGet(response.data.size)
                }.isSuccess
        }

        // Atmospheric logs (independent)
        queue.addItem("atmospheric_logs") {
            val atmosCheckpointKey = projectAtmosLogsKey(projectId)
            val atmosSince = syncCheckpointStore.updatedSinceParam(atmosCheckpointKey)
            runCatching { api.getProjectAtmosphericLogs(serverProjectId, atmosSince) }
                .onSuccess { response ->
                    localDataService.saveAtmosphericLogs(response.data.map { it.toEntity(defaultRoomId = null) })
                    itemCount.addAndGet(response.data.size)
                    response.data.latestTimestamp { it.updatedAt }
                        ?.let { syncCheckpointStore.updateCheckpoint(atmosCheckpointKey, it) }
                }.isSuccess
        }

        // Project-level damages (try first, may need per-room fallback)
        val projectDamagesId = queue.addItem("project_damages") {
            val damagesCheckpointKey = projectDamagesKey(projectId)
            val damagesSince = syncCheckpointStore.updatedSinceParam(damagesCheckpointKey)
            runCatching { api.getProjectDamageMaterials(serverProjectId, damagesSince) }
                .onSuccess { response ->
                    val damages = response.data
                    val entities = damages.mapNotNull { it.toEntity(defaultProjectId = projectId) }
                    val (scopedDamages, unscopedDamages) = entities.partition { it.roomId != null }

                    if (scopedDamages.isNotEmpty()) {
                        localDataService.saveDamages(scopedDamages)
                        localDataService.saveMaterials(damages.map { it.toMaterialEntity() })
                        itemCount.addAndGet(scopedDamages.size)
                        projectDamagesHaveRoomIds = true
                        damages.latestTimestamp { it.updatedAt }
                            ?.let { syncCheckpointStore.updateCheckpoint(damagesCheckpointKey, it) }
                    }

                    if (unscopedDamages.isNotEmpty()) {
                        Log.w(TAG, "[project_damages] ${unscopedDamages.size} damages missing roomId")
                    }
                }.isSuccess
        }

        // Process phase 1 first to determine if we need per-room fallback
        queue.processAll()

        // === PHASE 2: Per-room syncs (only if project damages didn't have roomIds) ===

        val roomIds = localDataService.getServerRoomIdsForProject(projectId).distinct()
        if (roomIds.isNotEmpty()) {
            val queue2 = DependencySyncQueue(tag = "MetadataSync[$projectId]/rooms")

            // Check if we need per-room damage fallback
            val needsPerRoomDamages = !projectDamagesHaveRoomIds &&
                localDataService.observeDamages(projectId).first().none { it.roomId != null }

            if (needsPerRoomDamages) {
                Log.d(TAG, "[syncProjectMetadata] Using per-room damage sync for ${roomIds.size} rooms")
            }

            // Queue per-room syncs (all run in parallel - no dependencies between rooms)
            for (roomId in roomIds) {
                // Per-room damages (only if project-level didn't work)
                if (needsPerRoomDamages) {
                    queue2.addItem("room_damages_$roomId") {
                        val count = syncRoomDamages(projectId, roomId)
                        itemCount.addAndGet(count)
                        true
                    }
                }

                // Per-room moisture logs
                queue2.addItem("room_moisture_$roomId") {
                    val count = syncRoomMoistureLogs(projectId, roomId)
                    itemCount.addAndGet(count)
                    true
                }

                // Per-room work scopes
                queue2.addItem("room_workscopes_$roomId") {
                    val count = workScopeSyncService.syncRoomWorkScopes(projectId, roomId)
                    itemCount.addAndGet(count)
                    true
                }
            }

            queue2.processAll()
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "[syncProjectMetadata] Synced ${itemCount.get()} items in ${duration}ms (dependency-based)")
        SyncResult.success(SyncSegment.PROJECT_METADATA, itemCount.get(), duration)
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
