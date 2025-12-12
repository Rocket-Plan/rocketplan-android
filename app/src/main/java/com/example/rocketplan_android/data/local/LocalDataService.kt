package com.example.rocketplan_android.data.local

import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
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
import com.example.rocketplan_android.data.local.entity.OfflineRoomPhotoSnapshotEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomTypeEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.local.entity.OfflineUserEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.local.entity.hasRenderableAsset
import com.example.rocketplan_android.data.local.entity.preferredImageSource
import com.example.rocketplan_android.data.local.entity.preferredThumbnailSource
import com.example.rocketplan_android.data.local.model.RoomPhotoSummary
import com.example.rocketplan_android.data.local.model.ProjectWithProperty
import com.example.rocketplan_android.data.model.ProjectStatus
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

    fun observeProjectsWithProperty(): Flow<List<ProjectWithProperty>> =
        dao.observeProjectsWithProperty()

    suspend fun getAllProjects(): List<OfflineProjectEntity> = withContext(ioDispatcher) {
        dao.getProjectsOnce()
    }

    suspend fun getProject(projectId: Long): OfflineProjectEntity? =
        withContext(ioDispatcher) { dao.getProject(projectId) }

    fun observeLocations(projectId: Long): Flow<List<OfflineLocationEntity>> =
        dao.observeLocationsForProject(projectId)

    suspend fun getLocations(projectId: Long): List<OfflineLocationEntity> =
        withContext(ioDispatcher) { dao.getLocationsForProject(projectId) }

    fun observeRooms(projectId: Long): Flow<List<OfflineRoomEntity>> =
        dao.observeRoomsForProject(projectId)

    fun observeRoomTypes(propertyServerId: Long, filterType: String): Flow<List<OfflineRoomTypeEntity>> =
        dao.observeRoomTypes(propertyServerId, filterType)

    suspend fun getRoomTypes(propertyServerId: Long, filterType: String): List<OfflineRoomTypeEntity> =
        withContext(ioDispatcher) { dao.getRoomTypes(propertyServerId, filterType) }

    suspend fun replaceRoomTypes(
        propertyServerId: Long,
        filterType: String,
        types: List<OfflineRoomTypeEntity>
    ) = withContext(ioDispatcher) {
        dao.clearRoomTypes(propertyServerId, filterType)
        if (types.isNotEmpty()) {
            dao.upsertRoomTypes(types)
        }
    }

    suspend fun getProperty(propertyId: Long): OfflinePropertyEntity? =
        withContext(ioDispatcher) { dao.getProperty(propertyId) }

    suspend fun getRoomByServerId(serverId: Long): OfflineRoomEntity? =
        withContext(ioDispatcher) { dao.getRoomByServerId(serverId) }

    suspend fun getRoom(roomId: Long): OfflineRoomEntity? =
        withContext(ioDispatcher) { dao.getRoom(roomId) }

    suspend fun getServerRoomIdsForProject(projectId: Long): List<Long> =
        withContext(ioDispatcher) { dao.getServerRoomIdsForProject(projectId) }

    suspend fun getRoomByUuid(uuid: String): OfflineRoomEntity? =
        withContext(ioDispatcher) { dao.getRoomByUuid(uuid) }

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

    fun pagedPhotoSnapshotsForRoom(
        roomId: Long,
        pageSize: Int = DEFAULT_ROOM_PHOTO_PAGE_SIZE
    ): Flow<PagingData<OfflineRoomPhotoSnapshotEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = pageSize,
                prefetchDistance = pageSize / 2,
                enablePlaceholders = false,
                initialLoadSize = pageSize,
                maxSize = pageSize * MAX_ROOM_PHOTO_PAGES
            ),
            pagingSourceFactory = { dao.pagingRoomPhotoSnapshots(roomId) }
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

    suspend fun getCachedPhotos(): List<OfflinePhotoEntity> =
        withContext(ioDispatcher) { dao.getCachedPhotos() }

    suspend fun refreshRoomPhotoSnapshot(roomId: Long) = withContext(ioDispatcher) {
        database.withTransaction {
            val photos = dao.getPhotosForRoomSnapshot(roomId)
                .filter { it.hasRenderableAsset() }
            dao.clearRoomPhotoSnapshots(roomId)
            if (photos.isEmpty()) return@withTransaction

            // Log first 10 photos to verify ordering
            Log.d("LocalDataService", "📸 Snapshot for room $roomId: ${photos.size} photos")
            photos.take(10).forEachIndexed { index, photo ->
                Log.d("LocalDataService", "  [$index] id=${photo.photoId}, capturedAt=${photo.capturedAt}, createdAt=${photo.createdAt}")
            }

            val snapshots = photos.mapIndexed { index, photo ->
                OfflineRoomPhotoSnapshotEntity(
                    roomId = roomId,
                    photoId = photo.photoId,
                    orderIndex = index,
                    imageUrl = photo.preferredImageSource(),
                    thumbnailUrl = photo.preferredThumbnailSource(),
                    capturedOn = photo.capturedAt ?: photo.createdAt
                )
            }

            // Log first 10 snapshot entries being inserted
            Log.d("LocalDataService", "📝 Inserting ${snapshots.size} snapshot entries")
            snapshots.take(10).forEach { snapshot ->
                Log.d("LocalDataService", "  orderIndex=${snapshot.orderIndex}, photoId=${snapshot.photoId}, capturedOn=${snapshot.capturedOn}")
            }

            dao.insertRoomPhotoSnapshots(snapshots)
        }
    }

    suspend fun clearRoomPhotoSnapshot(roomId: Long) = withContext(ioDispatcher) {
        dao.clearRoomPhotoSnapshots(roomId)
    }

    fun observeCachedPhotoCount(): Flow<Int> =
        dao.observePhotoCountByCacheStatus(PhotoCacheStatus.READY)

    fun observeRoomPhotoSummaries(projectId: Long): Flow<List<RoomPhotoSummary>> =
        dao.observeRoomPhotoSummaries(projectId)

    fun observeEquipmentForProject(projectId: Long): Flow<List<OfflineEquipmentEntity>> =
        dao.observeEquipmentForProject(projectId)

    fun observeEquipmentForRoom(roomId: Long): Flow<List<OfflineEquipmentEntity>> =
        dao.observeEquipmentForRoom(roomId)

    fun observeMoistureLogsForProject(projectId: Long): Flow<List<OfflineMoistureLogEntity>> =
        dao.observeMoistureLogsForProject(projectId)

    fun observeMoistureLogsForRoom(roomId: Long): Flow<List<OfflineMoistureLogEntity>> =
        dao.observeMoistureLogsForRoom(roomId)

    fun observeNotes(projectId: Long): Flow<List<OfflineNoteEntity>> = dao.observeNotesForProject(projectId)

    fun observeNotesForRoom(projectId: Long, roomId: Long): Flow<List<OfflineNoteEntity>> =
        dao.observeNotesForRoom(projectId, roomId)

    suspend fun getNoteByUuid(uuid: String): OfflineNoteEntity? = withContext(ioDispatcher) {
        dao.getNoteByUuid(uuid)
    }

    suspend fun getPendingNotes(projectId: Long): List<OfflineNoteEntity> = withContext(ioDispatcher) {
        dao.getPendingNotes(projectId)
    }

    fun observeDamages(projectId: Long): Flow<List<OfflineDamageEntity>> =
        dao.observeDamagesForProject(projectId)

    fun observeWorkScopes(projectId: Long): Flow<List<OfflineWorkScopeEntity>> =
        dao.observeWorkScopesForProject(projectId)

    fun observeMaterials(): Flow<List<OfflineMaterialEntity>> = dao.observeMaterials()
    // endregion

    // region Mutations
    suspend fun saveProjects(projects: List<OfflineProjectEntity>) = withContext(ioDispatcher) {
        if (projects.isEmpty()) {
            Log.d("LocalDataService", "💾 saveProjects(): no projects supplied")
            return@withContext
        }

        val start = System.currentTimeMillis()
        Log.d("LocalDataService", "💾 saveProjects(): upserting ${projects.size} projects")
        projects.forEachIndexed { index, project ->
            val anomalies = mutableListOf<String>()
            when (project.serverId) {
                null -> anomalies.add("serverId=null")
                0L -> {
                    anomalies.add("serverId=0")
                    Log.e("LocalDataService", "🚨 BUG FOUND! Project with serverId=0 detected!", Exception("Stack trace for project 0 creation"))
                }
            }
            if (project.uuid.equals("project-0", ignoreCase = true)) {
                anomalies.add("uuid=project-0")
            }
            val suffix = if (anomalies.isEmpty()) {
                ""
            } else {
                " ⚠️ ${anomalies.joinToString()}"
            }
            Log.d(
                "LocalDataService",
                "   [${index}] localId=${project.projectId}, serverId=${project.serverId ?: "null"}, uuid=${project.uuid}, title=${project.title}$suffix"
            )
        }
        dao.upsertProjects(projects)
        Log.d("LocalDataService", "💾 saveProjects(): finished in ${System.currentTimeMillis() - start}ms")
    }

    suspend fun updateProjectStatus(projectId: Long, status: ProjectStatus) = withContext(ioDispatcher) {
        val existing = dao.getProject(projectId) ?: return@withContext
        if (existing.status.equals(status.apiValue, ignoreCase = true)) {
            return@withContext
        }
        val updatedProject = existing.copy(
            status = status.apiValue,
            isDirty = true,
            syncStatus = SyncStatus.PENDING,
            updatedAt = Date()
        )
        dao.upsertProject(updatedProject)
    }

    suspend fun attachPropertyToProject(
        projectId: Long,
        propertyId: Long,
        propertyType: String?
    ) = withContext(ioDispatcher) {
        val existing = dao.getProject(projectId) ?: return@withContext
        val timestamp = Date()
        val updatedProject = existing.copy(
            propertyId = propertyId,
            propertyType = propertyType ?: existing.propertyType,
            syncStatus = SyncStatus.SYNCED,
            isDirty = false,
            updatedAt = timestamp,
            lastSyncedAt = timestamp
        )
        dao.upsertProject(updatedProject)
    }

    suspend fun saveLocations(locations: List<OfflineLocationEntity>) = withContext(ioDispatcher) {
        dao.upsertLocations(locations)
    }

    suspend fun getLatestLocationUpdate(projectId: Long): Date? = withContext(ioDispatcher) {
        dao.getLatestLocationUpdatedAt(projectId)
    }

    suspend fun getLatestRoomUpdateForLocation(locationId: Long): Date? = withContext(ioDispatcher) {
        dao.getLatestRoomUpdatedAt(locationId)
    }

    /**
     * Ensures all room-scoped entities reference the canonical server room ID once it exists.
     * This is required because locally-created content uses the auto-generated roomId until
     * the backend assigns a serverId, at which point everything needs to be migrated.
     */
    suspend fun relinkRoomScopedData(): RoomDataRepairResult = withContext(ioDispatcher) {
        val rooms = dao.getRoomsWithServerId()
        if (rooms.isEmpty()) {
            return@withContext RoomDataRepairResult()
        }

        var roomsAdjusted = 0
        var photosRelinked = 0
        var notesRelinked = 0
        var damagesRelinked = 0
        var equipmentRelinked = 0
        var atmosphericRelinked = 0
        var moistureRelinked = 0
        var albumsRelinked = 0
        var workScopesRelinked = 0

        database.withTransaction {
            rooms.forEach { room ->
                val serverId = room.serverId ?: return@forEach
                val localRoomId = room.roomId
                if (localRoomId == serverId) {
                    // Nothing to migrate if the auto PK already matches the server id
                    return@forEach
                }

                suspend fun migrateReferences(oldId: Long, newId: Long): ReferenceMigrationCounts {
                    if (oldId == newId) {
                        return ReferenceMigrationCounts(0, 0, 0, 0, 0, 0, 0, 0)
                    }
                    val photoCount = dao.migratePhotoRoomIds(oldId, newId)
                    val noteCount = dao.migrateNoteRoomIds(oldId, newId)
                    val damageCount = dao.migrateDamageRoomIds(oldId, newId)
                    val equipmentCount = dao.migrateEquipmentRoomIds(oldId, newId)
                    val atmosphericCount = dao.migrateAtmosphericLogRoomIds(oldId, newId)
                    val moistureCount = dao.migrateMoistureLogRoomIds(oldId, newId)
                    val albumCount = dao.migrateAlbumRoomIds(oldId, newId)
                    val workScopeCount = dao.migrateWorkScopeRoomIds(oldId, newId)
                    return ReferenceMigrationCounts(
                        photos = photoCount,
                        notes = noteCount,
                        damages = damageCount,
                        equipment = equipmentCount,
                        atmosphericLogs = atmosphericCount,
                        moistureLogs = moistureCount,
                        albums = albumCount,
                        workScopes = workScopeCount
                    )
                }

                val counts = migrateReferences(localRoomId, serverId)
                if (counts.hasAnyUpdates()) {
                    roomsAdjusted += 1
                    photosRelinked += counts.photos
                    notesRelinked += counts.notes
                    damagesRelinked += counts.damages
                    equipmentRelinked += counts.equipment
                    atmosphericRelinked += counts.atmosphericLogs
                    moistureRelinked += counts.moistureLogs
                    albumsRelinked += counts.albums
                    workScopesRelinked += counts.workScopes
                }
            }
        }

        RoomDataRepairResult(
            roomsAdjusted = roomsAdjusted,
            photosRelinked = photosRelinked,
            notesRelinked = notesRelinked,
            damagesRelinked = damagesRelinked,
            equipmentRelinked = equipmentRelinked,
            atmosphericLogsRelinked = atmosphericRelinked,
            moistureLogsRelinked = moistureRelinked,
            albumsRelinked = albumsRelinked,
            workScopesRelinked = workScopesRelinked
        )
    }

    suspend fun saveRooms(rooms: List<OfflineRoomEntity>) = withContext(ioDispatcher) {
        // Split into new rooms (roomId = 0) and existing rooms (roomId > 0)
        // This ensures auto-generated IDs work correctly for new rooms
        val (newRooms, existingRooms) = rooms.partition { it.roomId == 0L }
        if (newRooms.isNotEmpty()) {
            dao.insertRooms(newRooms)
        }
        if (existingRooms.isNotEmpty()) {
            dao.upsertRooms(existingRooms)
        }
    }

    suspend fun deletePhantomRoom() = withContext(ioDispatcher) {
        val phantomRoomId = 0L
        database.withTransaction {
            val albumPhotos = dao.deleteAlbumPhotosByRoomId(phantomRoomId)
            val albums = dao.deleteAlbumsByRoomId(phantomRoomId)
            val snapshots = dao.clearRoomPhotoSnapshots(phantomRoomId)
            val photos = dao.deletePhotosByRoomId(phantomRoomId)
            val notes = dao.deleteNotesByRoomId(phantomRoomId)
            val damages = dao.deleteDamagesByRoomId(phantomRoomId)
            val equipment = dao.deleteEquipmentByRoomId(phantomRoomId)
            val moistureLogs = dao.deleteMoistureLogsByRoomId(phantomRoomId)
            val atmosphericLogs = dao.deleteAtmosphericLogsByRoomId(phantomRoomId)
            val workScopes = dao.deleteWorkScopesByRoomId(phantomRoomId)
            val rooms = dao.deletePhantomRoom()

            Log.w(
                "LocalDataService",
                "🧹 Deleted phantom roomId=$phantomRoomId and cleaned refs " +
                    "(rooms=$rooms, photos=$photos, notes=$notes, damages=$damages, equipment=$equipment, " +
                    "moistureLogs=$moistureLogs, atmosphericLogs=$atmosphericLogs, workScopes=$workScopes, " +
                    "albums=$albums, albumPhotos=$albumPhotos, snapshots=$snapshots)"
            )
        }
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

    suspend fun getEquipment(equipmentId: Long): OfflineEquipmentEntity? = withContext(ioDispatcher) {
        dao.getEquipment(equipmentId)
    }

    suspend fun getEquipmentByUuid(uuid: String): OfflineEquipmentEntity? = withContext(ioDispatcher) {
        dao.getEquipmentByUuid(uuid)
    }

    suspend fun getPendingEquipment(projectId: Long): List<OfflineEquipmentEntity> = withContext(ioDispatcher) {
        dao.getPendingEquipment(projectId)
    }

    suspend fun saveMoistureLogs(logs: List<OfflineMoistureLogEntity>) = withContext(ioDispatcher) {
        dao.upsertMoistureLogs(logs)
    }

    suspend fun saveNotes(notes: List<OfflineNoteEntity>) = withContext(ioDispatcher) {
        dao.upsertNotes(notes)
    }

    suspend fun saveNote(note: OfflineNoteEntity) = withContext(ioDispatcher) {
        dao.upsertNotes(listOf(note))
    }

    suspend fun saveDamages(damages: List<OfflineDamageEntity>) = withContext(ioDispatcher) {
        dao.upsertDamages(damages)
    }

    suspend fun saveWorkScopes(scopes: List<OfflineWorkScopeEntity>) = withContext(ioDispatcher) {
        dao.upsertWorkScopes(scopes)
    }

    suspend fun markProjectsDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markProjectsDeleted(serverIds)
    }

    suspend fun deleteProject(projectId: Long) = withContext(ioDispatcher) {
        database.withTransaction {
            dao.markProjectDeletedByLocalId(projectId)
            val roomIds = dao.getRoomIdsForProject(projectId)
            if (roomIds.isNotEmpty()) {
                dao.markRoomsDeletedByProject(projectId)
                dao.clearRoomPhotoSnapshots(roomIds)
            }
        }
    }

    suspend fun markLocationsDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markLocationsDeleted(serverIds)
    }

    suspend fun markRoomsDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        database.withTransaction {
            dao.markRoomsDeleted(serverIds)
            dao.clearRoomPhotoSnapshots(serverIds)
        }
    }

    suspend fun markPhotosDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markPhotosDeleted(serverIds)
    }

    /**
     * Removes photos where the roomId references a room that doesn't exist in the photo's project.
     * This repairs data integrity issues where photos were synced with incorrect roomId values.
     * @return The count of photos cleaned up
     */
    suspend fun repairMismatchedPhotoRoomIds(): Int = withContext(ioDispatcher) {
        val mismatched = dao.getPhotosWithMismatchedRoomIds()
        if (mismatched.isEmpty()) {
            Log.d("LocalDataService", "✅ No mismatched photo roomIds found")
            return@withContext 0
        }

        Log.w("LocalDataService", "🔧 Found ${mismatched.size} photos with mismatched roomIds:")
        mismatched.take(10).forEach { photo ->
            Log.w("LocalDataService", "  Photo ${photo.photoId}: projectId=${photo.projectId}, roomId=${photo.roomId}")
        }
        if (mismatched.size > 10) {
            Log.w("LocalDataService", "  ... and ${mismatched.size - 10} more")
        }

        val deleted = dao.deleteMismatchedPhotos()
        Log.w("LocalDataService", "🗑️ Cleaned up $deleted mismatched photos")
        return@withContext deleted
    }

    suspend fun markNotesDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markNotesDeleted(serverIds)
    }

    suspend fun markDamagesDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markDamagesDeleted(serverIds)
    }

    suspend fun markEquipmentDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markEquipmentDeleted(serverIds)
    }

    suspend fun markAtmosphericLogsDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markAtmosphericLogsDeleted(serverIds)
    }

    suspend fun markWorkScopesDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markWorkScopesDeleted(serverIds)
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

    suspend fun getPendingSyncOperations(): List<OfflineSyncQueueEntity> = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        dao.getSyncOperationsByStatus(SyncStatus.PENDING, now)
    }

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

    suspend fun getRecentAddresses(limit: Int = DEFAULT_RECENT_ADDRESS_COUNT): List<String> =
        withContext(ioDispatcher) { dao.getRecentAddresses(limit) }

    companion object {
        @Volatile
        private var instance: LocalDataService? = null
        private const val DEFAULT_ROOM_PHOTO_PAGE_SIZE = 30
        private const val MAX_ROOM_PHOTO_PAGES = 5
        private const val DEFAULT_RECENT_ADDRESS_COUNT = 10

        fun initialize(context: Context): LocalDataService =
            instance ?: synchronized(this) {
                instance ?: LocalDataService(context.applicationContext).also { instance = it }
            }

        fun getInstance(): LocalDataService =
            instance ?: throw IllegalStateException("LocalDataService has not been initialized.")
    }
}

data class RoomDataRepairResult(
    val roomsAdjusted: Int = 0,
    val photosRelinked: Int = 0,
    val notesRelinked: Int = 0,
    val damagesRelinked: Int = 0,
    val equipmentRelinked: Int = 0,
    val atmosphericLogsRelinked: Int = 0,
    val moistureLogsRelinked: Int = 0,
    val albumsRelinked: Int = 0,
    val workScopesRelinked: Int = 0
)

private data class ReferenceMigrationCounts(
    val photos: Int,
    val notes: Int,
    val damages: Int,
    val equipment: Int,
    val atmosphericLogs: Int,
    val moistureLogs: Int,
    val albums: Int,
    val workScopes: Int
) {
    fun hasAnyUpdates(): Boolean =
        photos + notes + damages + equipment + atmosphericLogs + moistureLogs + albums + workScopes > 0
}
