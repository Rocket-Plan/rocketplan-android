package com.example.rocketplan_android.testing

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.*
import com.example.rocketplan_android.data.queue.ImageProcessorQueueManager
import com.example.rocketplan_android.data.repository.ImageProcessorRepository
import com.example.rocketplan_android.data.repository.sync.handlers.PushHandlerContext
import com.example.rocketplan_android.logging.RemoteLogger
import com.google.gson.Gson
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.Date

/**
 * Shared test fixtures for push handler and sync service tests.
 */
object PushHandlerTestFixtures {

    val gson = Gson()

    fun createContext(
        api: OfflineSyncApi = mockk(relaxed = true),
        localDataService: LocalDataService = mockk(relaxed = true),
        remoteLogger: RemoteLogger = mockk(relaxed = true),
        queueManager: ImageProcessorQueueManager = mockk(relaxed = true),
        imageProcessorRepository: ImageProcessorRepository = mockk(relaxed = true)
    ) = PushHandlerContext(
        api = api,
        localDataService = localDataService,
        gson = gson,
        remoteLogger = remoteLogger,
        syncProjectEssentials = { mockk() },
        persistProperty = { _, _, _, _, _ -> mockk() },
        imageProcessorQueueManagerProvider = { queueManager },
        imageProcessorRepositoryProvider = { imageProcessorRepository }
    )

    // ===== Entity Factories =====

