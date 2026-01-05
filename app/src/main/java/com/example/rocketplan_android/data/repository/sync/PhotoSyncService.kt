package com.example.rocketplan_android.data.repository.sync

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.PaginationMeta
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.RoomPhotoDto
import com.example.rocketplan_android.data.repository.SyncResult
import com.example.rocketplan_android.data.repository.SyncSegment
import com.example.rocketplan_android.data.repository.mapper.latestTimestamp
import com.example.rocketplan_android.data.repository.mapper.serverBackedTimestamp
import com.example.rocketplan_android.data.repository.mapper.toEntity
import com.example.rocketplan_android.data.repository.mapper.toPhotoDto
import com.example.rocketplan_android.data.repository.mapper.updatedSinceParam
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// API include parameter for room photo requests - fetches related data in single call
private const val ROOM_PHOTO_INCLUDE = "photo,albums,notes_count,creator"

// Pagination limits - balance between network efficiency and memory usage
private const val ROOM_PHOTO_PAGE_LIMIT = 30

// Checkpoint key functions for incremental sync
private fun roomPhotosKey(roomId: Long) = "room_photos_$roomId"
private fun floorPhotosKey(projectId: Long) = "project_floor_photos_$projectId"
private fun locationPhotosKey(projectId: Long) = "project_location_photos_$projectId"
private fun unitPhotosKey(projectId: Long) = "project_unit_photos_$projectId"

/**
 * Service responsible for synchronizing photos between the server and local database.
 * Handles room photos, project-level photos (floor, location, unit), and photo persistence.
 *
 * Extracted from OfflineSyncRepository to improve maintainability and testability.
 */
