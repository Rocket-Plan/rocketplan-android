package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
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
 * Fetches updated record IDs from the server using /api/sync/updated.
 * Used for incremental sync to identify which projects have changed since last sync.
 */
class UpdatedRecordsSyncService(
    private val api: OfflineSyncApi,
    private val syncCheckpointStore: SyncCheckpointStore,
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Fetches the IDs of projects that have been updated since the last checkpoint.
     * Updates the checkpoint on success using the server's Date header to avoid clock skew.
     *
     * @return Result containing the set of changed project server IDs, or failure
     */
    suspend fun getUpdatedProjectIds(): Result<Set<Long>> = withContext(ioDispatcher) {
        val now = Date()
        val lastServerDate = syncCheckpointStore.getCheckpoint(SERVER_TIME_KEY)
        val rawSinceDate = syncCheckpointStore.getCheckpoint(CHECKPOINT_KEY)
            ?: Date(now.time - DEFAULT_LOOKBACK_MS)

        // Handle clock skew - clamp future checkpoints to known server time
        val sinceDate = when {
            lastServerDate != null && rawSinceDate.after(lastServerDate) -> {
                Log.w(
                    TAG,
                    "⚠️ [getUpdatedProjectIds] Future checkpoint $rawSinceDate clamped to last server time $lastServerDate (device now=$now)"
                )
                syncCheckpointStore.updateCheckpoint(CHECKPOINT_KEY, lastServerDate)
                lastServerDate
            }
            lastServerDate == null && rawSinceDate.after(now) -> {
                val clamped = Date(0) // Safe epoch fallback
                Log.w(
                    TAG,
                    "⚠️ [getUpdatedProjectIds] Future checkpoint $rawSinceDate with no server time; clamping to epoch (device now=$now)"
                )
                syncCheckpointStore.updateCheckpoint(CHECKPOINT_KEY, clamped)
                clamped
            }
            else -> rawSinceDate
        }

        val sinceParam = DateUtils.formatApiDate(sinceDate)
        Log.d(TAG, "🔄 [getUpdatedProjectIds] Fetching updates since $sinceParam")

        val response = runCatching {
            api.getUpdatedRecords(
                since = sinceParam,
                types = listOf("projects"),
                limit = MAX_UPDATED_RECORDS
            )
        }.onFailure {
            Log.e(TAG, "❌ [getUpdatedProjectIds] Failed to fetch updated records", it)
        }.getOrElse { return@withContext Result.failure(it) }

        if (!response.isSuccessful) {
            val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
            Log.e(TAG, "❌ [getUpdatedProjectIds] Non-success response ${response.code()}: $errorBody")
            return@withContext Result.failure(
                IllegalStateException("Updated records fetch failed with HTTP ${response.code()}: $errorBody")
            )
        }

        val body = response.body()
        if (body == null) {
            Log.w(TAG, "⚠️ [getUpdatedProjectIds] Empty body")
            return@withContext Result.failure(
                IllegalStateException("Updated records response body missing")
            )
        }

        // Extract project IDs from the response
        val projectIds = body.projects.map { it.id }.toSet()

        // Update checkpoint using server's Date header to avoid clock skew
        val serverTimestamp = DateUtils.parseHttpDate(response.headers()["Date"])
        if (serverTimestamp != null) {
            syncCheckpointStore.updateCheckpoint(CHECKPOINT_KEY, serverTimestamp)
            syncCheckpointStore.updateCheckpoint(SERVER_TIME_KEY, serverTimestamp)
        } else {
            Log.w(TAG, "⚠️ [getUpdatedProjectIds] Missing Date header; checkpoint not advanced")
        }

        // Build summary of all updated record types
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
            put("atmosphericLogs", body.atmosphericLogs.size)
            put("workScopeActions", body.workScopeActions.size)
            put("claims", body.claims.size)
            put("timecards", body.timecards.size)
        }
        val totalUpdated = summary.values.sum()

        Log.d(
            TAG,
            "✅ [getUpdatedProjectIds] Updated records since $sinceParam: " +
                "projects=${body.projects.size}, properties=${body.properties.size}, " +
                "rooms=${body.rooms.size}, photos=${body.photos.size}, notes=${body.notes.size}, " +
                "total=$totalUpdated"
        )

        // Log project IDs for debugging
        if (projectIds.isNotEmpty()) {
            Log.d(TAG, "📊 [getUpdatedProjectIds] Updated project IDs: $projectIds")
        }

        remoteLogger?.log(
            LogLevel.INFO,
            TAG,
            "Updated records sync completed",
            buildMap {
                put("since", sinceParam)
                put("totalUpdated", totalUpdated.toString())
                summary.forEach { (key, value) ->
                    if (value > 0) put(key, value.toString())
                }
            }
        )

        Result.success(projectIds)
    }

    /**
     * Checks if there are any updated projects without advancing the checkpoint.
     * Useful for quick checks before deciding whether to do a full sync.
     */
    suspend fun hasUpdatedProjects(): Boolean = withContext(ioDispatcher) {
        val sinceDate = syncCheckpointStore.getCheckpoint(CHECKPOINT_KEY)
            ?: return@withContext true // No checkpoint means we need full sync

        val sinceParam = DateUtils.formatApiDate(sinceDate)

        runCatching {
            val response = api.getUpdatedRecords(
                since = sinceParam,
                types = listOf("projects"),
                limit = 1 // Just need to know if there's any
            )
            if (response.isSuccessful) {
                response.body()?.projects?.isNotEmpty() == true
            } else {
                true // Assume updates on error
            }
        }.getOrDefault(true)
    }

    companion object {
        const val CHECKPOINT_KEY = "updated_records_global"
        const val SERVER_TIME_KEY = "updated_records_server_date"
        private val DEFAULT_LOOKBACK_MS = TimeUnit.DAYS.toMillis(30)
        private const val MAX_UPDATED_RECORDS = 1000
        private const val TAG = "API"
    }
}