    fun createProject(
        projectId: Long = 100L,
        serverId: Long? = 1000L,
        uuid: String = "project-uuid",
        title: String = "Test Project",
        status: String = "active",
        companyId: Long? = 1L,
        propertyId: Long? = null,
        alias: String? = null,
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflineProjectEntity(
        projectId = projectId,
        serverId = serverId,
        uuid = uuid,
        title = title,
        status = status,
        companyId = companyId,
        propertyId = propertyId,
        alias = alias,
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = false,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createProperty(
        propertyId: Long = 200L,
        serverId: Long? = 2000L,
        uuid: String = "property-uuid",
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflinePropertyEntity(
        propertyId = propertyId,
        serverId = serverId,
        uuid = uuid,
        address = "123 Test St",
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = false,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createLocation(
        locationId: Long = 300L,
        serverId: Long? = 3000L,
        uuid: String = "location-uuid",
        projectId: Long = 100L,
        title: String = "First Floor",
        type: String = "level",
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflineLocationEntity(
        locationId = locationId,
        serverId = serverId,
        uuid = uuid,
        projectId = projectId,
        title = title,
        type = type,
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = false,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createRoom(
        roomId: Long = 400L,
        serverId: Long? = 4000L,
        uuid: String = "room-uuid",
        projectId: Long = 100L,
        locationId: Long? = 300L,
        title: String = "Living Room",
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflineRoomEntity(
        roomId = roomId,
        serverId = serverId,
        uuid = uuid,
        projectId = projectId,
        locationId = locationId,
        title = title,
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = false,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createNote(
        noteId: Long = 500L,
        serverId: Long? = 5000L,
        uuid: String = "note-uuid",
        projectId: Long = 100L,
        roomId: Long? = 400L,
        photoId: Long? = null,
        content: String = "Test note",
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflineNoteEntity(
        noteId = noteId,
        serverId = serverId,
        uuid = uuid,
        projectId = projectId,
        roomId = roomId,
        photoId = photoId,
        content = content,
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = true,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createEquipment(
        equipmentId: Long = 600L,
        serverId: Long? = 6000L,
        uuid: String = "equipment-uuid",
        projectId: Long = 100L,
        roomId: Long? = 400L,
        type: String = "Dehumidifier",
        status: String = "active",
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflineEquipmentEntity(
        equipmentId = equipmentId,
        serverId = serverId,
        uuid = uuid,
        projectId = projectId,
        roomId = roomId,
        type = type,
        status = status,
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = true,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createMoistureLog(
        logId: Long = 700L,
        serverId: Long? = 7000L,
        uuid: String = "moisture-log-uuid",
        projectId: Long = 100L,
        roomId: Long = 400L,
        materialId: Long = 800L,
        moistureContent: Double = 15.5,
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflineMoistureLogEntity(
        logId = logId,
        serverId = serverId,
        uuid = uuid,
        projectId = projectId,
        roomId = roomId,
        materialId = materialId,
        date = Date(),
        moistureContent = moistureContent,
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = true,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createAtmosphericLog(
        logId: Long = 900L,
        serverId: Long? = null,
        uuid: String = "atmos-log-uuid",
        projectId: Long = 100L,
        roomId: Long? = null,
        photoLocalPath: String? = null,
        photoAssemblyId: String? = null,
        photoUploadStatus: String = "none",
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.PENDING
    ) = OfflineAtmosphericLogEntity(
        logId = logId,
        uuid = uuid,
        serverId = serverId,
        projectId = projectId,
        roomId = roomId,
        date = Date(),
        temperature = 72.0,
        relativeHumidity = 45.0,
        dewPoint = null,
        gpp = null,
        pressure = 29.92,
        windSpeed = 5.0,
        isExternal = true,
        isInlet = false,
        inletId = null,
        photoUrl = null,
        photoLocalPath = photoLocalPath,
        photoUploadStatus = photoUploadStatus,
        photoAssemblyId = photoAssemblyId,
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = true,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = null
    )

    fun createTimecard(
        timecardId: Long = 1000L,
        serverId: Long? = 10000L,
        uuid: String = "timecard-uuid",
        projectId: Long = 100L,
        userId: Long = 1L,
        companyId: Long = 1L,
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflineTimecardEntity(
        timecardId = timecardId,
        serverId = serverId,
        uuid = uuid,
        projectId = projectId,
        userId = userId,
        companyId = companyId,
        timeIn = Date(),
        timeOut = Date(),
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = true,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createPhoto(
        photoId: Long = 1100L,
        serverId: Long? = 11000L,
        uuid: String = "photo-uuid",
        projectId: Long = 100L,
        roomId: Long? = 400L,
        isDeleted: Boolean = false,
        syncStatus: SyncStatus = SyncStatus.SYNCED
    ) = OfflinePhotoEntity(
        photoId = photoId,
        serverId = serverId,
        uuid = uuid,
        projectId = projectId,
        roomId = roomId,
        fileName = "test.jpg",
        localPath = "/path/to/test.jpg",
        fileSize = 1024,
        mimeType = "image/jpeg",
        isDeleted = isDeleted,
        syncStatus = syncStatus,
        isDirty = true,
        createdAt = Date(),
        updatedAt = Date(),
        serverUpdatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createMaterial(
        materialId: Long = 800L,
        serverId: Long? = 8000L,
        uuid: String = "material-uuid",
        name: String = "Drywall"
    ) = OfflineMaterialEntity(
        materialId = materialId,
        serverId = serverId,
        uuid = uuid,
        name = name,
        createdAt = Date(),
        updatedAt = Date(),
        lastSyncedAt = Date()
    )

    fun createSupportConversation(
        conversationId: Long = 1200L,
        serverId: Long? = null,
        uuid: String = "conv-uuid",
        userId: Long = 1L,
        categoryId: Long = 1L,
        subject: String = "Test Subject"
    ) = OfflineSupportConversationEntity(
        conversationId = conversationId,
        serverId = serverId,
        uuid = uuid,
        userId = userId,
        categoryId = categoryId,
        subject = subject,
        createdAt = Date(),
        updatedAt = Date()
    )

    fun createSupportMessage(
        messageId: Long = 1300L,
        serverId: Long? = null,
        uuid: String = "msg-uuid",
        conversationId: Long = 1200L,
        conversationServerId: Long? = null,
        body: String = "Test message"
    ) = OfflineSupportMessageEntity(
        messageId = messageId,
        serverId = serverId,
        uuid = uuid,
        conversationId = conversationId,
        conversationServerId = conversationServerId,
        senderId = 1L,
        senderType = "user",
        body = body,
        createdAt = Date(),
        updatedAt = Date()
    )

    // ===== Sync Operation Factory =====

    fun createSyncOperation(
        entityType: String = "project",
        entityId: Long = 100L,
        entityUuid: String = "test-uuid",
        operationType: SyncOperationType = SyncOperationType.UPDATE,
        payload: ByteArray = ByteArray(0)
    ) = OfflineSyncQueueEntity(
        operationId = "op-${System.nanoTime()}",
        entityType = entityType,
        entityId = entityId,
        entityUuid = entityUuid,
        operationType = operationType,
        payload = payload,
        priority = SyncPriority.MEDIUM,
        createdAt = Date()
    )

    // ===== HTTP Error Helpers =====

    fun create409WithUpdatedAt(updatedAt: String = "2026-01-30T12:00:00.000000Z"): HttpException {
        val body = """{"updated_at":"$updatedAt"}"""
        val responseBody = body.toResponseBody("application/json".toMediaType())
        val response = Response.error<Any>(409, responseBody)
        return HttpException(response)
    }

    fun create409WithoutUpdatedAt(): HttpException {
        val body = """{"error":"conflict"}"""
        val responseBody = body.toResponseBody("application/json".toMediaType())
        val response = Response.error<Any>(409, responseBody)
        return HttpException(response)
    }

    fun create422Response(): HttpException {
        val body = """{"error":"validation failed"}"""
        val responseBody = body.toResponseBody("application/json".toMediaType())
        val response = Response.error<Any>(422, responseBody)
        return HttpException(response)
    }

    fun create404Response(): HttpException {
        val body = """{"error":"not found"}"""
        val responseBody = body.toResponseBody("application/json".toMediaType())
        val response = Response.error<Any>(404, responseBody)
        return HttpException(response)
    }

    fun create410Response(): HttpException {
        val body = """{"error":"gone"}"""
        val responseBody = body.toResponseBody("application/json".toMediaType())
        val response = Response.error<Any>(410, responseBody)
        return HttpException(response)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> createSuccessResponse(body: T? = null): Response<T> =
        if (body != null) Response.success(body) else Response.success(null as T?, okhttp3.Headers.Builder().build())

    fun createEmptySuccessResponse(): Response<Unit> =
        Response.success(Unit)

    fun create404RetrofitResponse(): Response<Unit> {
        val body = "".toResponseBody("application/json".toMediaType())
        return Response.error(404, body)
    }

    fun create409RetrofitResponse(updatedAt: String = "2026-01-30T12:00:00.000000Z"): Response<Unit> {
        val body = """{"updated_at":"$updatedAt"}""".toResponseBody("application/json".toMediaType())
        return Response.error(409, body)
    }

    fun create422RetrofitResponse(): Response<Unit> {
        val body = """{"error":"validation failed"}""".toResponseBody("application/json".toMediaType())
        return Response.error(422, body)
    }
}