class PhotoSyncService(
    private val api: OfflineSyncApi,
    private val localDataService: LocalDataService,
    private val syncCheckpointStore: SyncCheckpointStore,
    private val photoCacheScheduler: PhotoCacheScheduler,
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val gson = Gson()
    private val roomPhotoListType = object : TypeToken<List<RoomPhotoDto>>() {}.type

    private data class RoomPhotoPageResult(
        val photos: List<PhotoDto>,
        val hasMore: Boolean,
        val nextPage: Int?
    )

    /**
     * Syncs photos for all rooms in a project.
     * Fetches room list from database and syncs photos for each.
     */
    suspend fun syncAllRoomPhotos(projectId: Long): SyncResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "🔄 [syncAllRoomPhotos] Starting for project $projectId")

        // Get current room list from database (must already be synced by syncProjectEssentials)
        val rooms = localDataService.observeRooms(projectId).first()
        if (rooms.isEmpty()) {
            Log.d(TAG, "⚠️ [syncAllRoomPhotos] No rooms found for project $projectId")
            return@withContext SyncResult.success(SyncSegment.ALL_ROOM_PHOTOS, 0, 0)
        }

        var totalPhotos = 0
        var failedRooms = 0
        val roomIds = rooms.mapNotNull { it.serverId }

        Log.d(TAG, "📸 [syncAllRoomPhotos] Fetching photos for ${roomIds.size} rooms")
        for (roomId in roomIds) {
            val result = syncRoomPhotos(projectId, roomId)
            if (result.success) {
                totalPhotos += result.itemsSynced
            } else {
                failedRooms++
                Log.w(TAG, "⚠️ [syncAllRoomPhotos] Failed room $roomId", result.error)
            }
            ensureActive()
        }

        if (totalPhotos > 0) {
            photoCacheScheduler.schedulePrefetch()
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "✅ [syncAllRoomPhotos] Synced $totalPhotos photos from ${roomIds.size - failedRooms}/${roomIds.size} rooms in ${duration}ms")
        SyncResult.success(SyncSegment.ALL_ROOM_PHOTOS, totalPhotos, duration)
    }

    /**
     * Syncs photos for a single room. Returns SyncResult for composability.
     *
     * @param source Optional caller identifier for telemetry (e.g., "RoomDetailFragment")
     * @param excludedPhotoServerIds Server IDs of photos pending local deletion to skip during sync
     */
    suspend fun syncRoomPhotos(
        projectId: Long,
        roomId: Long,
        ignoreCheckpoint: Boolean = false,
        source: String? = null,
        excludedPhotoServerIds: Set<Long> = emptySet()
    ): SyncResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val checkpointKey = roomPhotosKey(roomId)
        val checkpointValue = syncCheckpointStore.getCheckpoint(checkpointKey)
            ?.let { DateUtils.formatApiDate(it) }
            ?: "none"
        val updatedSince = if (ignoreCheckpoint) null else syncCheckpointStore.updatedSinceParam(checkpointKey)
        if (ignoreCheckpoint) {
            Log.d(
                TAG,
                "🔄 [syncRoomPhotos] Requesting photos for room $roomId (project $projectId) - " +
                    "full sync (checkpoint ignored, checkpoint=$checkpointValue)"
            )
        } else if (updatedSince != null) {
            Log.d(
                TAG,
                "🔄 [syncRoomPhotos] Requesting photos for room $roomId (project $projectId) since " +
                    "$updatedSince (checkpoint=$checkpointValue)"
            )
        } else {
            Log.d(
                TAG,
                "🔄 [syncRoomPhotos] Requesting photos for room $roomId (project $projectId) - " +
                    "full sync (checkpoint=$checkpointValue)"
            )
        }

        val photos = runCatching {
            fetchRoomPhotoPages(
                roomId = roomId,
                projectId = projectId,
                updatedSince = updatedSince
            )
        }.onFailure { error ->
            if (error is retrofit2.HttpException && error.code() == 404) {
                Log.d(TAG, "INFO [syncRoomPhotos] Room $roomId has no photos (404)")
            } else {
                Log.e(TAG, "❌ [syncRoomPhotos] Failed to fetch photos for room $roomId", error)
                val duration = System.currentTimeMillis() - startTime
                val failureResult = SyncResult.failure(SyncSegment.ROOM_PHOTOS, error, duration)
                logSegmentTelemetry(failureResult, projectId, roomId, source)
                return@withContext failureResult
            }
        }.getOrElse { emptyList() }

        if (photos.isEmpty()) {
            Log.d(TAG, "ℹ️ [syncRoomPhotos] No photos returned for room $roomId")
            val duration = System.currentTimeMillis() - startTime
            val emptyResult = SyncResult.success(SyncSegment.ROOM_PHOTOS, 0, duration)
            logSegmentTelemetry(emptyResult, projectId, roomId, source)
            return@withContext emptyResult
        }

        if (persistPhotos(photos, defaultRoomId = roomId, defaultProjectId = projectId, excludedPhotoServerIds = excludedPhotoServerIds)) {
            Log.d(TAG, "💾 [syncRoomPhotos] Saved ${photos.size} photos for room $roomId")
            photoCacheScheduler.schedulePrefetch()
        }
        photos.latestTimestamp { it.serverBackedTimestamp() }
            ?.let { syncCheckpointStore.updateCheckpoint(checkpointKey, it) }

        val duration = System.currentTimeMillis() - startTime
        val result = SyncResult.success(SyncSegment.ROOM_PHOTOS, photos.size, duration)
        logSegmentTelemetry(result, projectId, roomId, source)
        result
    }

    private fun logSegmentTelemetry(result: SyncResult, projectId: Long, roomId: Long?, source: String?) {
        val status = when (result) {
            is SyncResult.Success -> "success"
            is SyncResult.Failure -> "failure"
            is SyncResult.Incomplete -> "incomplete"
        }
        remoteLogger?.log(
            level = if (result.success) LogLevel.INFO else LogLevel.WARN,
            tag = "SyncTelemetry",
            message = "Segment ${result.segment.name} $status",
            metadata = buildMap {
                put("segment", result.segment.name)
                put("status", status)
                put("projectId", projectId.toString())
                roomId?.let { put("roomId", it.toString()) }
                put("itemsSynced", result.itemsSynced.toString())
                put("durationMs", result.durationMs.toString())
                source?.let { put("source", it) }
                result.error?.let { put("error", it.message ?: it.javaClass.simpleName) }
            }
        )
    }

    /**
     * Legacy API: syncs photos for a single room without returning SyncResult.
     * Kept for backward compatibility. Prefer syncRoomPhotos() for new code.
     */
    suspend fun refreshRoomPhotos(projectId: Long, roomId: Long) = withContext(ioDispatcher) {
        syncRoomPhotos(projectId, roomId)
    }

    /**
     * Syncs project-level photos (floor, location, unit).
     * This is typically done in the background as it's not needed for room navigation.
     *
     * @param projectId Local project ID
     * @param serverProjectId Server project ID (must be resolved by caller)
     */
    suspend fun syncProjectLevelPhotos(
        projectId: Long,
        serverProjectId: Long
    ): SyncResult = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "🔄 [syncProjectLevelPhotos] Starting for project $projectId (server=$serverProjectId)")

        var totalPhotos = 0
        var failedCount = 0

        val floorKey = floorPhotosKey(projectId)
        val floorSince = syncCheckpointStore.updatedSinceParam(floorKey)
        // Floor photos
        runCatching {
            fetchAllPages { page ->
                api.getProjectFloorPhotos(serverProjectId, page, updatedSince = floorSince)
            }
                .map { it.toPhotoDto(projectId) }
        }.onSuccess { photos ->
            if (persistPhotos(photos)) {
                totalPhotos += photos.size
                Log.d(TAG, "📸 [syncProjectLevelPhotos] Saved ${photos.size} floor photos")
            }
            photos.latestTimestamp { it.updatedAt }
                ?.let { syncCheckpointStore.updateCheckpoint(floorKey, it) }
        }.onFailure { error ->
            failedCount++
            Log.e(TAG, "❌ [syncProjectLevelPhotos] Failed to fetch floor photos", error)
        }
        ensureActive()

        // Location photos (THE SLOW ONE that was blocking room loading)
        val locationKey = locationPhotosKey(projectId)
        val locationSince = syncCheckpointStore.updatedSinceParam(locationKey)
        runCatching {
            fetchAllPages { page ->
                api.getProjectLocationPhotos(serverProjectId, page, updatedSince = locationSince)
            }
                .map { it.toPhotoDto(projectId) }
        }.onSuccess { photos ->
            if (persistPhotos(photos)) {
                totalPhotos += photos.size
                Log.d(TAG, "📸 [syncProjectLevelPhotos] Saved ${photos.size} location photos")
            }
            photos.latestTimestamp { it.updatedAt }
                ?.let { syncCheckpointStore.updateCheckpoint(locationKey, it) }
        }.onFailure { error ->
            failedCount++
            Log.e(TAG, "❌ [syncProjectLevelPhotos] Failed to fetch location photos", error)
        }
        ensureActive()

        // Unit photos
        val unitKey = unitPhotosKey(projectId)
        val unitSince = syncCheckpointStore.updatedSinceParam(unitKey)
        runCatching {
            fetchAllPages { page ->
                api.getProjectUnitPhotos(serverProjectId, page, updatedSince = unitSince)
            }
                .map { it.toPhotoDto(projectId) }
        }.onSuccess { photos ->
            if (persistPhotos(photos)) {
                totalPhotos += photos.size
                Log.d(TAG, "📸 [syncProjectLevelPhotos] Saved ${photos.size} unit photos")
            }
            photos.latestTimestamp { it.updatedAt }
                ?.let { syncCheckpointStore.updateCheckpoint(unitKey, it) }
        }.onFailure { error ->
            failedCount++
            Log.e(TAG, "❌ [syncProjectLevelPhotos] Failed to fetch unit photos", error)
        }

        if (totalPhotos > 0) {
            photoCacheScheduler.schedulePrefetch()
        }

        val duration = System.currentTimeMillis() - startTime
        if (failedCount == 3) {
            // All three photo types failed
            Log.e(TAG, "❌ [syncProjectLevelPhotos] All photo types failed")
            return@withContext SyncResult.failure(
                SyncSegment.PROJECT_LEVEL_PHOTOS,
                Exception("All project-level photo fetches failed"),
                duration
            )
        }
        Log.d(TAG, "✅ [syncProjectLevelPhotos] Synced $totalPhotos photos in ${duration}ms (${failedCount}/3 types failed)")
        SyncResult.success(SyncSegment.PROJECT_LEVEL_PHOTOS, totalPhotos, duration)
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

    private suspend fun fetchRoomPhotoPages(
        roomId: Long,
        projectId: Long,
        updatedSince: String?
    ): List<PhotoDto> {
        val collected = mutableListOf<PhotoDto>()
        var page = 1

        while (true) {
            val json = api.getRoomPhotos(
                roomId = roomId,
                page = page,
                limit = ROOM_PHOTO_PAGE_LIMIT,
                include = ROOM_PHOTO_INCLUDE,
                updatedSince = updatedSince
            )

            val parsed = parseRoomPhotoResponse(json, projectId, roomId)
            collected += parsed.photos

            if (!parsed.hasMore || parsed.nextPage == null || parsed.photos.isEmpty()) {
                break
            }
            if (parsed.nextPage == page) {
                break
            }
            page = parsed.nextPage
        }

        return collected
    }

    private fun parseRoomPhotoResponse(
        json: JsonObject,
        projectId: Long,
        roomId: Long
    ): RoomPhotoPageResult {
        val photos = mutableListOf<PhotoDto>()

        fun collect(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element is JsonArray -> {
                    val list: List<RoomPhotoDto> = gson.fromJson(element, roomPhotoListType)
                    photos += list.mapNotNull { it.toPhotoDto(defaultProjectId = projectId, defaultRoomId = roomId) }
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    collect(obj.get("data"))
                    collect(obj.get("photos"))
                }
            }
        }

        collect(json.get("data"))
        collect(json.get("photos"))

        val dataObject = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val metaElement = when {
            json.get("meta")?.isJsonObject == true -> json.getAsJsonObject("meta")
            dataObject?.get("meta")?.isJsonObject == true -> dataObject.getAsJsonObject("meta")
            else -> null
        }

        val meta = metaElement?.let { gson.fromJson(it, PaginationMeta::class.java) }
        val currentFromMeta = meta?.currentPage
        val lastFromMeta = meta?.lastPage
        val currentFromData = dataObject?.get("current_page")?.takeIf { it.isJsonPrimitive }?.asInt
        val lastFromData = dataObject?.get("last_page")?.takeIf { it.isJsonPrimitive }?.asInt

        val current = currentFromMeta ?: currentFromData ?: -1
        val last = lastFromMeta ?: lastFromData ?: current
        val hasMore = current > 0 && last > current
        val nextPage = if (hasMore) current + 1 else null

        return RoomPhotoPageResult(
            photos = photos,
            hasMore = hasMore,
            nextPage = nextPage
        )
    }

    internal suspend fun persistPhotos(
        photos: List<PhotoDto>,
        defaultRoomId: Long? = null,
        defaultProjectId: Long? = null,
        excludedPhotoServerIds: Set<Long> = emptySet()
    ): Boolean {
        if (photos.isEmpty()) {
            return false
        }

        val entities = mutableListOf<OfflinePhotoEntity>()
        var mismatchCount = 0
        var skippedPendingDeletionCount = 0
        val roomsNeedingSnapshotRefresh = mutableMapOf<Long, Int>()
        for (photo in photos) {
            // Skip photos that are pending local deletion to avoid resurrecting them
            if (photo.id in excludedPhotoServerIds) {
                skippedPendingDeletionCount++
                continue
            }

            val existing = localDataService.getPhotoByServerId(photo.id)
            val preservedRoom = existing?.roomId

            // Always use provided defaults to maintain sync context integrity
            val resolvedRoomId = defaultRoomId ?: photo.roomId ?: preservedRoom
            val resolvedProjectId = defaultProjectId ?: photo.projectId

            // Log mismatches for debugging data integrity issues
            if (defaultProjectId != null && photo.projectId != defaultProjectId) {
                mismatchCount++
                Log.w(TAG, "⚠️ [persistPhotos] Photo ${photo.id} has projectId=${photo.projectId} but syncing for project $defaultProjectId - using $defaultProjectId")
            }
            if (defaultRoomId != null && photo.roomId != null && photo.roomId != defaultRoomId) {
                Log.w(TAG, "⚠️ [persistPhotos] Photo ${photo.id} has roomId=${photo.roomId} but syncing for room $defaultRoomId - using $defaultRoomId")
            }

            val resolvedFileName = photo.fileName ?: "photo_${photo.id}.jpg"
            val removedCount = pruneLocalPlaceholderForIncomingPhoto(
                projectId = resolvedProjectId,
                roomId = resolvedRoomId,
                fileName = resolvedFileName
            )
            if (removedCount > 0 && resolvedRoomId != null) {
                roomsNeedingSnapshotRefresh.merge(resolvedRoomId, removedCount, Int::plus)
            }

            entities += photo.toEntity(
                defaultRoomId = resolvedRoomId,
                defaultProjectId = resolvedProjectId
            )
        }

        if (mismatchCount > 0) {
            Log.w(TAG, "⚠️ [persistPhotos] Fixed $mismatchCount photos with mismatched projectId")
        }
        if (skippedPendingDeletionCount > 0) {
            Log.d(TAG, "🗑️ [persistPhotos] Skipped $skippedPendingDeletionCount photos pending local deletion")
        }

        localDataService.savePhotos(entities)

        // Extract and save albums from photos
        val albums = buildList<OfflineAlbumEntity> {
            photos.forEach { photo ->
                photo.albums?.forEach { album ->
                    // Always use defaultProjectId when provided (sync context) over DTO value
                    val projectId = defaultProjectId ?: photo.projectId ?: 0L
                    // Prioritize defaultRoomId (canonical server ID) when available
                    val roomId = defaultRoomId ?: photo.roomId
                    add(album.toEntity(defaultProjectId = projectId, defaultRoomId = roomId))
                }
            }
        }.distinctBy { it.albumId }

        if (albums.isNotEmpty()) {
            localDataService.saveAlbums(albums)
            Log.d(TAG, "📂 [persistPhotos] Saved ${albums.size} albums from photos (defaultRoomId=$defaultRoomId)")
            albums.forEach { album ->
                Log.d(TAG, "  Album '${album.name}' (id=${album.albumId}): roomId=${album.roomId}, projectId=${album.projectId}")
            }
        }

        val albumPhotoRelationships = buildList<OfflineAlbumPhotoEntity> {
            photos.forEach { photo ->
                photo.albums?.forEach { album ->
                    add(
                        OfflineAlbumPhotoEntity(
                            albumId = album.id,
                            photoServerId = photo.id
                        )
                    )
                }
            }
        }
        if (albumPhotoRelationships.isNotEmpty()) {
            localDataService.saveAlbumPhotos(albumPhotoRelationships)
            Log.d(TAG, "📸 [persistPhotos] Saved ${albumPhotoRelationships.size} album-photo relationships")
        }

        roomsNeedingSnapshotRefresh.forEach { (roomId, _) ->
            runCatching { localDataService.refreshRoomPhotoSnapshot(roomId) }
                .onFailure {
                    Log.w(TAG, "⚠️ [persistPhotos] Failed to refresh snapshot after placeholder cleanup for room $roomId", it)
                    remoteLogger?.log(
                        level = LogLevel.WARN,
                        tag = "PhotoSyncService",
                        message = "Failed to refresh snapshot after placeholder cleanup",
                        metadata = mapOf(
                            "room_id" to roomId.toString(),
                            "error" to (it.message ?: "unknown")
                        )
                    )
                }
        }

        if (roomsNeedingSnapshotRefresh.isNotEmpty()) {
            val totalRemoved = roomsNeedingSnapshotRefresh.values.sum()
            remoteLogger?.log(
                level = LogLevel.INFO,
                tag = "PhotoSyncService",
                message = "Removed local pending placeholders before applying server photos",
                metadata = mapOf(
                    "total_removed" to totalRemoved.toString(),
                    "rooms" to roomsNeedingSnapshotRefresh.entries.joinToString { "${it.key}:${it.value}" }
                )
            )
        }

        return true
    }

    private suspend fun pruneLocalPlaceholderForIncomingPhoto(
        projectId: Long?,
        roomId: Long?,
        fileName: String
    ): Int {
        if (projectId == null || roomId == null) return 0

        val removed = localDataService.deleteLocalPendingRoomPhoto(projectId, roomId, fileName)
        if (removed <= 0) return 0

        Log.d(
            TAG,
            "🧹 [persistPhotos] Removed $removed local pending photo(s) named '$fileName' " +
                "for roomId=$roomId projectId=$projectId before applying server update"
        )

        return removed
    }

    companion object {
        private const val TAG = "API"
    }
}
