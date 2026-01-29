package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.cache.PhotoCacheManager
import com.example.rocketplan_android.data.model.offline.DeletedRecordsResponse
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.util.DateUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Syncs server-side deleted records and applies deletions locally.
 */
class DeletedRecordsSyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val syncCheckpointStore: SyncCheckpointStore,
    private val photoCacheManager: PhotoCacheManager? = null,
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun syncDeletedRecords(types: List<String> = DEFAULT_TYPES): Result<Unit> = withContext(ioDispatcher) {
        val now = Date()
        val lastServerDate = syncCheckpointStore.getCheckpoint(SERVER_TIME_CHECKPOINT_KEY)
        val rawSinceDate = syncCheckpointStore.getCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY)
            ?: Date(now.time - DEFAULT_DELETION_LOOKBACK_MS)
        val sinceDate = when {
            lastServerDate != null && rawSinceDate.after(lastServerDate) -> {
                Log.w(
                    TAG,
                    "⚠️ [syncDeletedRecords] Future checkpoint $rawSinceDate clamped to last server time $lastServerDate (device now=$now)"
                )
                syncCheckpointStore.updateCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY, lastServerDate)
                lastServerDate
            }
            lastServerDate == null && rawSinceDate.after(now) -> {
                val clamped = Date(0) // Safe epoch fallback avoids future-dated requests
                Log.w(
                    TAG,
                    "⚠️ [syncDeletedRecords] Future checkpoint $rawSinceDate with no server time; clamping to epoch (device now=$now)"
                )
                syncCheckpointStore.updateCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY, clamped)
                clamped
            }
            else -> rawSinceDate
        }
        val sinceParam = DateUtils.formatApiDate(sinceDate)
        val filteredTypes = types.filter { it.isNotBlank() }

        Log.d(TAG, "🔄 [syncDeletedRecords] Fetching deletions since $sinceParam (types=${filteredTypes.joinToString()})")
        val response = runCatching {
            api.getDeletedRecords(
                since = sinceParam,
                types = filteredTypes.takeIf { it.isNotEmpty() }
            )
        }.onFailure {
            Log.e(TAG, "❌ [syncDeletedRecords] Failed to fetch deletions", it)
        }.getOrElse { return@withContext Result.failure(it) }

        if (!response.isSuccessful) {
            val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
            Log.e(TAG, "❌ [syncDeletedRecords] Non-success response ${response.code()}: $errorBody")
            return@withContext Result.failure(
                IllegalStateException("Deleted records sync failed with HTTP ${response.code()}: $errorBody")
            )
        }

        val body = response.body()
        if (body == null) {
            Log.w(TAG, "⚠️ [syncDeletedRecords] Empty body; skipping apply")
            return@withContext Result.failure(
                IllegalStateException("Deleted records response body missing")
            )
        }

        val applyResult = runCatching { applyDeletedRecords(body) }
            .onFailure { Log.e(TAG, "❌ [syncDeletedRecords] Failed to apply deletions", it) }
        if (applyResult.isFailure) {
            return@withContext Result.failure(
                applyResult.exceptionOrNull() ?: IllegalStateException("Failed to apply deleted records")
            )
        }

        val serverTimestamp = DateUtils.parseHttpDate(response.headers()["Date"])
        if (serverTimestamp != null) {
            syncCheckpointStore.updateCheckpoint(DELETED_RECORDS_CHECKPOINT_KEY, serverTimestamp)
            syncCheckpointStore.updateCheckpoint(SERVER_TIME_CHECKPOINT_KEY, serverTimestamp)
        } else {
            Log.w(TAG, "⚠️ [syncDeletedRecords] Missing Date header; checkpoint not advanced")
        }
        val summary = buildMap {
            put("projects", body.projects.size)
            put("properties", body.properties.size)
            put("rooms", body.rooms.size)
            put("locations", body.locations.size)
            put("photos", body.photos.size)
            put("notes", body.notes.size)
            put("equipment", body.equipment.size)
            put("damageMaterials", body.damageMaterials.size)
            put("damageMaterialRoomLogs", body.damageMaterialRoomLogs.size)
            put("moistureLogs", body.moistureLogs.size)
            put("atmosphericLogs", body.atmosphericLogs.size)
            put("workScopeActions", body.workScopeActions.size)
        }
        val totalDeleted = summary.values.sum()
        Log.d(
            TAG,
            "✅ [syncDeletedRecords] Applied deletions projects=${body.projects.size}, properties=${body.properties.size}, " +
                "rooms=${body.rooms.size}, photos=${body.photos.size}, notes=${body.notes.size}, total=$totalDeleted"
        )
        remoteLogger?.log(
            LogLevel.INFO,
            TAG,
            "Deleted records sync completed",
            buildMap {
                put("since", sinceParam)
                put("totalDeleted", totalDeleted.toString())
                summary.forEach { (key, value) ->
                    if (value > 0) put(key, value.toString())
                }
            }
        )
        Result.success(Unit)
    }

    private suspend fun applyDeletedRecords(response: DeletedRecordsResponse) {
        // Cascade delete projects first - this will also delete all child entities
        // (rooms, photos, notes, etc.) even if the server didn't explicitly list them
        val cachedPhotos = localDataService.cascadeDeleteProjectsByServerIds(response.projects)

        // Clean up cached photo files from disk
        if (cachedPhotos.isNotEmpty()) {
            photoCacheManager?.removeCachedPhotos(cachedPhotos)
        }

        // Apply remaining deletions for entities the server explicitly listed
        // (these may be redundant for project children but are idempotent)
        localDataService.markPropertiesDeleted(response.properties)
        localDataService.markRoomsDeleted(response.rooms)
        localDataService.markLocationsDeleted(response.locations)
        localDataService.markPhotosDeleted(response.photos)
        localDataService.markNotesDeleted(response.notes)
        localDataService.markEquipmentDeleted(response.equipment)
        localDataService.markDamagesDeleted(response.damageMaterials)
        localDataService.markAtmosphericLogsDeleted(response.atmosphericLogs)
        // Handle both moisture_logs and damage_material_room_logs (same entity type)
        val allMoistureLogs = (response.moistureLogs + response.damageMaterialRoomLogs).distinct()
        localDataService.markMoistureLogsDeleted(allMoistureLogs)
        localDataService.markWorkScopesDeleted(response.workScopeActions)
    }

    companion object {
        // Note: atmospheric_logs are not supported by the backend API
        val DEFAULT_TYPES = listOf(
            "projects",
            "properties",
            "photos",
            "notes",
            "rooms",
            "locations",
            "equipment",
            "damage_materials",
            "damage_material_room_logs",
            "work_scope_actions"
        )
        private val DEFAULT_DELETION_LOOKBACK_MS = TimeUnit.DAYS.toMillis(30)
        private const val DELETED_RECORDS_CHECKPOINT_KEY = "deleted_records_global"
        private const val SERVER_TIME_CHECKPOINT_KEY = "deleted_records_server_date"
        private const val TAG = "API"
    }
}
