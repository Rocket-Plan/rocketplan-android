package com.example.rocketplan_android.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineCompanyEntity
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageCauseEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogLevelEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogPropertyTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineCatalogRoomTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineLocationEntity
import com.example.rocketplan_android.data.local.entity.OfflineMaterialEntity
import com.example.rocketplan_android.data.local.entity.OfflineMoistureLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflinePropertyEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomPhotoSnapshotEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeCatalogItemEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportCategoryEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportConversationEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportMessageEntity
import com.example.rocketplan_android.data.local.entity.OfflineSupportMessageAttachmentEntity
import com.example.rocketplan_android.data.local.model.RoomPhotoSummary
import com.example.rocketplan_android.data.local.model.ProjectWithProperty
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface OfflineDao {

    // region Projects
    @Upsert
    suspend fun upsertProject(project: OfflineProjectEntity)

    @Upsert
    suspend fun upsertProjects(projects: List<OfflineProjectEntity>)

    @Query("UPDATE offline_projects SET propertyId = NULL, propertyType = NULL WHERE propertyId = :propertyId")
    suspend fun clearProjectPropertyId(propertyId: Long)

    @Query("SELECT * FROM offline_projects WHERE isDeleted = 0")
    fun observeProjects(): Flow<List<OfflineProjectEntity>>

    @Transaction
    @Query("SELECT * FROM offline_projects WHERE isDeleted = 0")
    fun observeProjectsWithProperty(): Flow<List<ProjectWithProperty>>

    @Query("SELECT * FROM offline_projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getProject(projectId: Long): OfflineProjectEntity?

    @Query("SELECT * FROM offline_projects WHERE serverId = :serverId AND companyId = :companyId AND isDeleted = 0 LIMIT 1")
    suspend fun getProjectByServerId(serverId: Long, companyId: Long): OfflineProjectEntity?

    @Query("SELECT * FROM offline_projects WHERE isDeleted = 0")
    suspend fun getProjectsOnce(): List<OfflineProjectEntity>

    @Query("SELECT COUNT(*) FROM offline_projects")
    suspend fun countProjects(): Int

    @Query("UPDATE offline_projects SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markProjectsDeleted(serverIds: List<Long>)

    /** Force-marks projects as deleted regardless of isDirty state. Used for server-side cascade deletes. */
    @Query("UPDATE offline_projects SET isDeleted = 1, isDirty = 0 WHERE serverId IN (:serverIds)")
    suspend fun forceMarkProjectsDeletedByServerIds(serverIds: List<Long>)

    @Query("UPDATE offline_projects SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markProjectDeletedByLocalId(projectId: Long)
    // endregion

    // region Locations
    @Upsert
    suspend fun upsertLocations(locations: List<OfflineLocationEntity>)

    @Query("SELECT * FROM offline_locations WHERE projectId = :projectId AND isDeleted = 0 ORDER BY title")
    fun observeLocationsForProject(projectId: Long): Flow<List<OfflineLocationEntity>>

    @Query("SELECT * FROM offline_locations WHERE projectId = :projectId AND isDeleted = 0 ORDER BY title")
    suspend fun getLocationsForProject(projectId: Long): List<OfflineLocationEntity>

    @Query("SELECT MAX(updatedAt) FROM offline_locations WHERE projectId = :projectId AND isDeleted = 0")
    suspend fun getLatestLocationUpdatedAt(projectId: Long): Date?

    @Query("SELECT * FROM offline_locations WHERE uuid = :uuid AND isDeleted = 0 LIMIT 1")
    suspend fun getLocationByUuid(uuid: String): OfflineLocationEntity?

    @Query("SELECT * FROM offline_locations WHERE locationId = :locationId AND isDeleted = 0 LIMIT 1")
    suspend fun getLocation(locationId: Long): OfflineLocationEntity?

    @Query("UPDATE offline_locations SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markLocationsDeleted(serverIds: List<Long>)

    @Query("UPDATE offline_locations SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markLocationsDeletedByProject(projectId: Long)
    // endregion

    // region Rooms
    @Upsert
    suspend fun upsertRooms(rooms: List<OfflineRoomEntity>)

    @Query("SELECT * FROM offline_rooms WHERE projectId = :projectId AND isDeleted = 0 ORDER BY title")
    fun observeRoomsForProject(projectId: Long): Flow<List<OfflineRoomEntity>>

    @Query("SELECT * FROM offline_rooms WHERE projectId = :projectId AND isDeleted = 0 ORDER BY title")
    suspend fun getRoomsForProject(projectId: Long): List<OfflineRoomEntity>

    @Query("SELECT roomId FROM offline_rooms WHERE projectId = :projectId")
    suspend fun getRoomIdsForProject(projectId: Long): List<Long>

    @Query("SELECT serverId FROM offline_rooms WHERE projectId = :projectId AND serverId IS NOT NULL AND isDeleted = 0")
    suspend fun getServerRoomIdsForProject(projectId: Long): List<Long>

    @Query("UPDATE offline_rooms SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markRoomsDeletedByProject(projectId: Long)

    @Query("UPDATE offline_rooms SET isDeleted = 1, isDirty = 1 WHERE locationId = :locationId")
    suspend fun markRoomsDeletedByLocation(locationId: Long)

    @Query("SELECT * FROM offline_rooms WHERE roomId = :roomId LIMIT 1")
    suspend fun getRoom(roomId: Long): OfflineRoomEntity?

    @Query("SELECT * FROM offline_rooms WHERE serverId = :serverId LIMIT 1")
    suspend fun getRoomByServerId(serverId: Long): OfflineRoomEntity?

    @Query("SELECT * FROM offline_rooms WHERE uuid = :uuid LIMIT 1")
    suspend fun getRoomByUuid(uuid: String): OfflineRoomEntity?

    @Query(
        """
        SELECT * FROM offline_rooms
        WHERE projectId = :projectId
          AND isDeleted = 0
          AND serverId IS NULL
          AND title = :title
        LIMIT 1
        """
    )
    suspend fun getPendingRoomForProject(projectId: Long, title: String): OfflineRoomEntity?

    @Query(
        """
        SELECT COUNT(*) FROM offline_rooms
        WHERE projectId = :projectId
          AND isDeleted = 0
          AND serverId IS NULL
          AND title = :title
        """
    )
    suspend fun countPendingRoomsForProjectTitle(projectId: Long, title: String): Int

    @Query("SELECT * FROM offline_rooms WHERE serverId IS NOT NULL")
    suspend fun getRoomsWithServerId(): List<OfflineRoomEntity>

    @Query("SELECT MAX(updatedAt) FROM offline_rooms WHERE locationId = :locationId AND isDeleted = 0")
    suspend fun getLatestRoomUpdatedAt(locationId: Long): Date?

    @Query("SELECT * FROM offline_rooms WHERE projectId = :projectId AND isDeleted = 1 AND isDirty = 1 AND serverId IS NOT NULL")
    suspend fun getPendingRoomDeletions(projectId: Long): List<OfflineRoomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoom(room: OfflineRoomEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRooms(rooms: List<OfflineRoomEntity>): List<Long>

    @Query("DELETE FROM offline_rooms WHERE roomId = :roomId")
    suspend fun deleteRoomById(roomId: Long)

    @Query("DELETE FROM offline_rooms WHERE roomId = 0")
    suspend fun deletePhantomRoom(): Int

    @Query("UPDATE offline_rooms SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markRoomsDeleted(serverIds: List<Long>)
    // endregion

    // region Room types
    @Upsert
    suspend fun upsertRoomTypes(types: List<OfflineRoomTypeEntity>)

    @Query(
        """
        SELECT * FROM offline_room_types
        WHERE propertyServerId = :propertyServerId
          AND filterType = :filterType
        ORDER BY 
            CASE WHEN name IS NULL OR name = '' THEN 1 ELSE 0 END,
            name COLLATE NOCASE
        """
    )
    suspend fun getRoomTypes(propertyServerId: Long, filterType: String): List<OfflineRoomTypeEntity>

    @Query(
        """
        SELECT * FROM offline_room_types
        WHERE propertyServerId = :propertyServerId
          AND filterType = :filterType
        ORDER BY 
            CASE WHEN name IS NULL OR name = '' THEN 1 ELSE 0 END,
            name COLLATE NOCASE
        """
    )
    fun observeRoomTypes(propertyServerId: Long, filterType: String): Flow<List<OfflineRoomTypeEntity>>

    @Query("DELETE FROM offline_room_types WHERE propertyServerId = :propertyServerId AND filterType = :filterType")
    suspend fun clearRoomTypes(propertyServerId: Long, filterType: String)
    // endregion

    // region Offline catalog
    @Upsert
    suspend fun upsertCatalogPropertyTypes(types: List<OfflineCatalogPropertyTypeEntity>)

    @Upsert
    suspend fun upsertCatalogLevels(levels: List<OfflineCatalogLevelEntity>)

    @Upsert
    suspend fun upsertCatalogRoomTypes(types: List<OfflineCatalogRoomTypeEntity>)

    @Query("DELETE FROM offline_catalog_property_types")
    suspend fun clearCatalogPropertyTypes()

    @Query("DELETE FROM offline_catalog_levels")
    suspend fun clearCatalogLevels()

    @Query("DELETE FROM offline_catalog_room_types")
    suspend fun clearCatalogRoomTypes()

    @Query("SELECT * FROM offline_catalog_property_types ORDER BY sortOrder, name")
    suspend fun getCatalogPropertyTypes(): List<OfflineCatalogPropertyTypeEntity>

    @Query("SELECT * FROM offline_catalog_levels ORDER BY name")
    suspend fun getCatalogLevels(): List<OfflineCatalogLevelEntity>

    @Query("SELECT * FROM offline_catalog_room_types ORDER BY name")
    suspend fun getCatalogRoomTypes(): List<OfflineCatalogRoomTypeEntity>
    // endregion

    // region Work Scope Catalog
    @Upsert
    suspend fun upsertWorkScopeCatalogItems(items: List<OfflineWorkScopeCatalogItemEntity>)

    @Query(
        """
        SELECT * FROM offline_work_scope_catalog_items
        WHERE companyId = :companyId
        ORDER BY sheetId, itemId
        """
    )
    suspend fun getWorkScopeCatalogItems(companyId: Long): List<OfflineWorkScopeCatalogItemEntity>

    @Query("SELECT MAX(fetchedAt) FROM offline_work_scope_catalog_items WHERE companyId = :companyId")
    suspend fun getLatestWorkScopeCatalogFetchedAt(companyId: Long): Date?

    @Query("DELETE FROM offline_work_scope_catalog_items WHERE companyId = :companyId")
    suspend fun clearWorkScopeCatalogItems(companyId: Long)
    // endregion

    // region Damage Types / Causes
    @Upsert
    suspend fun upsertDamageTypes(types: List<OfflineDamageTypeEntity>)

    @Query(
        """
        SELECT * FROM offline_damage_types
        WHERE projectServerId = :projectServerId
        ORDER BY COALESCE(name, title) COLLATE NOCASE
        """
    )
    suspend fun getDamageTypes(projectServerId: Long): List<OfflineDamageTypeEntity>

    @Query("SELECT MAX(fetchedAt) FROM offline_damage_types WHERE projectServerId = :projectServerId")
    suspend fun getLatestDamageTypesFetchedAt(projectServerId: Long): Date?

    @Query("DELETE FROM offline_damage_types WHERE projectServerId = :projectServerId")
    suspend fun clearDamageTypes(projectServerId: Long)

    @Upsert
    suspend fun upsertDamageCauses(causes: List<OfflineDamageCauseEntity>)

    @Query(
        """
        SELECT * FROM offline_damage_causes
        WHERE projectServerId = :projectServerId
        ORDER BY COALESCE(name, '') COLLATE NOCASE
        """
    )
    suspend fun getDamageCauses(projectServerId: Long): List<OfflineDamageCauseEntity>

    @Query("SELECT MAX(fetchedAt) FROM offline_damage_causes WHERE projectServerId = :projectServerId")
    suspend fun getLatestDamageCausesFetchedAt(projectServerId: Long): Date?

    @Query("DELETE FROM offline_damage_causes WHERE projectServerId = :projectServerId")
    suspend fun clearDamageCauses(projectServerId: Long)
    // endregion

    // region Atmospheric Logs
    @Upsert
    suspend fun upsertAtmosphericLogs(logs: List<OfflineAtmosphericLogEntity>)

    @Query("SELECT * FROM offline_atmospheric_logs WHERE projectId = :projectId AND isDeleted = 0 ORDER BY date DESC")
    fun observeAtmosphericLogsForProject(projectId: Long): Flow<List<OfflineAtmosphericLogEntity>>

    @Query("SELECT * FROM offline_atmospheric_logs WHERE roomId = :roomId AND isDeleted = 0 ORDER BY date DESC")
    fun observeAtmosphericLogsForRoom(roomId: Long): Flow<List<OfflineAtmosphericLogEntity>>

    @Query("UPDATE offline_atmospheric_logs SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateAtmosphericLogRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("UPDATE offline_atmospheric_logs SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markAtmosphericLogsDeleted(serverIds: List<Long>)

    @Query("DELETE FROM offline_atmospheric_logs WHERE roomId = :roomId")
    suspend fun deleteAtmosphericLogsByRoomId(roomId: Long): Int

    @Query("UPDATE offline_atmospheric_logs SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markAtmosphericLogsDeletedByProject(projectId: Long)

    @Query("SELECT * FROM offline_atmospheric_logs WHERE uuid = :uuid LIMIT 1")
    suspend fun getAtmosphericLogByUuid(uuid: String): OfflineAtmosphericLogEntity?

    @Query("SELECT * FROM offline_atmospheric_logs WHERE logId = :logId LIMIT 1")
    suspend fun getAtmosphericLog(logId: Long): OfflineAtmosphericLogEntity?
    // endregion

    // region Photos
    @Upsert
    suspend fun upsertPhotos(photos: List<OfflinePhotoEntity>)

    @Query("SELECT * FROM offline_photos WHERE projectId = :projectId AND isDeleted = 0 ORDER BY capturedAt DESC")
    fun observePhotosForProject(projectId: Long): Flow<List<OfflinePhotoEntity>>

    @Query("SELECT * FROM offline_photos WHERE roomId = :roomId AND isDeleted = 0 ORDER BY capturedAt DESC")
    fun observePhotosForRoom(roomId: Long): Flow<List<OfflinePhotoEntity>>

    @Query(
        """
        SELECT * FROM offline_photos
        WHERE roomId = :roomId
          AND isDeleted = 0
        ORDER BY COALESCE(capturedAt, createdAt) DESC, photoId DESC
        """
    )
    fun pagingPhotosForRoom(roomId: Long): PagingSource<Int, OfflinePhotoEntity>

    @Query(
        """
        SELECT * FROM offline_photos
        WHERE roomId = :roomId
          AND isDeleted = 0
        ORDER BY COALESCE(capturedAt, createdAt) DESC, photoId DESC
        """
    )
    suspend fun getPhotosForRoomSnapshot(roomId: Long): List<OfflinePhotoEntity>

    @Query("SELECT * FROM offline_photos WHERE serverId = :serverId LIMIT 1")
    suspend fun getPhotoByServerId(serverId: Long): OfflinePhotoEntity?

    @Query("SELECT * FROM offline_photos WHERE photoId = :photoId LIMIT 1")
    fun observePhoto(photoId: Long): Flow<OfflinePhotoEntity?>

    @Query("SELECT * FROM offline_photos WHERE photoId = :photoId LIMIT 1")
    suspend fun getPhotoById(photoId: Long): OfflinePhotoEntity?

    @Query("UPDATE offline_photos SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migratePhotoRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("DELETE FROM offline_photos WHERE roomId = :roomId")
    suspend fun deletePhotosByRoomId(roomId: Long): Int

    @Query(
        """
        DELETE FROM offline_photos
        WHERE projectId = :projectId
          AND roomId = :roomId
          AND serverId IS NULL
          AND uploadStatus = 'local_pending'
          AND isDeleted = 0
          AND LOWER(fileName) = LOWER(:fileName)
        """
    )
    suspend fun deleteLocalPendingRoomPhoto(
        projectId: Long,
        roomId: Long,
        fileName: String
    ): Int

    @Query(
        """
        SELECT * FROM offline_photos
        WHERE projectId = :projectId
          AND isDeleted = 1
          AND isDirty = 1
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getPendingPhotoDeletions(projectId: Long): List<OfflinePhotoEntity>

    /**
     * Find photos where the roomId references a room that doesn't exist in the photo's project.
     * These are orphaned/mismatched photos that need cleanup.
     */
    @Query(
        """
        SELECT p.* FROM offline_photos p
        WHERE p.isDeleted = 0
          AND p.roomId IS NOT NULL
          AND NOT EXISTS (
            SELECT 1 FROM offline_rooms r
            WHERE r.isDeleted = 0
              AND (r.roomId = p.roomId OR r.serverId = p.roomId)
              AND r.projectId = p.projectId
          )
        """
    )
    suspend fun getPhotosWithMismatchedRoomIds(): List<OfflinePhotoEntity>

    /**
     * Reassign orphaned photos to project level by clearing their roomId.
     * This is safer than deleting - photos remain accessible at the project level
     * and users can manually reassign them to the correct room.
     * Returns the count of photos reassigned.
     */
    @Query(
        """
        UPDATE offline_photos
        SET roomId = NULL
        WHERE isDeleted = 0
          AND isDirty = 0
          AND roomId IS NOT NULL
          AND NOT EXISTS (
            SELECT 1 FROM offline_rooms r
            WHERE r.isDeleted = 0
              AND (r.roomId = offline_photos.roomId OR r.serverId = offline_photos.roomId)
              AND r.projectId = offline_photos.projectId
          )
        """
    )
    suspend fun reassignMismatchedPhotosToProject(): Int

    @Query(
        """
        SELECT * FROM offline_photos 
        WHERE isDeleted = 0
          AND remoteUrl IS NOT NULL
          AND (cacheStatus = :pending OR cacheStatus = :failed)
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getPhotosNeedingCache(
        pending: PhotoCacheStatus = PhotoCacheStatus.PENDING,
        failed: PhotoCacheStatus = PhotoCacheStatus.FAILED,
        limit: Int = 25
    ): List<OfflinePhotoEntity>

    @Query(
        """
        SELECT * FROM offline_photos
        WHERE cacheStatus = :status
          AND isDeleted = 0
          AND cachedOriginalPath IS NOT NULL
        ORDER BY COALESCE(lastAccessedAt, updatedAt) DESC
        """
    )
    suspend fun getCachedPhotos(
        status: PhotoCacheStatus = PhotoCacheStatus.READY
    ): List<OfflinePhotoEntity>

    @Query(
        """
        SELECT
            roomId,
            CAST(COUNT(*) AS INTEGER) AS photoCount,
            (
                SELECT COALESCE(
                    NULLIF(p2.thumbnailUrl, ''),
                    NULLIF(p2.remoteUrl, '')
                )
                FROM offline_photos p2
                WHERE p2.roomId = p.roomId
                  AND p2.isDeleted = 0
                ORDER BY p2.capturedAt DESC, p2.photoId DESC
                LIMIT 1
            ) AS latestThumbnailUrl
        FROM offline_photos p
        WHERE p.projectId = :projectId
          AND p.isDeleted = 0
        GROUP BY roomId
        """
    )
    fun observeRoomPhotoSummaries(projectId: Long): Flow<List<RoomPhotoSummary>>

    @Query("UPDATE offline_photos SET cacheStatus = :status, lastAccessedAt = :timestamp WHERE photoId = :photoId")
    suspend fun updatePhotoCacheStatus(
        photoId: Long,
        status: PhotoCacheStatus,
        timestamp: Date
    )

    @Query(
        """
        UPDATE offline_photos 
        SET cacheStatus = :status, 
            cachedOriginalPath = :originalPath, 
            cachedThumbnailPath = :thumbnailPath,
            lastAccessedAt = :timestamp 
        WHERE photoId = :photoId
        """
    )
    suspend fun updatePhotoCachePaths(
        photoId: Long,
        status: PhotoCacheStatus,
        originalPath: String?,
        thumbnailPath: String?,
        timestamp: Date
    )

    @Query("UPDATE offline_photos SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markPhotosDeleted(serverIds: List<Long>)

    @Query("UPDATE offline_photos SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markPhotosDeletedByProject(projectId: Long)

    /** Returns photos with cached files for cleanup when deleting a project */
    @Query(
        """
        SELECT * FROM offline_photos
        WHERE projectId = :projectId
          AND (cachedOriginalPath IS NOT NULL OR cachedThumbnailPath IS NOT NULL)
        """
    )
    suspend fun getCachedPhotosForProject(projectId: Long): List<OfflinePhotoEntity>

    // region Room photo snapshots
    @Query("DELETE FROM offline_room_photo_snapshots WHERE roomId = :roomId")
    suspend fun clearRoomPhotoSnapshots(roomId: Long): Int

    @Query("DELETE FROM offline_room_photo_snapshots WHERE roomId IN (:roomIds)")
    suspend fun clearRoomPhotoSnapshots(roomIds: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoomPhotoSnapshots(snapshots: List<OfflineRoomPhotoSnapshotEntity>)

    @Query(
        """
        SELECT * FROM offline_room_photo_snapshots
        WHERE roomId = :roomId
        ORDER BY capturedOn DESC, photoId DESC
        """
    )
    fun pagingRoomPhotoSnapshots(roomId: Long): PagingSource<Int, OfflineRoomPhotoSnapshotEntity>
    // endregion

    @Query("SELECT COUNT(*) FROM offline_photos WHERE cacheStatus = :status AND isDeleted = 0")
    fun observePhotoCountByCacheStatus(status: PhotoCacheStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM offline_photos WHERE roomId = :roomId AND isDeleted = 0")
    fun observePhotoCountForRoom(roomId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM offline_photos WHERE roomId = :roomId AND isDeleted = 0")
    suspend fun getPhotoCountForRoom(roomId: Long): Int

    /**
     * Get photo counts for all rooms in a project in a single query.
     * More efficient than N+1 individual count queries.
     */
    @Query("""
        SELECT roomId, COUNT(*) as count
        FROM offline_photos
        WHERE projectId = :projectId AND isDeleted = 0 AND roomId IS NOT NULL
        GROUP BY roomId
    """)
    suspend fun getPhotoCountsByProject(projectId: Long): List<RoomPhotoCount>

    /**
     * Get room IDs that have pending photo deletions (photos marked isDeleted=1 but not yet synced).
     * Returns BOTH local roomId AND associated room serverId to handle ID mismatches after relink.
     * Used to avoid resurrecting deleted photos during mismatch sync.
     */
    @Query("""
        SELECT DISTINCT p.roomId AS roomId
        FROM offline_photos p
        WHERE p.projectId = :projectId AND p.isDeleted = 1 AND p.roomId IS NOT NULL
        UNION
        SELECT DISTINCT r.serverId AS roomId
        FROM offline_photos p
        INNER JOIN offline_rooms r ON p.roomId = r.roomId
        WHERE p.projectId = :projectId AND p.isDeleted = 1 AND p.roomId IS NOT NULL AND r.serverId IS NOT NULL
    """)
    suspend fun getRoomIdsWithPendingPhotoDeletions(projectId: Long): List<Long>

    /**
     * Get server IDs of photos pending deletion for a specific room.
     * Used to filter out these photos during sync to prevent resurrection.
     */
    @Query("""
        SELECT serverId
        FROM offline_photos
        WHERE roomId = :roomId AND isDeleted = 1 AND serverId IS NOT NULL
    """)
    suspend fun getPendingPhotoServerIdsForRoom(roomId: Long): List<Long>

    data class RoomPhotoCount(val roomId: Long, val count: Int)
    // endregion

    // region Albums
    @Upsert
    suspend fun upsertAlbums(albums: List<OfflineAlbumEntity>)

    @Upsert
    suspend fun upsertAlbumPhotos(albumPhotos: List<OfflineAlbumPhotoEntity>)

    @Query(
        """
        SELECT
            a.albumId,
            a.projectId,
            a.roomId,
            a.name,
            a.albumableType,
            a.albumableId,
            CAST(COALESCE(COUNT(DISTINCT ap.photoServerId), 0) AS INTEGER) AS photoCount,
            a.thumbnailUrl,
            a.syncStatus,
            a.syncVersion,
            a.isDirty,
            a.isDeleted,
            a.createdAt,
            a.updatedAt,
            a.lastSyncedAt
        FROM offline_albums a
        LEFT JOIN offline_album_photos ap ON a.albumId = ap.albumId
        WHERE a.projectId = :projectId AND a.isDeleted = 0
        GROUP BY a.albumId
        ORDER BY a.name
        """
    )
    fun observeAlbumsForProject(projectId: Long): Flow<List<OfflineAlbumEntity>>

    @Query(
        """
        SELECT
            a.albumId,
            a.projectId,
            a.roomId,
            a.name,
            a.albumableType,
            a.albumableId,
            CAST(COALESCE(COUNT(DISTINCT ap.photoServerId), 0) AS INTEGER) AS photoCount,
            a.thumbnailUrl,
            a.syncStatus,
            a.syncVersion,
            a.isDirty,
            a.isDeleted,
            a.createdAt,
            a.updatedAt,
            a.lastSyncedAt
        FROM offline_albums a
        LEFT JOIN offline_album_photos ap ON a.albumId = ap.albumId
        WHERE a.roomId = :roomId AND a.isDeleted = 0
        GROUP BY a.albumId
        ORDER BY a.name
        """
    )
    fun observeAlbumsForRoom(roomId: Long): Flow<List<OfflineAlbumEntity>>

    @Query(
        """
        SELECT p.* FROM offline_photos p
        INNER JOIN offline_album_photos ap ON p.serverId = ap.photoServerId
        WHERE ap.albumId = :albumId AND p.isDeleted = 0
        ORDER BY p.capturedAt DESC
        """
    )
    fun observePhotosForAlbum(albumId: Long): Flow<List<OfflinePhotoEntity>>

    @Query("UPDATE offline_albums SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateAlbumRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("DELETE FROM offline_album_photos WHERE albumId IN (SELECT albumId FROM offline_albums WHERE roomId = :roomId)")
    suspend fun deleteAlbumPhotosByRoomId(roomId: Long): Int

    @Query("UPDATE offline_albums SET isDeleted = 1 WHERE roomId = :roomId")
    suspend fun markAlbumsDeletedByRoomId(roomId: Long): Int

    @Query("DELETE FROM offline_album_photos WHERE albumId IN (SELECT albumId FROM offline_albums WHERE projectId = :projectId)")
    suspend fun deleteAlbumPhotosByProject(projectId: Long): Int

    @Query("UPDATE offline_albums SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markAlbumsDeletedByProject(projectId: Long): Int
    // endregion

    // region Equipment
    @Upsert
    suspend fun upsertEquipment(equipment: List<OfflineEquipmentEntity>)

    @Query("SELECT * FROM offline_equipment WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeEquipmentForProject(projectId: Long): Flow<List<OfflineEquipmentEntity>>

    @Query("SELECT * FROM offline_equipment WHERE roomId = :roomId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeEquipmentForRoom(roomId: Long): Flow<List<OfflineEquipmentEntity>>

    @Query("UPDATE offline_equipment SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateEquipmentRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("UPDATE offline_equipment SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markEquipmentDeleted(serverIds: List<Long>)

    @Query("UPDATE offline_equipment SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markEquipmentDeletedByProject(projectId: Long)
    // endregion

    @Query("SELECT * FROM offline_equipment WHERE equipmentId = :equipmentId LIMIT 1")
    suspend fun getEquipment(equipmentId: Long): OfflineEquipmentEntity?

    @Query("SELECT * FROM offline_equipment WHERE uuid = :uuid LIMIT 1")
    suspend fun getEquipmentByUuid(uuid: String): OfflineEquipmentEntity?

    @Query(
        """
        SELECT * FROM offline_equipment
        WHERE projectId = :projectId AND (isDirty = 1 OR syncStatus != :synced)
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getPendingEquipment(
        projectId: Long,
        synced: SyncStatus = SyncStatus.SYNCED
    ): List<OfflineEquipmentEntity>

    @Query("DELETE FROM offline_equipment WHERE roomId = :roomId")
    suspend fun deleteEquipmentByRoomId(roomId: Long): Int

    // region Moisture Logs
    @Upsert
    suspend fun upsertMoistureLogs(logs: List<OfflineMoistureLogEntity>)

    @Query("SELECT * FROM offline_moisture_logs WHERE projectId = :projectId AND isDeleted = 0 ORDER BY date DESC")
    fun observeMoistureLogsForProject(projectId: Long): Flow<List<OfflineMoistureLogEntity>>

    @Query("SELECT * FROM offline_moisture_logs WHERE roomId = :roomId AND isDeleted = 0 ORDER BY date DESC")
    fun observeMoistureLogsForRoom(roomId: Long): Flow<List<OfflineMoistureLogEntity>>

    @Query("SELECT * FROM offline_moisture_logs WHERE uuid = :uuid LIMIT 1")
    suspend fun getMoistureLogByUuid(uuid: String): OfflineMoistureLogEntity?

    @Query("UPDATE offline_moisture_logs SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateMoistureLogRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("DELETE FROM offline_moisture_logs WHERE roomId = :roomId")
    suspend fun deleteMoistureLogsByRoomId(roomId: Long): Int

    @Query(
        """
        SELECT * FROM offline_moisture_logs
        WHERE projectId = :projectId
          AND (isDirty = 1 OR syncStatus != :synced)
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getPendingMoistureLogs(
        projectId: Long,
        synced: SyncStatus = SyncStatus.SYNCED
    ): List<OfflineMoistureLogEntity>

    @Query("UPDATE offline_moisture_logs SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markMoistureLogsDeleted(serverIds: List<Long>)

    @Query("UPDATE offline_moisture_logs SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markMoistureLogsDeletedByProject(projectId: Long)
    // endregion

    // region Notes & Damages & Work Scopes
    @Upsert
    suspend fun upsertNotes(notes: List<OfflineNoteEntity>)

    @Query("SELECT * FROM offline_notes WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeNotesForProject(projectId: Long): Flow<List<OfflineNoteEntity>>

    @Query("SELECT * FROM offline_notes WHERE projectId = :projectId AND roomId = :roomId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeNotesForRoom(projectId: Long, roomId: Long): Flow<List<OfflineNoteEntity>>

    @Query("SELECT * FROM offline_notes WHERE uuid = :uuid LIMIT 1")
    suspend fun getNoteByUuid(uuid: String): OfflineNoteEntity?

    @Query("SELECT * FROM offline_notes WHERE projectId = :projectId AND (isDirty = 1 OR syncStatus != :synced)")
    suspend fun getPendingNotes(projectId: Long, synced: SyncStatus = SyncStatus.SYNCED): List<OfflineNoteEntity>

    @Query("UPDATE offline_notes SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateNoteRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("UPDATE offline_notes SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markNotesDeleted(serverIds: List<Long>)

    @Query("UPDATE offline_notes SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markNotesDeletedByProject(projectId: Long)

    @Query("DELETE FROM offline_notes WHERE roomId = :roomId")
    suspend fun deleteNotesByRoomId(roomId: Long): Int

    @Upsert
    suspend fun upsertDamages(damages: List<OfflineDamageEntity>)

    @Query("SELECT * FROM offline_damages WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeDamagesForProject(projectId: Long): Flow<List<OfflineDamageEntity>>

    @Query("UPDATE offline_damages SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateDamageRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("UPDATE offline_damages SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markDamagesDeleted(serverIds: List<Long>)

    @Query("UPDATE offline_damages SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markDamagesDeletedByProject(projectId: Long)

    @Query("DELETE FROM offline_damages WHERE roomId = :roomId")
    suspend fun deleteDamagesByRoomId(roomId: Long): Int

    @Upsert
    suspend fun upsertWorkScopes(scopes: List<OfflineWorkScopeEntity>)

    @Query("SELECT * FROM offline_work_scopes WHERE workScopeId = :id LIMIT 1")
    suspend fun getWorkScopeById(id: Long): OfflineWorkScopeEntity?

    @Query("SELECT * FROM offline_work_scopes WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeWorkScopesForProject(projectId: Long): Flow<List<OfflineWorkScopeEntity>>

    @Query("UPDATE offline_work_scopes SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markWorkScopesDeleted(serverIds: List<Long>)

    @Query("UPDATE offline_work_scopes SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markWorkScopesDeletedByProject(projectId: Long)

    @Query("UPDATE offline_work_scopes SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateWorkScopeRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("DELETE FROM offline_work_scopes WHERE roomId = :roomId")
    suspend fun deleteWorkScopesByRoomId(roomId: Long): Int
    // endregion

    // region Materials
    @Upsert
    suspend fun upsertMaterials(materials: List<OfflineMaterialEntity>)

    @Query("SELECT * FROM offline_materials ORDER BY name")
    fun observeMaterials(): Flow<List<OfflineMaterialEntity>>

    @Query("SELECT * FROM offline_materials WHERE uuid = :uuid LIMIT 1")
    suspend fun getMaterialByUuid(uuid: String): OfflineMaterialEntity?

    @Query("SELECT * FROM offline_materials WHERE materialId = :materialId LIMIT 1")
    suspend fun getMaterial(materialId: Long): OfflineMaterialEntity?
    // endregion

    // region Company & Users & Properties
    @Upsert
    suspend fun upsertCompany(company: OfflineCompanyEntity)

    @Upsert
    suspend fun upsertUsers(users: List<OfflineUserEntity>)

    @Upsert
    suspend fun upsertProperty(property: OfflinePropertyEntity)

    @Query("DELETE FROM offline_properties WHERE propertyId = :propertyId")
    suspend fun deleteProperty(propertyId: Long)

    @Query("SELECT * FROM offline_users WHERE companyId = :companyId")
    fun observeUsersForCompany(companyId: Long): Flow<List<OfflineUserEntity>>

    @Query("SELECT * FROM offline_properties WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getProperty(propertyId: Long): OfflinePropertyEntity?

    @Query("SELECT * FROM offline_properties WHERE serverId = :serverId LIMIT 1")
    suspend fun getPropertyByServerId(serverId: Long): OfflinePropertyEntity?

    @Query(
        """
        SELECT address FROM (
            SELECT address, MAX(lastUsed) AS lastUsed FROM (
                SELECT TRIM(address) AS address, MAX(updatedAt) AS lastUsed
                FROM offline_properties
                WHERE address IS NOT NULL
                  AND TRIM(address) != ''
                GROUP BY TRIM(address)
                UNION ALL
                SELECT TRIM(
                    CASE
                        WHEN addressLine2 IS NOT NULL AND TRIM(addressLine2) != '' THEN addressLine1 || ', ' || addressLine2
                        ELSE addressLine1
                    END
                ) AS address,
                MAX(updatedAt) AS lastUsed
                FROM offline_projects
                WHERE addressLine1 IS NOT NULL
                  AND TRIM(addressLine1) != ''
                  AND isDeleted = 0
                GROUP BY
                    CASE
                        WHEN addressLine2 IS NOT NULL AND TRIM(addressLine2) != '' THEN addressLine1 || ', ' || addressLine2
                        ELSE addressLine1
                    END
            )
            WHERE address IS NOT NULL AND address != ''
            GROUP BY address
        )
        ORDER BY lastUsed DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentAddresses(limit: Int): List<String>
    // endregion

    // region Sync Queue
    @Upsert
    suspend fun upsertSyncOperation(operation: OfflineSyncQueueEntity)

    @Upsert
    suspend fun upsertSyncOperations(operations: List<OfflineSyncQueueEntity>)

    @Query("SELECT * FROM offline_sync_queue WHERE status = :status ORDER BY priority ASC, createdAt ASC")
    fun observeSyncOperationsByStatus(status: SyncStatus): Flow<List<OfflineSyncQueueEntity>>

    @Query(
        """
        SELECT * FROM offline_sync_queue
        WHERE status = :status
          AND (scheduledAt IS NULL OR scheduledAt <= :now)
        ORDER BY priority ASC, createdAt ASC
        """
    )
    suspend fun getSyncOperationsByStatus(status: SyncStatus, now: Long): List<OfflineSyncQueueEntity>

    @Query("SELECT * FROM offline_sync_queue WHERE status = :status ORDER BY priority ASC, createdAt ASC")
    suspend fun getSyncOperationsByStatus(status: SyncStatus): List<OfflineSyncQueueEntity>

    @Query(
        """
        SELECT COUNT(*) FROM offline_sync_queue
        WHERE status = :status
          AND scheduledAt IS NOT NULL
          AND scheduledAt <= :now
        """
    )
    suspend fun countDueScheduledOperations(status: SyncStatus, now: Long): Int

    @Query(
        """
        SELECT * FROM offline_sync_queue
        WHERE entityType = :entityType
          AND entityId = :entityId
          AND status = :status
        LIMIT 1
        """
    )
    suspend fun getSyncOperationForEntity(
        entityType: String,
        entityId: Long,
        status: SyncStatus = SyncStatus.PENDING
    ): OfflineSyncQueueEntity?

    @Query("DELETE FROM offline_sync_queue WHERE operationId = :operationId")
    suspend fun deleteSyncOperation(operationId: String)

    @Query("DELETE FROM offline_sync_queue WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteSyncOperationsForEntity(entityType: String, entityId: Long)

    // Typed sync queue deletion methods - must filter by BOTH entityType AND entityId
    // to avoid collisions across tables (e.g., projectId=5 vs photoId=5 are different entities)

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'project' AND entityId IN (:projectIds)")
    suspend fun deleteSyncOpsForProjects(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'property' AND entityId IN (SELECT propertyId FROM offline_properties WHERE propertyId IN (:propertyIds))")
    suspend fun deleteSyncOpsForProperties(propertyIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'location' AND entityId IN (SELECT locationId FROM offline_locations WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForLocationsByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'room' AND entityId IN (SELECT roomId FROM offline_rooms WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForRoomsByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'room' AND entityId IN (SELECT roomId FROM offline_rooms WHERE locationId = :locationId)")
    suspend fun deleteSyncOpsForRoomsByLocation(locationId: Long): Int

    @Query("SELECT roomId FROM offline_rooms WHERE locationId = :locationId")
    suspend fun getRoomIdsForLocation(locationId: Long): List<Long>

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'photo' AND entityId IN (SELECT photoId FROM offline_photos WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForPhotosByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'note' AND entityId IN (SELECT noteId FROM offline_notes WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForNotesByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'equipment' AND entityId IN (SELECT equipmentId FROM offline_equipment WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForEquipmentByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'atmospheric_log' AND entityId IN (SELECT logId FROM offline_atmospheric_logs WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForAtmosphericLogsByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'moisture_log' AND entityId IN (SELECT logId FROM offline_moisture_logs WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForMoistureLogsByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'damage' AND entityId IN (SELECT damageId FROM offline_damages WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForDamagesByProject(projectIds: List<Long>): Int

    @Query("DELETE FROM offline_sync_queue WHERE entityType = 'work_scope' AND entityId IN (SELECT workScopeId FROM offline_work_scopes WHERE projectId IN (:projectIds))")
    suspend fun deleteSyncOpsForWorkScopesByProject(projectIds: List<Long>): Int

    /** Counts unsynced operations (PENDING or FAILED) for a project, its property, and children */
    @Query("""
        SELECT COUNT(*) FROM offline_sync_queue WHERE status IN ('PENDING', 'FAILED') AND (
            (entityType = 'project' AND entityId = :projectId) OR
            (entityType = 'property' AND entityId = (SELECT propertyId FROM offline_projects WHERE projectId = :projectId)) OR
            (entityType = 'photo' AND entityId IN (SELECT photoId FROM offline_photos WHERE projectId = :projectId)) OR
            (entityType = 'note' AND entityId IN (SELECT noteId FROM offline_notes WHERE projectId = :projectId)) OR
            (entityType = 'room' AND entityId IN (SELECT roomId FROM offline_rooms WHERE projectId = :projectId)) OR
            (entityType = 'location' AND entityId IN (SELECT locationId FROM offline_locations WHERE projectId = :projectId)) OR
            (entityType = 'equipment' AND entityId IN (SELECT equipmentId FROM offline_equipment WHERE projectId = :projectId)) OR
            (entityType = 'damage' AND entityId IN (SELECT damageId FROM offline_damages WHERE projectId = :projectId)) OR
            (entityType = 'atmospheric_log' AND entityId IN (SELECT logId FROM offline_atmospheric_logs WHERE projectId = :projectId)) OR
            (entityType = 'moisture_log' AND entityId IN (SELECT logId FROM offline_moisture_logs WHERE projectId = :projectId)) OR
            (entityType = 'work_scope' AND entityId IN (SELECT workScopeId FROM offline_work_scopes WHERE projectId = :projectId))
        )
    """)
    suspend fun countPendingSyncOpsForProject(projectId: Long): Int
    // endregion

    // region Conflicts
    @Upsert
    suspend fun upsertConflict(conflict: OfflineConflictResolutionEntity)

    @Query("SELECT * FROM offline_conflicts ORDER BY detectedAt DESC")
    fun observeConflicts(): Flow<List<OfflineConflictResolutionEntity>>

    @Query("SELECT * FROM offline_conflicts WHERE resolvedAt IS NULL ORDER BY detectedAt DESC")
    fun observeUnresolvedConflicts(): Flow<List<OfflineConflictResolutionEntity>>

    @Query("SELECT * FROM offline_conflicts WHERE conflictId = :conflictId LIMIT 1")
    suspend fun getConflict(conflictId: String): OfflineConflictResolutionEntity?

    @Query("SELECT COUNT(*) FROM offline_conflicts WHERE resolvedAt IS NULL")
    fun observeUnresolvedConflictCount(): Flow<Int>

    @Query("DELETE FROM offline_conflicts WHERE conflictId = :conflictId")
    suspend fun deleteConflict(conflictId: String)

    @Query("DELETE FROM offline_conflicts WHERE resolvedAt IS NOT NULL")
    suspend fun deleteResolvedConflicts()
    // endregion

    // region Support Categories
    @Upsert
    suspend fun upsertSupportCategories(categories: List<OfflineSupportCategoryEntity>)

    @Query("SELECT * FROM offline_support_categories ORDER BY name")
    fun observeSupportCategories(): Flow<List<OfflineSupportCategoryEntity>>

    @Query("SELECT * FROM offline_support_categories ORDER BY name")
    suspend fun getSupportCategories(): List<OfflineSupportCategoryEntity>

    @Query("DELETE FROM offline_support_categories")
    suspend fun clearSupportCategories()
    // endregion

    // region Support Conversations
    @Upsert
    suspend fun upsertSupportConversations(conversations: List<OfflineSupportConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSupportConversation(conversation: OfflineSupportConversationEntity): Long

    @Query("SELECT * FROM offline_support_conversations WHERE isDeleted = 0 ORDER BY COALESCE(lastMessageAt, createdAt) DESC")
    fun observeSupportConversations(): Flow<List<OfflineSupportConversationEntity>>

    @Query("SELECT * FROM offline_support_conversations WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getSupportConversation(conversationId: Long): OfflineSupportConversationEntity?

    @Query("SELECT * FROM offline_support_conversations WHERE serverId = :serverId LIMIT 1")
    suspend fun getSupportConversationByServerId(serverId: Long): OfflineSupportConversationEntity?

    @Query("SELECT * FROM offline_support_conversations WHERE uuid = :uuid LIMIT 1")
    suspend fun getSupportConversationByUuid(uuid: String): OfflineSupportConversationEntity?

    @Query("UPDATE offline_support_conversations SET status = :status, updatedAt = :updatedAt WHERE conversationId = :conversationId")
    suspend fun updateSupportConversationStatus(conversationId: Long, status: String, updatedAt: Date)

    @Query("UPDATE offline_support_conversations SET serverId = :serverId, syncStatus = :syncStatus, lastSyncedAt = :lastSyncedAt WHERE conversationId = :conversationId")
    suspend fun updateSupportConversationServerId(
        conversationId: Long,
        serverId: Long,
        syncStatus: SyncStatus,
        lastSyncedAt: Date
    )

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM offline_support_conversations WHERE isDeleted = 0")
    fun observeTotalSupportUnreadCount(): Flow<Int>
    // endregion

    // region Support Messages
    @Upsert
    suspend fun upsertSupportMessages(messages: List<OfflineSupportMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSupportMessage(message: OfflineSupportMessageEntity): Long

    @Query("SELECT * FROM offline_support_messages WHERE conversationId = :conversationId AND isDeleted = 0 ORDER BY createdAt ASC")
    fun observeSupportMessages(conversationId: Long): Flow<List<OfflineSupportMessageEntity>>

    @Query("SELECT * FROM offline_support_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getSupportMessage(messageId: Long): OfflineSupportMessageEntity?

    @Query("SELECT * FROM offline_support_messages WHERE uuid = :uuid LIMIT 1")
    suspend fun getSupportMessageByUuid(uuid: String): OfflineSupportMessageEntity?

    @Query("UPDATE offline_support_messages SET isRead = 1 WHERE conversationId = :conversationId AND isRead = 0")
    suspend fun markSupportMessagesAsRead(conversationId: Long)

    @Query("UPDATE offline_support_messages SET serverId = :serverId, syncStatus = :syncStatus, lastSyncedAt = :lastSyncedAt WHERE messageId = :messageId")
    suspend fun updateSupportMessageServerId(
        messageId: Long,
        serverId: Long,
        syncStatus: SyncStatus,
        lastSyncedAt: Date
    )

    @Query("DELETE FROM offline_support_messages WHERE conversationId = :conversationId")
    suspend fun deleteSupportMessagesForConversation(conversationId: Long)
    // endregion

    // region Support Message Attachments
    @Upsert
    suspend fun upsertSupportMessageAttachments(attachments: List<OfflineSupportMessageAttachmentEntity>)

    @Query("SELECT * FROM offline_support_message_attachments WHERE messageId = :messageId ORDER BY attachmentId")
    suspend fun getAttachmentsForSupportMessage(messageId: Long): List<OfflineSupportMessageAttachmentEntity>
    // endregion
}
