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

    @Volatile
    private var _currentCompanyId: Long? = null

    /**
     * The company ID the user is currently operating in.
     * Set this when the user logs in or switches companies.
     * @throws IllegalStateException if accessed before being set
     */
    val currentCompanyId: Long
        get() = _currentCompanyId
            ?: throw IllegalStateException("currentCompanyId not set. Call setCurrentCompanyId() after login.")

    /**
     * Returns the current company ID, or null if not yet set.
     * Prefer [currentCompanyId] when you expect it to be set.
     */
    val currentCompanyIdOrNull: Long?
        get() = _currentCompanyId

    /**
     * Sets the current company ID. Call this when the user logs in or switches companies.
     */
    fun setCurrentCompanyId(companyId: Long) {
        Log.d("LocalDataService", "🏢 Setting currentCompanyId=$companyId")
        _currentCompanyId = companyId
    }

    /**
     * Clears the current company ID. Call this on logout.
     */
    fun clearCurrentCompanyId() {
        Log.d("LocalDataService", "🏢 Clearing currentCompanyId")
        _currentCompanyId = null
    }

    // region Project accessors
    fun observeProjects(): Flow<List<OfflineProjectEntity>> = dao.observeProjects()

    fun observeProjectsWithProperty(): Flow<List<ProjectWithProperty>> =
        dao.observeProjectsWithProperty()

    suspend fun getAllProjects(): List<OfflineProjectEntity> = withContext(ioDispatcher) {
        dao.getProjectsOnce()
    }

    suspend fun getProject(projectId: Long): OfflineProjectEntity? =
        withContext(ioDispatcher) { dao.getProject(projectId) }

    suspend fun getProjectByServerId(serverId: Long, companyId: Long): OfflineProjectEntity? =
        withContext(ioDispatcher) { dao.getProjectByServerId(serverId, companyId) }

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

    suspend fun replaceOfflineRoomTypeCatalog(
        propertyTypes: List<OfflineCatalogPropertyTypeEntity>,
        levels: List<OfflineCatalogLevelEntity>,
        roomTypes: List<OfflineCatalogRoomTypeEntity>
    ) = withContext(ioDispatcher) {
        database.withTransaction {
            dao.clearCatalogPropertyTypes()
            dao.clearCatalogLevels()
            dao.clearCatalogRoomTypes()
            if (propertyTypes.isNotEmpty()) {
                dao.upsertCatalogPropertyTypes(propertyTypes)
            }
            if (levels.isNotEmpty()) {
                dao.upsertCatalogLevels(levels)
            }
            if (roomTypes.isNotEmpty()) {
                dao.upsertCatalogRoomTypes(roomTypes)
            }
        }
    }

    suspend fun getOfflineCatalogPropertyTypes(): List<OfflineCatalogPropertyTypeEntity> =
        withContext(ioDispatcher) { dao.getCatalogPropertyTypes() }

    suspend fun getOfflineCatalogLevels(): List<OfflineCatalogLevelEntity> =
        withContext(ioDispatcher) { dao.getCatalogLevels() }

    suspend fun getOfflineCatalogRoomTypes(): List<OfflineCatalogRoomTypeEntity> =
        withContext(ioDispatcher) { dao.getCatalogRoomTypes() }

    suspend fun getWorkScopeCatalogItems(companyId: Long): List<OfflineWorkScopeCatalogItemEntity> =
        withContext(ioDispatcher) { dao.getWorkScopeCatalogItems(companyId) }

    suspend fun getWorkScopeCatalogFetchedAt(companyId: Long): Date? =
        withContext(ioDispatcher) { dao.getLatestWorkScopeCatalogFetchedAt(companyId) }

    suspend fun replaceWorkScopeCatalogItems(
        companyId: Long,
        items: List<OfflineWorkScopeCatalogItemEntity>
    ) = withContext(ioDispatcher) {
        dao.clearWorkScopeCatalogItems(companyId)
        if (items.isNotEmpty()) {
            dao.upsertWorkScopeCatalogItems(items)
        }
    }

    suspend fun getDamageTypes(projectServerId: Long): List<OfflineDamageTypeEntity> =
        withContext(ioDispatcher) { dao.getDamageTypes(projectServerId) }

    suspend fun getDamageTypesFetchedAt(projectServerId: Long): Date? =
        withContext(ioDispatcher) { dao.getLatestDamageTypesFetchedAt(projectServerId) }

    suspend fun replaceDamageTypes(
        projectServerId: Long,
        types: List<OfflineDamageTypeEntity>
    ) = withContext(ioDispatcher) {
        dao.clearDamageTypes(projectServerId)
        if (types.isNotEmpty()) {
            dao.upsertDamageTypes(types)
        }
    }

    suspend fun getDamageCauses(projectServerId: Long): List<OfflineDamageCauseEntity> =
        withContext(ioDispatcher) { dao.getDamageCauses(projectServerId) }

    suspend fun getDamageCausesFetchedAt(projectServerId: Long): Date? =
        withContext(ioDispatcher) { dao.getLatestDamageCausesFetchedAt(projectServerId) }

    suspend fun replaceDamageCauses(
        projectServerId: Long,
        causes: List<OfflineDamageCauseEntity>
    ) = withContext(ioDispatcher) {
        dao.clearDamageCauses(projectServerId)
        if (causes.isNotEmpty()) {
            dao.upsertDamageCauses(causes)
        }
    }

    suspend fun getProperty(propertyId: Long): OfflinePropertyEntity? =
        withContext(ioDispatcher) { dao.getProperty(propertyId) }

    suspend fun getPropertyByServerId(serverId: Long): OfflinePropertyEntity? =
        withContext(ioDispatcher) { dao.getPropertyByServerId(serverId) }

    suspend fun deleteProperty(propertyId: Long) =
        withContext(ioDispatcher) { dao.deleteProperty(propertyId) }

    suspend fun getRoomByServerId(serverId: Long): OfflineRoomEntity? =
        withContext(ioDispatcher) { dao.getRoomByServerId(serverId) }

    suspend fun getRoom(roomId: Long): OfflineRoomEntity? =
        withContext(ioDispatcher) { dao.getRoom(roomId) }

    suspend fun getPendingRoomForProject(projectId: Long, title: String): OfflineRoomEntity? =
        withContext(ioDispatcher) {
            dao.getPendingRoomForProject(projectId, title)
        }

    suspend fun countPendingRoomsForProjectTitle(projectId: Long, title: String): Int =
        withContext(ioDispatcher) { dao.countPendingRoomsForProjectTitle(projectId, title) }

    suspend fun getPendingRoomDeletions(projectId: Long): List<OfflineRoomEntity> =
        withContext(ioDispatcher) { dao.getPendingRoomDeletions(projectId) }

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

    suspend fun deleteLocalPendingRoomPhoto(
        projectId: Long,
        roomId: Long,
        fileName: String
    ): Int = withContext(ioDispatcher) {
        if (fileName.isBlank()) return@withContext 0
        dao.deleteLocalPendingRoomPhoto(projectId, roomId, fileName)
    }

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

    suspend fun getPendingPhotoDeletions(projectId: Long): List<OfflinePhotoEntity> =
        withContext(ioDispatcher) { dao.getPendingPhotoDeletions(projectId) }

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

    suspend fun getPendingMoistureLogs(projectId: Long): List<OfflineMoistureLogEntity> = withContext(ioDispatcher) {
        dao.getPendingMoistureLogs(projectId)
    }

    suspend fun getMoistureLogByUuid(uuid: String): OfflineMoistureLogEntity? = withContext(ioDispatcher) {
        dao.getMoistureLogByUuid(uuid)
    }

    fun observeDamages(projectId: Long): Flow<List<OfflineDamageEntity>> =
        dao.observeDamagesForProject(projectId)

    fun observeWorkScopes(projectId: Long): Flow<List<OfflineWorkScopeEntity>> =
        dao.observeWorkScopesForProject(projectId)

    suspend fun getWorkScopeById(id: Long): OfflineWorkScopeEntity? = withContext(ioDispatcher) {
        dao.getWorkScopeById(id)
    }

    fun observeMaterials(): Flow<List<OfflineMaterialEntity>> = dao.observeMaterials()

    suspend fun getMaterialByUuid(uuid: String): OfflineMaterialEntity? = withContext(ioDispatcher) {
        dao.getMaterialByUuid(uuid)
    }

    suspend fun getMaterial(materialId: Long): OfflineMaterialEntity? = withContext(ioDispatcher) {
        dao.getMaterial(materialId)
    }
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
        propertyType: String?,
        forceUpdate: Boolean = false
    ) = withContext(ioDispatcher) {
        val existing = dao.getProject(projectId) ?: return@withContext
        // Preserve local pending property (negative ID) - don't overwrite with server property
        // unless forceUpdate is true (used when pending property creation completes)
        val existingPropertyIsPending = existing.propertyId != null && existing.propertyId < 0
        val resolvedPropertyId = if (existingPropertyIsPending && !forceUpdate) existing.propertyId else propertyId
        Log.d("API", "[attachPropertyToProject] projectId=$projectId existingPropertyId=${existing.propertyId} newPropertyId=$propertyId forceUpdate=$forceUpdate -> resolvedPropertyId=$resolvedPropertyId")
        val timestamp = Date()
        // Only mark as synced if forceUpdate (i.e., property actually synced from server)
        // Otherwise preserve project's current sync status to not clear pending changes
        val updatedProject = existing.copy(
            propertyId = resolvedPropertyId,
            propertyType = propertyType ?: existing.propertyType,
            syncStatus = if (forceUpdate) SyncStatus.SYNCED else existing.syncStatus,
            isDirty = if (forceUpdate) false else existing.isDirty,
            updatedAt = timestamp,
            lastSyncedAt = if (forceUpdate) timestamp else existing.lastSyncedAt
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

    /**
     * Deletes a room's related data locally to avoid orphaned records.
     * Returns the photos that were removed so callers can clean up files on disk.
     */
    suspend fun cascadeDeleteRoom(room: OfflineRoomEntity): List<OfflinePhotoEntity> = withContext(ioDispatcher) {
        val roomIds = buildSet {
            add(room.roomId)
            room.serverId?.let { add(it) }
        }

        val photosToDelete = roomIds.flatMap { id ->
            dao.getPhotosForRoomSnapshot(id)
        }

        database.withTransaction {
            roomIds.forEach { id ->
                dao.deleteAlbumPhotosByRoomId(id)
                dao.deleteAlbumsByRoomId(id)
                dao.clearRoomPhotoSnapshots(id)
                dao.deletePhotosByRoomId(id)
                dao.deleteNotesByRoomId(id)
                dao.deleteDamagesByRoomId(id)
                dao.deleteEquipmentByRoomId(id)
                dao.deleteMoistureLogsByRoomId(id)
                dao.deleteAtmosphericLogsByRoomId(id)
                dao.deleteWorkScopesByRoomId(id)
            }
        }

        photosToDelete
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

    /**
     * Cascades deletion for projects deleted on the server.
     * This finds all local projects matching the given server IDs and
     * deletes their child entities before marking them as deleted.
     *
     * @param serverIds List of server-side project IDs to delete
     * @param companyId Optional company ID to scope deletion. If provided, only projects
     *                  belonging to this company will be deleted. This prevents cross-tenant
     *                  data corruption if server IDs are not globally unique.
     * @return List of photos with cached files that need disk cleanup
     */
    suspend fun cascadeDeleteProjectsByServerIds(
        serverIds: List<Long>,
        companyId: Long? = null
    ): List<OfflinePhotoEntity> = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext emptyList()

        val cachedPhotosToCleanup = mutableListOf<OfflinePhotoEntity>()

        database.withTransaction {
            // Get all local project IDs for the given server IDs, optionally scoped by company
            val allProjects = dao.getProjectsOnce().filter { it.serverId in serverIds }
            val projects = if (companyId != null) {
                allProjects.filter { it.companyId == companyId }
            } else {
                allProjects
            }
            if (allProjects.isNotEmpty() && projects.isEmpty() && companyId != null) {
                Log.w(
                    "LocalDataService",
                    "⚠️ Found ${allProjects.size} projects matching serverIds=$serverIds but none for companyId=$companyId. " +
                        "Skipping deletion to prevent cross-tenant data loss."
                )
            }
            if (projects.isEmpty()) {
                Log.d("LocalDataService", "🗑️ No local projects found for serverIds=$serverIds")
                return@withTransaction
            }

            val projectIds = projects.map { it.projectId }
            val propertyIds = projects.mapNotNull { it.propertyId }

            // Clear sync queue operations for all affected entities FIRST
            // Must use typed deletions to avoid ID collisions across tables
            var clearedOps = 0
            clearedOps += dao.deleteSyncOpsForProjects(projectIds)
            if (propertyIds.isNotEmpty()) {
                clearedOps += dao.deleteSyncOpsForProperties(propertyIds)
            }
            clearedOps += dao.deleteSyncOpsForLocationsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForRoomsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForPhotosByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForNotesByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForEquipmentByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForAtmosphericLogsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForMoistureLogsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForDamagesByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForWorkScopesByProject(projectIds)
            if (clearedOps > 0) {
                Log.d("LocalDataService", "🧹 Cleared $clearedOps sync queue operations")
            }

            projects.forEach { project ->
                val roomIds = dao.getRoomIdsForProject(project.projectId)

                // Collect cached photos for disk cleanup
                cachedPhotosToCleanup.addAll(dao.getCachedPhotosForProject(project.projectId))

                // Mark all child entities as deleted
                dao.markLocationsDeletedByProject(project.projectId)
                dao.markRoomsDeletedByProject(project.projectId)
                dao.markPhotosDeletedByProject(project.projectId)
                dao.markAtmosphericLogsDeletedByProject(project.projectId)
                dao.markMoistureLogsDeletedByProject(project.projectId)
                dao.markNotesDeletedByProject(project.projectId)
                dao.markDamagesDeletedByProject(project.projectId)
                dao.markEquipmentDeletedByProject(project.projectId)
                dao.markWorkScopesDeletedByProject(project.projectId)

                // Hard-delete albums (they don't have soft-delete)
                dao.deleteAlbumPhotosByProject(project.projectId)
                dao.deleteAlbumsByProject(project.projectId)

                // Clear room photo snapshots
                if (roomIds.isNotEmpty()) {
                    dao.clearRoomPhotoSnapshots(roomIds)
                }

                // Clear project-specific catalogs
                project.serverId?.let { serverId ->
                    dao.clearDamageTypes(serverId)
                    dao.clearDamageCauses(serverId)
                }

                Log.d(
                    "LocalDataService",
                    "🗑️ Cascade deleted project ${project.projectId} (serverId=${project.serverId})"
                )
            }

            // Force-mark only the filtered projects as deleted (scoped by companyId if provided)
            // IMPORTANT: Use serverIds from filtered projects, not the original list, to prevent
            // cross-tenant deletion when companyId is specified
            val filteredServerIds = projects.mapNotNull { it.serverId }
            if (filteredServerIds.isNotEmpty()) {
                dao.forceMarkProjectsDeletedByServerIds(filteredServerIds)
            }
        }

        cachedPhotosToCleanup
    }

    /**
     * Marks a project and all its child entities as deleted.
     * This cascades the deletion to locations, rooms, photos, notes, damages,
     * equipment, atmospheric logs, moisture logs, work scopes, and albums.
     */
    suspend fun deleteProject(projectId: Long) = withContext(ioDispatcher) {
        database.withTransaction {
            val project = dao.getProject(projectId)
            val roomIds = dao.getRoomIdsForProject(projectId)
            val propertyId = project?.propertyId

            // Clear sync queue operations FIRST to prevent race with sync processor
            val projectIds = listOf(projectId)
            var clearedOps = 0
            clearedOps += dao.deleteSyncOpsForProjects(projectIds)
            if (propertyId != null) {
                clearedOps += dao.deleteSyncOpsForProperties(listOf(propertyId))
            }
            clearedOps += dao.deleteSyncOpsForLocationsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForRoomsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForPhotosByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForNotesByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForEquipmentByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForAtmosphericLogsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForMoistureLogsByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForDamagesByProject(projectIds)
            clearedOps += dao.deleteSyncOpsForWorkScopesByProject(projectIds)
            if (clearedOps > 0) {
                Log.d("LocalDataService", "🧹 [deleteProject] Cleared $clearedOps sync queue operations")
            }

            // Mark all child entities as deleted
            dao.markLocationsDeletedByProject(projectId)
            dao.markRoomsDeletedByProject(projectId)
            dao.markPhotosDeletedByProject(projectId)
            dao.markAtmosphericLogsDeletedByProject(projectId)
            dao.markMoistureLogsDeletedByProject(projectId)
            dao.markNotesDeletedByProject(projectId)
            dao.markDamagesDeletedByProject(projectId)
            dao.markEquipmentDeletedByProject(projectId)
            dao.markWorkScopesDeletedByProject(projectId)

            // Hard-delete albums (they don't have soft-delete)
            dao.deleteAlbumPhotosByProject(projectId)
            dao.deleteAlbumsByProject(projectId)

            // Clear room photo snapshots
            if (roomIds.isNotEmpty()) {
                dao.clearRoomPhotoSnapshots(roomIds)
            }

            // Clear project-specific catalogs if we have a serverId
            project?.serverId?.let { serverId ->
                dao.clearDamageTypes(serverId)
                dao.clearDamageCauses(serverId)
            }

            // Mark the project itself as deleted
            dao.markProjectDeletedByLocalId(projectId)

            Log.d(
                "LocalDataService",
                "🗑️ Cascade deleted project $projectId (serverId=${project?.serverId}): " +
                    "${roomIds.size} rooms"
            )
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
     * Repairs photos where the roomId references a room that doesn't exist in the photo's project.
     * Instead of deleting these photos, we reassign them to the project level (roomId = null).
     * This preserves the photos and allows users to manually reassign them to the correct room.
     * @return The count of photos reassigned to project level
     */
    suspend fun repairMismatchedPhotoRoomIds(): Int = withContext(ioDispatcher) {
        val mismatched = dao.getPhotosWithMismatchedRoomIds()
        if (mismatched.isEmpty()) {
            Log.d("LocalDataService", "✅ No mismatched photo roomIds found")
            return@withContext 0
        }

        Log.w("LocalDataService", "🔧 Found ${mismatched.size} photos with mismatched roomIds (will reassign to project level):")
        mismatched.take(10).forEach { photo ->
            Log.w("LocalDataService", "  Photo ${photo.photoId}: projectId=${photo.projectId}, roomId=${photo.roomId}")
        }
        if (mismatched.size > 10) {
            Log.w("LocalDataService", "  ... and ${mismatched.size - 10} more")
        }

        val reassigned = dao.reassignMismatchedPhotosToProject()
        Log.w("LocalDataService", "📎 Reassigned $reassigned photos to project level (roomId cleared)")
        return@withContext reassigned
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

    suspend fun markMoistureLogsDeleted(serverIds: List<Long>) = withContext(ioDispatcher) {
        if (serverIds.isEmpty()) return@withContext
        dao.markMoistureLogsDeleted(serverIds)
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

    /**
     * Check if there are any scheduled operations that are now due for retry.
     * Used by the periodic retry ticker to wake up stalled backoff operations.
     */
    suspend fun hasDueScheduledOperations(): Boolean = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        dao.countDueScheduledOperations(SyncStatus.PENDING, now) > 0
    }

    suspend fun getSyncOperationForEntity(
        entityType: String,
        entityId: Long,
        status: SyncStatus = SyncStatus.PENDING
    ): OfflineSyncQueueEntity? = withContext(ioDispatcher) {
        dao.getSyncOperationForEntity(entityType, entityId, status)
    }

    suspend fun removeSyncOperation(operationId: String) = withContext(ioDispatcher) {
        dao.deleteSyncOperation(operationId)
    }

    suspend fun removeSyncOperationsForEntity(entityType: String, entityId: Long) = withContext(ioDispatcher) {
        dao.deleteSyncOperationsForEntity(entityType, entityId)
    }

    /** Counts all pending sync operations for a project and its children (photos, notes, rooms, etc.) */
    suspend fun countPendingSyncOpsForProject(projectId: Long): Int = withContext(ioDispatcher) {
        dao.countPendingSyncOpsForProject(projectId)
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
