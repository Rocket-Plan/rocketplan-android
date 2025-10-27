package com.example.rocketplan_android.data.local.dao

import androidx.room.Dao
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
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
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
    // endregion

    // region Locations
    @Upsert
    suspend fun upsertLocations(locations: List<OfflineLocationEntity>)

    @Query("SELECT * FROM offline_locations WHERE projectId = :projectId AND isDeleted = 0 ORDER BY title")
    fun observeLocationsForProject(projectId: Long): Flow<List<OfflineLocationEntity>>
    // endregion

    // region Rooms
    @Upsert
    suspend fun upsertRooms(rooms: List<OfflineRoomEntity>)

    @Query("SELECT * FROM offline_rooms WHERE projectId = :projectId AND isDeleted = 0 ORDER BY title")
    fun observeRoomsForProject(projectId: Long): Flow<List<OfflineRoomEntity>>

    @Query("SELECT * FROM offline_rooms WHERE roomId = :roomId LIMIT 1")
    suspend fun getRoom(roomId: Long): OfflineRoomEntity?
    // endregion

    // region Atmospheric Logs
    @Upsert
    suspend fun upsertAtmosphericLogs(logs: List<OfflineAtmosphericLogEntity>)

    @Query("SELECT * FROM offline_atmospheric_logs WHERE projectId = :projectId AND isDeleted = 0 ORDER BY date DESC")
    fun observeAtmosphericLogsForProject(projectId: Long): Flow<List<OfflineAtmosphericLogEntity>>

    @Query("SELECT * FROM offline_atmospheric_logs WHERE roomId = :roomId AND isDeleted = 0 ORDER BY date DESC")
    fun observeAtmosphericLogsForRoom(roomId: Long): Flow<List<OfflineAtmosphericLogEntity>>
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

    @Query("SELECT COUNT(*) FROM offline_photos WHERE cacheStatus = :status AND isDeleted = 0")
    fun observePhotoCountByCacheStatus(status: PhotoCacheStatus): Flow<Int>
    // endregion

    // region Albums
    @Upsert
    suspend fun upsertAlbums(albums: List<OfflineAlbumEntity>)

    @Upsert
    suspend fun upsertAlbumPhotos(albumPhotos: List<OfflineAlbumPhotoEntity>)

    @Query("SELECT * FROM offline_albums WHERE projectId = :projectId ORDER BY name")
    fun observeAlbumsForProject(projectId: Long): Flow<List<OfflineAlbumEntity>>

    @Query("SELECT * FROM offline_albums WHERE roomId = :roomId ORDER BY name")
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
    // endregion

    // region Equipment
    @Upsert
    suspend fun upsertEquipment(equipment: List<OfflineEquipmentEntity>)

    @Query("SELECT * FROM offline_equipment WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeEquipmentForProject(projectId: Long): Flow<List<OfflineEquipmentEntity>>

    @Query("SELECT * FROM offline_equipment WHERE roomId = :roomId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeEquipmentForRoom(roomId: Long): Flow<List<OfflineEquipmentEntity>>
    // endregion

    // region Moisture Logs
    @Upsert
    suspend fun upsertMoistureLogs(logs: List<OfflineMoistureLogEntity>)

    @Query("SELECT * FROM offline_moisture_logs WHERE projectId = :projectId AND isDeleted = 0 ORDER BY date DESC")
    fun observeMoistureLogsForProject(projectId: Long): Flow<List<OfflineMoistureLogEntity>>

    @Query("SELECT * FROM offline_moisture_logs WHERE roomId = :roomId AND isDeleted = 0 ORDER BY date DESC")
    fun observeMoistureLogsForRoom(roomId: Long): Flow<List<OfflineMoistureLogEntity>>
    // endregion

    // region Notes & Damages & Work Scopes
    @Upsert
    suspend fun upsertNotes(notes: List<OfflineNoteEntity>)

    @Query("SELECT * FROM offline_notes WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeNotesForProject(projectId: Long): Flow<List<OfflineNoteEntity>>

    @Upsert
    suspend fun upsertDamages(damages: List<OfflineDamageEntity>)

    @Query("SELECT * FROM offline_damages WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeDamagesForProject(projectId: Long): Flow<List<OfflineDamageEntity>>

    @Upsert
    suspend fun upsertWorkScopes(scopes: List<OfflineWorkScopeEntity>)

    @Query("SELECT * FROM offline_work_scopes WHERE projectId = :projectId AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun observeWorkScopesForProject(projectId: Long): Flow<List<OfflineWorkScopeEntity>>
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
