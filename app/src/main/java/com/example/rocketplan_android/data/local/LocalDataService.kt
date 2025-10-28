package com.example.rocketplan_android.data.local

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.dao.OfflineDao
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Primary entry-point for accessing and mutating offline data. The UI layer should depend on this
 * service so that the app can function fully while offline.
 */
class LocalDataService private constructor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val database: OfflineDatabase = OfflineDatabase.getInstance(context)
    private val dao: OfflineDao = database.offlineDao()

    // region Project accessors
    fun observeProjects(): Flow<List<OfflineProjectEntity>> = dao.observeProjects()

    suspend fun getAllProjects(): List<OfflineProjectEntity> = withContext(ioDispatcher) {
        dao.getProjectsOnce()
    }

    fun observeLocations(projectId: Long): Flow<List<OfflineLocationEntity>> =
        dao.observeLocationsForProject(projectId)

    fun observeRooms(projectId: Long): Flow<List<OfflineRoomEntity>> =
        dao.observeRoomsForProject(projectId)

    fun observeAtmosphericLogsForProject(projectId: Long): Flow<List<OfflineAtmosphericLogEntity>> =
        dao.observeAtmosphericLogsForProject(projectId)

    fun observeAtmosphericLogsForRoom(roomId: Long): Flow<List<OfflineAtmosphericLogEntity>> =
        dao.observeAtmosphericLogsForRoom(roomId)

    fun observePhotosForProject(projectId: Long): Flow<List<OfflinePhotoEntity>> =
        dao.observePhotosForProject(projectId)

    fun observePhotosForRoom(roomId: Long): Flow<List<OfflinePhotoEntity>> =
        dao.observePhotosForRoom(roomId)

    fun observePhotoCountForRoom(roomId: Long): Flow<Int> =
        dao.observePhotoCountForRoom(roomId)

    fun pagedPhotosForRoom(
        roomId: Long,
        pageSize: Int = DEFAULT_ROOM_PHOTO_PAGE_SIZE
    ): Flow<PagingData<OfflinePhotoEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = pageSize,
                prefetchDistance = pageSize / 2,
                enablePlaceholders = false,
                initialLoadSize = pageSize,
                maxSize = pageSize * MAX_ROOM_PHOTO_PAGES
            ),
            pagingSourceFactory = { dao.pagingPhotosForRoom(roomId) }
        ).flow

    suspend fun getPhotoByServerId(serverId: Long): OfflinePhotoEntity? =
        withContext(ioDispatcher) { dao.getPhotoByServerId(serverId) }

    fun observePhoto(photoId: Long): Flow<OfflinePhotoEntity?> =
        dao.observePhoto(photoId)

    suspend fun getPhoto(photoId: Long): OfflinePhotoEntity? =
        withContext(ioDispatcher) { dao.getPhotoById(photoId) }

    fun observeAlbumsForProject(projectId: Long): Flow<List<OfflineAlbumEntity>> =
        dao.observeAlbumsForProject(projectId)

    fun observeAlbumsForRoom(roomId: Long): Flow<List<OfflineAlbumEntity>> =
        dao.observeAlbumsForRoom(roomId)

    fun observePhotosForAlbum(albumId: Long): Flow<List<OfflinePhotoEntity>> =
        dao.observePhotosForAlbum(albumId)

    suspend fun getPhotosNeedingCache(limit: Int = 25): List<OfflinePhotoEntity> =
        withContext(ioDispatcher) { dao.getPhotosNeedingCache(limit = limit) }

    fun observeCachedPhotoCount(): Flow<Int> =
        dao.observePhotoCountByCacheStatus(PhotoCacheStatus.READY)

    fun observeEquipmentForProject(projectId: Long): Flow<List<OfflineEquipmentEntity>> =
        dao.observeEquipmentForProject(projectId)

    fun observeEquipmentForRoom(roomId: Long): Flow<List<OfflineEquipmentEntity>> =
        dao.observeEquipmentForRoom(roomId)

    fun observeMoistureLogsForRoom(roomId: Long): Flow<List<OfflineMoistureLogEntity>> =
        dao.observeMoistureLogsForRoom(roomId)

    fun observeNotes(projectId: Long): Flow<List<OfflineNoteEntity>> = dao.observeNotesForProject(projectId)

    fun observeDamages(projectId: Long): Flow<List<OfflineDamageEntity>> =
        dao.observeDamagesForProject(projectId)

    fun observeWorkScopes(projectId: Long): Flow<List<OfflineWorkScopeEntity>> =
        dao.observeWorkScopesForProject(projectId)

    fun observeMaterials(): Flow<List<OfflineMaterialEntity>> = dao.observeMaterials()
    // endregion

    // region Mutations
    suspend fun saveProjects(projects: List<OfflineProjectEntity>) = withContext(ioDispatcher) {
        dao.upsertProjects(projects)
    }

    suspend fun saveLocations(locations: List<OfflineLocationEntity>) = withContext(ioDispatcher) {
        dao.upsertLocations(locations)
    }

    suspend fun saveRooms(rooms: List<OfflineRoomEntity>) = withContext(ioDispatcher) {
        dao.upsertRooms(rooms)
    }

    suspend fun saveAtmosphericLogs(logs: List<OfflineAtmosphericLogEntity>) = withContext(ioDispatcher) {
        dao.upsertAtmosphericLogs(logs)
    }

    suspend fun savePhotos(photos: List<OfflinePhotoEntity>) = withContext(ioDispatcher) {
        dao.upsertPhotos(photos)
    }

    suspend fun saveAlbums(albums: List<OfflineAlbumEntity>) = withContext(ioDispatcher) {
        dao.upsertAlbums(albums)
    }

    suspend fun saveAlbumPhotos(albumPhotos: List<OfflineAlbumPhotoEntity>) = withContext(ioDispatcher) {
        dao.upsertAlbumPhotos(albumPhotos)
    }

    suspend fun saveEquipment(items: List<OfflineEquipmentEntity>) = withContext(ioDispatcher) {
        dao.upsertEquipment(items)
    }

    suspend fun saveMoistureLogs(logs: List<OfflineMoistureLogEntity>) = withContext(ioDispatcher) {
        dao.upsertMoistureLogs(logs)
    }

    suspend fun saveNotes(notes: List<OfflineNoteEntity>) = withContext(ioDispatcher) {
        dao.upsertNotes(notes)
    }

    suspend fun saveDamages(damages: List<OfflineDamageEntity>) = withContext(ioDispatcher) {
        dao.upsertDamages(damages)
    }

    suspend fun saveWorkScopes(scopes: List<OfflineWorkScopeEntity>) = withContext(ioDispatcher) {
        dao.upsertWorkScopes(scopes)
    }

    suspend fun saveMaterials(materials: List<OfflineMaterialEntity>) = withContext(ioDispatcher) {
        dao.upsertMaterials(materials)
    }

    suspend fun saveCompany(company: OfflineCompanyEntity) = withContext(ioDispatcher) {
        dao.upsertCompany(company)
    }

    suspend fun saveUsers(users: List<OfflineUserEntity>) = withContext(ioDispatcher) {
        dao.upsertUsers(users)
    }

    suspend fun saveProperty(property: OfflinePropertyEntity) = withContext(ioDispatcher) {
        dao.upsertProperty(property)
    }

    suspend fun enqueueSyncOperation(operation: OfflineSyncQueueEntity) = withContext(ioDispatcher) {
        dao.upsertSyncOperation(operation)
    }

    fun observeSyncOperations(status: SyncStatus): Flow<List<OfflineSyncQueueEntity>> =
        dao.observeSyncOperationsByStatus(status)

    suspend fun removeSyncOperation(operationId: String) = withContext(ioDispatcher) {
        dao.deleteSyncOperation(operationId)
    }

    fun observeConflicts(): Flow<List<OfflineConflictResolutionEntity>> = dao.observeConflicts()

    suspend fun resolveConflict(conflictId: String) = withContext(ioDispatcher) {
        dao.deleteConflict(conflictId)
    }
    // endregion

    suspend fun markPhotoCacheInProgress(photoId: Long) = withContext(ioDispatcher) {
        dao.updatePhotoCacheStatus(photoId, PhotoCacheStatus.DOWNLOADING, Date())
    }

    suspend fun markPhotoCacheSuccess(
        photoId: Long,
        originalPath: String,
        thumbnailPath: String?
    ) = withContext(ioDispatcher) {
        dao.updatePhotoCachePaths(
            photoId = photoId,
            status = PhotoCacheStatus.READY,
            originalPath = originalPath,
            thumbnailPath = thumbnailPath,
            timestamp = Date()
        )
    }

    suspend fun markPhotoCacheFailed(photoId: Long) = withContext(ioDispatcher) {
        dao.updatePhotoCacheStatus(photoId, PhotoCacheStatus.FAILED, Date())
    }

    suspend fun touchPhotoAccess(photoId: Long) = withContext(ioDispatcher) {
        dao.updatePhotoCacheStatus(photoId, PhotoCacheStatus.READY, Date())
    }

    companion object {
        @Volatile
        private var instance: LocalDataService? = null
        private const val DEFAULT_ROOM_PHOTO_PAGE_SIZE = 30
        private const val MAX_ROOM_PHOTO_PAGES = 5

        fun initialize(context: Context): LocalDataService =
            instance ?: synchronized(this) {
                instance ?: LocalDataService(context.applicationContext).also { instance = it }
            }

        fun getInstance(): LocalDataService =
            instance ?: throw IllegalStateException("LocalDataService has not been initialized.")
    }
}
