package com.example.rocketplan_android.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineAtmosphericLogEntity
import com.example.rocketplan_android.data.local.entity.OfflineCompanyEntity
import com.example.rocketplan_android.data.local.entity.OfflineConflictResolutionEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineEquipmentEntity
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
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.local.model.RoomPhotoSummary
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface OfflineDao {

    // region Projects
    @Upsert
    suspend fun upsertProject(project: OfflineProjectEntity)

    @Upsert
    suspend fun upsertProjects(projects: List<OfflineProjectEntity>)

    @Query("SELECT * FROM offline_projects WHERE isDeleted = 0")
    fun observeProjects(): Flow<List<OfflineProjectEntity>>

    @Query("SELECT * FROM offline_projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getProject(projectId: Long): OfflineProjectEntity?

    @Query("SELECT * FROM offline_projects WHERE isDeleted = 0")
    suspend fun getProjectsOnce(): List<OfflineProjectEntity>

    @Query("SELECT COUNT(*) FROM offline_projects")
    suspend fun countProjects(): Int

    @Query("UPDATE offline_projects SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markProjectsDeleted(serverIds: List<Long>)

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

    @Query("UPDATE offline_locations SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markLocationsDeleted(serverIds: List<Long>)
    // endregion

    // region Rooms
    @Upsert
    suspend fun upsertRooms(rooms: List<OfflineRoomEntity>)

    @Query("SELECT * FROM offline_rooms WHERE projectId = :projectId AND isDeleted = 0 ORDER BY title")
    fun observeRoomsForProject(projectId: Long): Flow<List<OfflineRoomEntity>>
    @Query("SELECT roomId FROM offline_rooms WHERE projectId = :projectId")
    suspend fun getRoomIdsForProject(projectId: Long): List<Long>

    @Query("UPDATE offline_rooms SET isDeleted = 1 WHERE projectId = :projectId")
    suspend fun markRoomsDeletedByProject(projectId: Long)

    @Query("SELECT * FROM offline_rooms WHERE roomId = :roomId LIMIT 1")
    suspend fun getRoom(roomId: Long): OfflineRoomEntity?

    @Query("SELECT * FROM offline_rooms WHERE serverId = :serverId LIMIT 1")
    suspend fun getRoomByServerId(serverId: Long): OfflineRoomEntity?

    @Query("SELECT * FROM offline_rooms WHERE uuid = :uuid LIMIT 1")
    suspend fun getRoomByUuid(uuid: String): OfflineRoomEntity?

    @Query("SELECT * FROM offline_rooms WHERE serverId IS NOT NULL")
    suspend fun getRoomsWithServerId(): List<OfflineRoomEntity>

    @Query("SELECT MAX(updatedAt) FROM offline_rooms WHERE locationId = :locationId AND isDeleted = 0")
    suspend fun getLatestRoomUpdatedAt(locationId: Long): Date?

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
     * Delete photos where the roomId references a room in a different project.
     * Returns the count of deleted photos.
     */
    @Query(
        """
        DELETE FROM offline_photos
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
    suspend fun deleteMismatchedPhotos(): Int

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
        ORDER BY orderIndex ASC
        """
    )
    fun pagingRoomPhotoSnapshots(roomId: Long): PagingSource<Int, OfflineRoomPhotoSnapshotEntity>
    // endregion

    @Query("SELECT COUNT(*) FROM offline_photos WHERE cacheStatus = :status AND isDeleted = 0")
    fun observePhotoCountByCacheStatus(status: PhotoCacheStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM offline_photos WHERE roomId = :roomId AND isDeleted = 0")
    fun observePhotoCountForRoom(roomId: Long): Flow<Int>
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
            a.createdAt,
            a.updatedAt,
            a.lastSyncedAt
        FROM offline_albums a
        LEFT JOIN offline_album_photos ap ON a.albumId = ap.albumId
        WHERE a.projectId = :projectId
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
            a.createdAt,
            a.updatedAt,
            a.lastSyncedAt
        FROM offline_albums a
        LEFT JOIN offline_album_photos ap ON a.albumId = ap.albumId
        WHERE a.roomId = :roomId
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

    @Query("DELETE FROM offline_albums WHERE roomId = :roomId")
    suspend fun deleteAlbumsByRoomId(roomId: Long): Int
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
    // endregion

    @Query("DELETE FROM offline_equipment WHERE roomId = :roomId")
    suspend fun deleteEquipmentByRoomId(roomId: Long): Int

    // region Moisture Logs
    @Upsert
    suspend fun upsertMoistureLogs(logs: List<OfflineMoistureLogEntity>)

    @Query("SELECT * FROM offline_moisture_logs WHERE projectId = :projectId AND isDeleted = 0 ORDER BY date DESC")
    fun observeMoistureLogsForProject(projectId: Long): Flow<List<OfflineMoistureLogEntity>>

    @Query("SELECT * FROM offline_moisture_logs WHERE roomId = :roomId AND isDeleted = 0 ORDER BY date DESC")
    fun observeMoistureLogsForRoom(roomId: Long): Flow<List<OfflineMoistureLogEntity>>

    @Query("UPDATE offline_moisture_logs SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateMoistureLogRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("DELETE FROM offline_moisture_logs WHERE roomId = :roomId")
    suspend fun deleteMoistureLogsByRoomId(roomId: Long): Int
    // endregion

    // region Notes & Damages & Work Scopes
    @Upsert
    suspend fun upsertNotes(notes: List<OfflineNoteEntity>)

    @Query("SELECT * FROM offline_notes WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeNotesForProject(projectId: Long): Flow<List<OfflineNoteEntity>>

    @Query("SELECT * FROM offline_notes WHERE projectId = :projectId AND roomId = :roomId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeNotesForRoom(projectId: Long, roomId: Long): Flow<List<OfflineNoteEntity>>

    @Query("SELECT * FROM offline_notes WHERE projectId = :projectId AND (isDirty = 1 OR syncStatus != :synced)")
    suspend fun getPendingNotes(projectId: Long, synced: SyncStatus = SyncStatus.SYNCED): List<OfflineNoteEntity>

    @Query("UPDATE offline_notes SET roomId = :newRoomId WHERE roomId = :oldRoomId")
    suspend fun migrateNoteRoomIds(oldRoomId: Long, newRoomId: Long): Int

    @Query("UPDATE offline_notes SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markNotesDeleted(serverIds: List<Long>)

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

    @Query("DELETE FROM offline_damages WHERE roomId = :roomId")
    suspend fun deleteDamagesByRoomId(roomId: Long): Int

    @Upsert
    suspend fun upsertWorkScopes(scopes: List<OfflineWorkScopeEntity>)

    @Query("SELECT * FROM offline_work_scopes WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeWorkScopesForProject(projectId: Long): Flow<List<OfflineWorkScopeEntity>>

    @Query("UPDATE offline_work_scopes SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0")
    suspend fun markWorkScopesDeleted(serverIds: List<Long>)

    @Query("DELETE FROM offline_work_scopes WHERE roomId = :roomId")
    suspend fun deleteWorkScopesByRoomId(roomId: Long): Int
    // endregion

    // region Materials
    @Upsert
    suspend fun upsertMaterials(materials: List<OfflineMaterialEntity>)

    @Query("SELECT * FROM offline_materials ORDER BY name")
    fun observeMaterials(): Flow<List<OfflineMaterialEntity>>
    // endregion

    // region Company & Users & Properties
    @Upsert
    suspend fun upsertCompany(company: OfflineCompanyEntity)

    @Upsert
    suspend fun upsertUsers(users: List<OfflineUserEntity>)

    @Upsert
    suspend fun upsertProperty(property: OfflinePropertyEntity)

    @Query("SELECT * FROM offline_users WHERE companyId = :companyId")
    fun observeUsersForCompany(companyId: Long): Flow<List<OfflineUserEntity>>

    @Query("SELECT * FROM offline_properties WHERE propertyId = :propertyId LIMIT 1")
    suspend fun getProperty(propertyId: Long): OfflinePropertyEntity?
    // endregion

    // region Sync Queue
    @Upsert
    suspend fun upsertSyncOperation(operation: OfflineSyncQueueEntity)

    @Upsert
    suspend fun upsertSyncOperations(operations: List<OfflineSyncQueueEntity>)

    @Query("SELECT * FROM offline_sync_queue WHERE status = :status ORDER BY priority ASC, createdAt ASC")
    fun observeSyncOperationsByStatus(status: SyncStatus): Flow<List<OfflineSyncQueueEntity>>

    @Query("DELETE FROM offline_sync_queue WHERE operationId = :operationId")
    suspend fun deleteSyncOperation(operationId: String)
    // endregion

    // region Conflicts
    @Upsert
    suspend fun upsertConflict(conflict: OfflineConflictResolutionEntity)

    @Query("SELECT * FROM offline_conflicts ORDER BY detectedAt DESC")
    fun observeConflicts(): Flow<List<OfflineConflictResolutionEntity>>

    @Query("DELETE FROM offline_conflicts WHERE conflictId = :conflictId")
    suspend fun deleteConflict(conflictId: String)
    // endregion
}
