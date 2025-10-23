package com.example.rocketplan_android.data.local

import android.content.Context
import com.example.rocketplan_android.data.local.dao.OfflineDao
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
import java.util.UUID

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

    suspend fun seedDemoDataIfEmpty() = withContext(ioDispatcher) {
        if (dao.countProjects() > 0) return@withContext

        val now = Date()
        val companyId = 1L
        val propertyId = 1L
        val projectId = 1L
        val locationId = 1L
        val roomId = 1L
        val materialId = 1L

        val company = OfflineCompanyEntity(
            companyId = companyId,
            uuid = UUID.randomUUID().toString(),
            name = "RocketPlan Restoration",
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = now
        )
        dao.upsertCompany(company)

        val users = listOf(
            OfflineUserEntity(
                userId = 1L,
                uuid = UUID.randomUUID().toString(),
                email = "pm@rocketplan.io",
                firstName = "Project",
                lastName = "Manager",
                role = "manager",
                companyId = companyId,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now
            ),
            OfflineUserEntity(
                userId = 2L,
                uuid = UUID.randomUUID().toString(),
                email = "tech@rocketplan.io",
                firstName = "Field",
                lastName = "Technician",
                role = "technician",
                companyId = companyId,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now
            )
        )
        dao.upsertUsers(users)

        val property = OfflinePropertyEntity(
            propertyId = propertyId,
            uuid = UUID.randomUUID().toString(),
            address = "123 Offline Ave",
            city = "Vancouver",
            state = "BC",
            zipCode = "V6B 1A1",
            latitude = 49.2827,
            longitude = -123.1207,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = now
        )
        dao.upsertProperty(property)

        val project = OfflineProjectEntity(
            projectId = projectId,
            uuid = UUID.randomUUID().toString(),
            title = "Offline Demo Project",
            projectNumber = "RP-001",
            status = "active",
            propertyType = "residential",
            companyId = companyId,
            propertyId = propertyId,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 2,
            isDirty = false,
            isDeleted = false,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = now
        )
        dao.upsertProject(project)

        val location = OfflineLocationEntity(
            locationId = locationId,
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            title = "Main Building",
            type = "building",
            parentLocationId = null,
            isAccessible = true,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            isDirty = false,
            isDeleted = false,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = now
        )
        dao.upsertLocations(listOf(location))

        val room = OfflineRoomEntity(
            roomId = roomId,
            uuid = UUID.randomUUID().toString(),
            projectId = projectId,
            locationId = locationId,
            title = "Living Room",
            roomType = "living_space",
            level = "main",
            squareFootage = 400.0,
            isAccessible = true,
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            isDirty = false,
            isDeleted = false,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = now
        )
        dao.upsertRooms(listOf(room))

        val material = OfflineMaterialEntity(
            materialId = materialId,
            uuid = UUID.randomUUID().toString(),
            name = "Drywall",
            description = "Standard 5/8\" drywall",
            syncStatus = SyncStatus.SYNCED,
            syncVersion = 1,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = now
        )
        dao.upsertMaterials(listOf(material))

        val atmosphericLogs = listOf(
            OfflineAtmosphericLogEntity(
                logId = 1L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                date = now,
                relativeHumidity = 45.0,
                temperature = 22.0,
                dewPoint = 8.0,
                gpp = 40.0,
                pressure = 101.3,
                windSpeed = null,
                isExternal = false,
                isInlet = true,
                inletId = null,
                outletId = null,
                photoUrl = null,
                photoLocalPath = null,
                photoUploadStatus = "none",
                photoAssemblyId = null,
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
        dao.upsertAtmosphericLogs(atmosphericLogs)

        val photos = listOf(
            OfflinePhotoEntity(
                photoId = 1L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                logId = atmosphericLogs.first().logId,
                moistureLogId = null,
                albumId = null,
                fileName = "living_room_overview.jpg",
                localPath = "Documents/Photos/$projectId/$roomId/living_room_overview.jpg",
                remoteUrl = null,
                thumbnailUrl = null,
                uploadStatus = "completed",
                assemblyId = null,
                tusUploadId = null,
                fileSize = 1_024_000,
                width = 1920,
                height = 1080,
                mimeType = "image/jpeg",
                capturedAt = now,
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
        dao.upsertPhotos(photos)

        val equipment = listOf(
            OfflineEquipmentEntity(
                equipmentId = 1L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                type = "dehumidifier",
                brand = "Dri-Eaz",
                model = "Revolution LGR",
                serialNumber = "DH-1001",
                quantity = 1,
                status = "active",
                startDate = now,
                endDate = null,
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            ),
            OfflineEquipmentEntity(
                equipmentId = 2L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                type = "air_mover",
                brand = "Phoenix",
                model = "Focus II",
                serialNumber = "AM-2001",
                quantity = 3,
                status = "active",
                startDate = now,
                endDate = null,
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
        dao.upsertEquipment(equipment)

        val moistureLogs = listOf(
            OfflineMoistureLogEntity(
                logId = 1L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                materialId = materialId,
                date = now,
                moistureContent = 12.5,
                location = "West wall",
                depth = "Surface",
                photoUrl = null,
                photoLocalPath = null,
                photoUploadStatus = "none",
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
        dao.upsertMoistureLogs(moistureLogs)

        val notes = listOf(
            OfflineNoteEntity(
                noteId = 1L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                userId = users.first().userId,
                content = "Initial walkthrough completed. Equipment placed.",
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
        dao.upsertNotes(notes)

        val damages = listOf(
            OfflineDamageEntity(
                damageId = 1L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                title = "Ceiling staining",
                description = "Water staining on northwest corner of ceiling.",
                severity = "moderate",
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
        dao.upsertDamages(damages)

        val workScopes = listOf(
            OfflineWorkScopeEntity(
                workScopeId = 1L,
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = roomId,
                name = "Drywall repair",
                description = "Patch and repaint affected area after drying is complete.",
                createdAt = now,
                updatedAt = now,
                lastSyncedAt = now,
                syncStatus = SyncStatus.SYNCED,
                syncVersion = 1,
                isDirty = false,
                isDeleted = false
            )
        )
        dao.upsertWorkScopes(workScopes)
    }

    companion object {
        @Volatile
        private var instance: LocalDataService? = null

        fun initialize(context: Context): LocalDataService =
            instance ?: synchronized(this) {
                instance ?: LocalDataService(context.applicationContext).also { instance = it }
            }

        fun getInstance(): LocalDataService =
            instance ?: throw IllegalStateException("LocalDataService has not been initialized.")
    }
}
