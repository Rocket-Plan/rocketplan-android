package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncPriority
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineDamageEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineProjectEntity
import com.example.rocketplan_android.data.local.entity.OfflineSyncQueueEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.model.NoteResourceResponse
import com.example.rocketplan_android.data.model.DeleteProjectRequest
import com.example.rocketplan_android.data.model.ProjectDetailResourceResponse
import com.example.rocketplan_android.data.model.PropertyResourceResponse
import com.example.rocketplan_android.data.model.offline.AlbumDto
import com.example.rocketplan_android.data.model.offline.AtmosphericLogDto
import com.example.rocketplan_android.data.model.offline.DamageMaterialDto
import com.example.rocketplan_android.data.model.offline.DeletedRecordsResponse
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.NoteableDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.RoomPhotoDto
import com.example.rocketplan_android.data.model.offline.RoomPhotoFileDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.data.repository.SyncResult.Failure
import com.example.rocketplan_android.data.repository.SyncResult.Success
import com.example.rocketplan_android.data.repository.SyncSegment
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.util.DateUtils
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Headers
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.util.Date
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSyncRepositoryTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val projectId = 42L
    private val companyId = 7L

    @Test
    fun `syncProjectGraph persists paged project photos`() = runTest {
        val locationPhotoListing = ProjectPhotoListingDto(
            id = 1L,
            uuid = "photo-1",
            projectId = projectId,
            roomId = 100L,
            fileName = "photo.jpg",
            contentType = "image/jpeg",
            sizes = com.example.rocketplan_android.data.model.offline.PhotoSizeDto(
                small = "small.jpg",
                medium = "medium.jpg",
                gallery = "gallery.jpg"
            ),
            createdAt = "2025-05-06T18:01:14.000000Z",
            updatedAt = "2025-05-06T18:01:14.000000Z"
        )

        val albumPhoto = PhotoDto(
            id = 999L,
            uuid = "photo-999",
            projectId = projectId,
            roomId = 100L,
            logId = null,
            moistureLogId = null,
            fileName = "album_photo.jpg",
            localPath = "",
            remoteUrl = "https://example.com/album_photo.jpg",
            thumbnailUrl = "https://example.com/album_thumb.jpg",
            assemblyId = null,
            tusUploadId = null,
            fileSize = 2048,
            width = 800,
            height = 600,
            mimeType = "image/jpeg",
            capturedAt = "2025-05-06T18:01:14.000000Z",
            createdAt = "2025-05-06T18:01:14.000000Z",
            updatedAt = "2025-05-06T18:01:14.000000Z",
            albums = null
        )

        val albumDto = AlbumDto(
            id = 300L,
            name = "Damage Assessment",
            albumableType = "App\\Models\\Project",
            albumableId = projectId,
            photos = listOf(albumPhoto),
            createdAt = "2025-05-06T18:01:14.000000Z",
            updatedAt = "2025-05-06T18:01:14.000000Z"
        )

        val propertyDto = PropertyDto(
            id = 10L,
            uuid = "prop-10",
            address = "123 Main Street",
            city = "Vancouver",
            state = "BC",
            postalCode = "V5K0A1",
            latitude = null,
            longitude = null,
            propertyType = "residential",
            createdAt = "2025-05-06T18:01:14.000000Z",
            updatedAt = "2025-05-06T18:01:14.000000Z"
        )

        val api = mockk<OfflineSyncApi>()
        coEvery { api.getProjectDetail(projectId) } returns ProjectDetailResourceResponse(
            data = ProjectDetailDto(
                id = projectId,
                title = "Test Project",
                projectNumber = "RP-1",
                status = "wip",
                companyId = 1L,
                propertyId = 10L,
                notes = emptyList(),
                users = emptyList(),
                locations = emptyList(),
                rooms = emptyList(),
                photos = emptyList(),
                atmosphericLogs = emptyList(),
                moistureLogs = emptyList(),
                equipment = emptyList(),
                damages = emptyList(),
                workScopes = emptyList()
            )
        )
        coEvery { api.getProjectProperties(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProperty(10L) } returns PropertyResourceResponse(propertyDto)
        coEvery { api.getPropertyLevels(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getPropertyLocations(any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomsForLocation(any(), any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomDetail(any()) } returns RoomDto(
            id = 100L,
            uuid = "room",
            projectId = projectId,
            locationId = null,
            name = "Basement",
            title = "Basement",
            typeOccurrence = null,
            roomType = null,
            level = null,
            squareFootage = null,
            isAccessible = true,
            createdAt = "2025-05-01T00:00:00Z",
            updatedAt = "2025-05-01T00:00:00Z"
        )
        coEvery { api.getRoomPhotos(any(), any(), any(), any(), any()) } returns JsonObject()
        coEvery { api.getRoomAtmosphericLogs(any()) } returns emptyList()
        coEvery { api.getRoomMoistureLogs(any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomDamageMaterials(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomWorkScope(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomEquipment(any()) } returns emptyList()
        coEvery { api.getProjectAtmosphericLogs(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectFloorPhotos(projectId, any()) } returns PaginatedResponse(
            data = emptyList()
        )
        coEvery { api.getProjectLocationPhotos(projectId, any(), any()) } returns PaginatedResponse(
            data = listOf(locationPhotoListing),
            meta = com.example.rocketplan_android.data.model.offline.PaginationMeta(
                currentPage = 1,
                lastPage = 1,
                perPage = 50,
                total = 1
            )
        )
        coEvery { api.getProjectUnitPhotos(projectId, any()) } returns PaginatedResponse(
            data = emptyList()
        )
        coEvery { api.getProjectAlbums(projectId, any(), any()) } answers {
            val page = secondArg<Int?>() ?: 1
            if (page == 1) {
                PaginatedResponse(
                    data = listOf(albumDto),
                    meta = com.example.rocketplan_android.data.model.offline.PaginationMeta(
                        currentPage = 1,
                        lastPage = 1,
                        perPage = 50,
                        total = 1
                    )
                )
            } else {
                PaginatedResponse(data = emptyList())
            }
        }
        coEvery { api.getProjectDamageMaterials(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectNotes(projectId, any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectUsers(projectId) } returns emptyList()
        coEvery { api.getProjectEquipment(projectId) } returns PaginatedResponse(data = emptyList())

        everyLog()

        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        every { localDataService.observeRooms(projectId) } returns flowOf(emptyList<com.example.rocketplan_android.data.local.entity.OfflineRoomEntity>())
        coEvery { localDataService.getProject(projectId) } returns OfflineProjectEntity(
            projectId = projectId,
            serverId = projectId,
            uuid = "project-uuid",
            title = "Test Project",
            status = "wip",
            companyId = companyId
        )
        val savedPhotos = mutableListOf<List<OfflinePhotoEntity>>()
        coEvery { localDataService.savePhotos(capture(savedPhotos)) } just runs
        val savedAlbums = mutableListOf<List<OfflineAlbumEntity>>()
        coEvery { localDataService.saveAlbums(capture(savedAlbums)) } just runs
        val savedAlbumPhotos = mutableListOf<List<OfflineAlbumPhotoEntity>>()
        coEvery { localDataService.saveAlbumPhotos(capture(savedAlbumPhotos)) } just runs

        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncProjectGraph(projectId)

        coVerify { api.getProjectLocationPhotos(projectId, 1, any()) }
        coVerify { api.getProjectAlbums(projectId, 1, any()) }
        val flattened = savedPhotos.flatten()
        assertThat(flattened).isNotEmpty()
        val mapped = flattened.last()
        assertThat(mapped.remoteUrl).isEqualTo("gallery.jpg")
        assertThat(mapped.thumbnailUrl).isEqualTo("small.jpg")
        assertThat(mapped.roomId).isEqualTo(100L)
        coVerify { scheduler.schedulePrefetch() }
        val flattenedAlbums = savedAlbums.flatten()
        assertThat(flattenedAlbums).isNotEmpty()
        val albumEntity = flattenedAlbums.first()
        assertThat(albumEntity.albumId).isEqualTo(300L)
        assertThat(albumEntity.photoCount).isEqualTo(0)
        assertThat(albumEntity.thumbnailUrl).isNull()

        val flattenedAlbumPhotos = savedAlbumPhotos.flatten()
        assertThat(flattenedAlbumPhotos).isEmpty()
    }

    @Test
    fun `syncProjectGraph fetches and persists room photos`() = runTest {
        val roomId = 5999L
        val locationId = 3971L
        val roomPhotoDto = PhotoDto(
            id = 1234L,
            uuid = "photo-room",
            projectId = projectId,
            roomId = roomId,
            logId = null,
            moistureLogId = null,
            fileName = "room_photo.jpg",
            localPath = null,
            remoteUrl = "https://example.com/room_photo.jpg",
            thumbnailUrl = "https://example.com/room_thumbnail.jpg",
            assemblyId = null,
            tusUploadId = null,
            fileSize = 1024,
            width = 1200,
            height = 800,
            mimeType = "image/jpeg",
            capturedAt = "2025-05-06T18:01:14.000000Z",
            createdAt = "2025-05-06T18:01:14.000000Z",
            updatedAt = "2025-05-06T18:01:14.000000Z",
            albums = null
        )

        val api = mockk<OfflineSyncApi>()
        val propertyDto = PropertyDto(
            id = 10L,
            uuid = "prop-10",
            address = "123 Main Street",
            city = "Vancouver",
            state = "BC",
            postalCode = "V5K0A1",
            latitude = null,
            longitude = null,
            propertyType = "residential",
            createdAt = "2025-05-06T18:01:14.000000Z",
            updatedAt = "2025-05-06T18:01:14.000000Z"
        )
        coEvery { api.getProjectDetail(projectId) } returns ProjectDetailResourceResponse(
            data = ProjectDetailDto(
                id = projectId,
                title = "Project 4970",
                projectNumber = "RP-2",
                status = "active",
                companyId = 1L,
                propertyId = 10L,
                notes = emptyList(),
                users = emptyList(),
                locations = emptyList(),
                rooms = listOf(
                    RoomDto(
                        id = roomId,
                        uuid = "room-uuid",
                        projectId = projectId,
                        locationId = locationId,
                        name = "Main Level Bathroom",
                        title = null,
                        typeOccurrence = null,
                        roomType = com.example.rocketplan_android.data.model.offline.RoomTypeDto(
                            id = 1L,
                            name = "Bathroom",
                            type = "category",
                            isStandard = true
                        ),
                        level = null,
                        squareFootage = null,
                        isAccessible = true,
                        createdAt = "2025-05-01T00:00:00Z",
                        updatedAt = "2025-05-02T00:00:00Z"
                    )
                ),
                photos = emptyList(),
                atmosphericLogs = emptyList(),
                moistureLogs = emptyList(),
                equipment = emptyList(),
                damages = emptyList(),
                workScopes = emptyList()
            )
        )
        coEvery { api.getProjectProperties(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProperty(10L) } returns PropertyResourceResponse(propertyDto)
        coEvery { api.getPropertyLevels(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getPropertyLocations(any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomsForLocation(any(), any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomPhotos(any(), any(), any(), any(), any()) } returns JsonObject()
        coEvery { api.getRoomPhotos(roomId, any(), any(), any(), any()) } returns roomPhotosResponse(roomPhotoDto)
        coEvery { api.getRoomAtmosphericLogs(any()) } returns emptyList()
        coEvery { api.getRoomMoistureLogs(any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomDamageMaterials(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomWorkScope(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomEquipment(any()) } returns emptyList()
        coEvery { api.getProjectAtmosphericLogs(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectFloorPhotos(projectId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectLocationPhotos(projectId, any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectUnitPhotos(projectId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectAlbums(projectId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectDamageMaterials(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectNotes(projectId, any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectUsers(projectId) } returns emptyList()
        coEvery { api.getProjectEquipment(projectId) } returns PaginatedResponse(data = emptyList())

        everyLog()

        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        every {
            localDataService.observeRooms(projectId)
        } returns flowOf(
            listOf(
                com.example.rocketplan_android.data.local.entity.OfflineRoomEntity(
                    roomId = 1L,
                    serverId = roomId,
                    uuid = "room-uuid",
                    projectId = projectId,
                    locationId = locationId,
                    title = "Main Level Bathroom",
                    isAccessible = true
                )
            )
        )
        coEvery { localDataService.getProject(projectId) } returns OfflineProjectEntity(
            projectId = projectId,
            serverId = projectId,
            uuid = "project-uuid",
            title = "Project 4970",
            status = "active",
            companyId = 1L
        )
        val savedPhotos = mutableListOf<List<OfflinePhotoEntity>>()
        coEvery { localDataService.savePhotos(capture(savedPhotos)) } just runs
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncProjectGraph(projectId)

        coVerify { api.getRoomPhotos(roomId, any(), any(), any(), any()) }
        val flattenedPhotos = savedPhotos.flatten()
        assertThat(flattenedPhotos.any { it.serverId == roomPhotoDto.id && it.roomId == roomId }).isTrue()
        coVerify { scheduler.schedulePrefetch() }
    }

    @Test
    fun `syncProjectGraph fast path only runs essentials`() = runTest {
        val api = mockk<OfflineSyncApi>(relaxed = true)
        coEvery { api.getProjectDetail(projectId) } returns ProjectDetailResourceResponse(
            data = ProjectDetailDto(
                id = projectId,
                title = "Fast Project",
                status = "wip",
                companyId = companyId
            )
        )
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())

        val repository = spyk(
            OfflineSyncRepository(
                api = api,
                localDataService = localDataService,
                photoCacheScheduler = mockk(relaxed = true),
                syncCheckpointStore = mockk(relaxed = true),
                roomTypeRepository = mockk(relaxed = true)
            )
        )

        val capturedSegments = slot<List<SyncSegment>>()
        coEvery { repository.syncProjectSegments(projectId, capture(capturedSegments)) } returns listOf(
            Success(SyncSegment.PROJECT_ESSENTIALS, itemsSynced = 1, durationMs = 5)
        )

        val results = repository.syncProjectGraph(projectId, skipPhotos = true)

        assertThat(capturedSegments.captured).containsExactly(SyncSegment.PROJECT_ESSENTIALS)
        assertThat(results).hasSize(1)
        val essentials = results.single() as Success
        assertThat(essentials.segment).isEqualTo(SyncSegment.PROJECT_ESSENTIALS)
    }

    @Test
    fun `syncRoomPhotos updates checkpoint using latest server timestamp`() = runTest {
        val roomId = 77L
        val timestamp = "2025-06-01T10:15:30Z"
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val photo = PhotoDto(
            id = 200L,
            uuid = "photo-200",
            projectId = projectId,
            roomId = roomId,
            logId = null,
            moistureLogId = null,
            fileName = "photo.jpg",
            localPath = null,
            remoteUrl = "https://example.com/photo.jpg",
            thumbnailUrl = "https://example.com/photo_thumb.jpg",
            assemblyId = null,
            tusUploadId = null,
            fileSize = 1024,
            width = 1000,
            height = 800,
            mimeType = "image/jpeg",
            capturedAt = timestamp,
            createdAt = timestamp,
            updatedAt = timestamp,
            albums = null
        )

        every { checkpointStore.getCheckpoint(any()) } returns null
        val capturedDate = slot<Date>()
        every { checkpointStore.updateCheckpoint(any(), capture(capturedDate)) } just runs

        coEvery { api.getRoomPhotos(roomId, any(), any(), any(), any()) } returns roomPhotosResponse(photo)
        coEvery { localDataService.getPhotoByServerId(photo.id) } returns null
        coEvery { localDataService.savePhotos(any()) } just runs

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncRoomPhotos(projectId, roomId)

        val expected = DateUtils.parseApiDate(timestamp)!!
        assertThat(capturedDate.isCaptured).isTrue()
        assertThat(capturedDate.captured).isEqualTo(expected)
    }

    @Test
    fun `syncRoomPhotos does not advance checkpoint when no photos returned`() = runTest {
        val roomId = 88L
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        every { checkpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getRoomPhotos(roomId, any(), any(), any(), any()) } returns JsonObject()

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncRoomPhotos(projectId, roomId)

        io.mockk.verify(exactly = 0) { checkpointStore.updateCheckpoint(any(), any()) }
    }

    @Test
    fun `syncRoomPhotos ignores checkpoint when requested`() = runTest {
        val roomId = 90L
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        every { checkpointStore.getCheckpoint(any()) } returns Date(1_000_000)
        val updatedSince = slot<String?>()
        val photo = PhotoDto(
            id = 300L,
            uuid = "photo-300",
            projectId = projectId + 1, // mismatched on purpose
            roomId = roomId,
            logId = null,
            moistureLogId = null,
            fileName = "photo.jpg",
            localPath = null,
            remoteUrl = "https://example.com/photo.jpg",
            thumbnailUrl = "https://example.com/photo_thumb.jpg",
            assemblyId = null,
            tusUploadId = null,
            fileSize = 2048,
            width = 1000,
            height = 800,
            mimeType = "image/jpeg",
            capturedAt = "2025-06-01T10:15:30Z",
            createdAt = "2025-06-01T10:15:30Z",
            updatedAt = "2025-06-01T10:15:30Z",
            albums = null
        )

        coEvery {
            api.getRoomPhotos(roomId, any(), any(), any(), captureNullable(updatedSince))
        } returns roomPhotosResponse(photo)
        coEvery { localDataService.getPhotoByServerId(photo.id) } returns null
        coEvery { localDataService.savePhotos(any()) } just runs

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncRoomPhotos(projectId, roomId, ignoreCheckpoint = true)

        assertThat(updatedSince.isCaptured).isTrue()
        assertThat(updatedSince.captured).isNull()
    }

    @Test
    fun `syncRoomPhotos succeeds on 404 with zero items`() = runTest {
        val roomId = 91L
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val errorBody = "missing".toResponseBody("application/json".toMediaType())
        val errorResponse = Response.error<JsonObject>(404, errorBody)
        coEvery { api.getRoomPhotos(roomId, any(), any(), any(), any()) } throws HttpException(errorResponse)

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        val result = repository.syncRoomPhotos(projectId, roomId)

        assertThat(result.success).isTrue()
        assertThat(result.itemsSynced).isEqualTo(0)
        io.mockk.verify(exactly = 0) { checkpointStore.updateCheckpoint(any(), any()) }
    }

    @Test
    fun `syncRoomPhotos fetches multiple pages using meta`() = runTest {
        val roomId = 92L
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val first = PhotoDto(
            id = 401L,
            uuid = "photo-401",
            projectId = projectId,
            roomId = roomId,
            logId = null,
            moistureLogId = null,
            fileName = "first.jpg",
            localPath = null,
            remoteUrl = "https://example.com/first.jpg",
            thumbnailUrl = null,
            assemblyId = null,
            tusUploadId = null,
            fileSize = 1024,
            width = 800,
            height = 600,
            mimeType = "image/jpeg",
            capturedAt = "2025-06-01T10:15:30Z",
            createdAt = "2025-06-01T10:15:30Z",
            updatedAt = "2025-06-01T10:15:30Z",
            albums = null
        )
        val second = first.copy(
            id = 402L,
            uuid = "photo-402",
            fileName = "second.jpg",
            remoteUrl = "https://example.com/second.jpg"
        )

        coEvery { api.getRoomPhotos(roomId, any(), any(), any(), any()) } returnsMany listOf(
            pagedRoomPhotosResponse(currentPage = 1, lastPage = 2, photos = listOf(first)),
            pagedRoomPhotosResponse(currentPage = 2, lastPage = 2, photos = listOf(second))
        )
        coEvery { localDataService.getPhotoByServerId(any()) } returns null
        val savedPhotos = mutableListOf<List<OfflinePhotoEntity>>()
        coEvery { localDataService.savePhotos(capture(savedPhotos)) } just runs

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncRoomPhotos(projectId, roomId)

        val flattened = savedPhotos.flatten()
        assertThat(flattened.map { it.serverId }).containsAtLeastElementsIn(listOf(first.id, second.id))
        coVerify { api.getRoomPhotos(roomId, 1, any(), any(), any()) }
        coVerify { api.getRoomPhotos(roomId, 2, any(), any(), any()) }
    }

    @Test
    fun `syncRoomPhotos normalizes mismatched ids and refreshes placeholder snapshot`() = runTest {
        val localProjectId = 123L
        val roomId = 93L
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val photo = PhotoDto(
            id = 500L,
            uuid = "photo-500",
            projectId = 9999L, // mismatched
            roomId = 8888L, // mismatched
            logId = null,
            moistureLogId = null,
            fileName = "mismatch.jpg",
            localPath = null,
            remoteUrl = "https://example.com/mismatch.jpg",
            thumbnailUrl = null,
            assemblyId = null,
            tusUploadId = null,
            fileSize = 512,
            width = 400,
            height = 300,
            mimeType = "image/jpeg",
            capturedAt = "2025-06-01T10:15:30Z",
            createdAt = "2025-06-01T10:15:30Z",
            updatedAt = "2025-06-01T10:15:30Z",
            albums = null
        )

        coEvery { api.getRoomPhotos(roomId, any(), any(), any(), any()) } returns roomPhotosResponse(photo)
        coEvery { localDataService.getPhotoByServerId(photo.id) } returns null
        coEvery { localDataService.deleteLocalPendingRoomPhoto(localProjectId, roomId, any()) } returns 1
        coEvery { localDataService.refreshRoomPhotoSnapshot(roomId) } just runs
        val savedPhotos = mutableListOf<List<OfflinePhotoEntity>>()
        coEvery { localDataService.savePhotos(capture(savedPhotos)) } just runs

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncRoomPhotos(localProjectId, roomId)

        val saved = savedPhotos.flatten().single()
        assertThat(saved.projectId).isEqualTo(localProjectId)
        assertThat(saved.roomId).isEqualTo(roomId)
        coVerify { localDataService.deleteLocalPendingRoomPhoto(localProjectId, roomId, "mismatch.jpg") }
        coVerify { localDataService.refreshRoomPhotoSnapshot(roomId) }
    }

    @Test
    fun `syncRoomWorkScopes saves scopes with local project id`() = runTest {
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val localProjectId = 12L
        val serverProjectId = 999L
        val roomId = 123L
        val scopeDto = WorkScopeDto(
            id = 55L,
            uuid = "scope-uuid",
            projectId = serverProjectId,
            roomId = roomId,
            name = "Demo Scope",
            description = "From catalog",
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-02T00:00:00Z"
        )

        everyLog()
        coEvery { api.getRoomWorkScope(roomId) } returns PaginatedResponse(data = listOf(scopeDto))
        val savedScopes = mutableListOf<List<OfflineWorkScopeEntity>>()
        coEvery { localDataService.saveWorkScopes(capture(savedScopes)) } just runs

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        val savedCount = repository.syncRoomWorkScopes(localProjectId, roomId)

        assertThat(savedCount).isEqualTo(1)
        val savedScope = savedScopes.single().single()
        assertThat(savedScope.projectId).isEqualTo(localProjectId)
        assertThat(savedScope.serverId).isEqualTo(scopeDto.id)
        assertThat(savedScope.roomId).isEqualTo(roomId)
    }

    @Test
    fun `syncRoomWorkScopes returns zero on empty response`() = runTest {
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        coEvery { api.getRoomWorkScope(any()) } returns PaginatedResponse(data = emptyList())

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        val count = repository.syncRoomWorkScopes(projectId, roomId = 1L)

        assertThat(count).isEqualTo(0)
        coVerify(exactly = 0) { localDataService.saveWorkScopes(any()) }
    }

    @Test
    fun `syncDeletedRecords stores server provided timestamp`() = runTest {
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)
        every { checkpointStore.getCheckpoint(any()) } returns null

        val body = DeletedRecordsResponse(projects = listOf(1L))
        val headerDate = "Wed, 06 Nov 2024 15:20:00 GMT"
        val headers = Headers.headersOf("Date", headerDate)

        coEvery { api.getDeletedRecords(any(), any()) } returns Response.success(body, headers)

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncDeletedRecords()

        val expected = DateUtils.parseHttpDate(headerDate)!!
        io.mockk.verify {
            checkpointStore.updateCheckpoint("deleted_records_global", match { it == expected })
        }
    }

    @Test
    fun `syncDeletedRecords clamps future checkpoint before requesting`() = runTest {
        everyLog()
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val lastServerDate = Date(1_700_000_000_000)
        val futureDate = Date(lastServerDate.time + TimeUnit.DAYS.toMillis(365))
        every { checkpointStore.getCheckpoint("deleted_records_server_date") } returns lastServerDate
        every { checkpointStore.getCheckpoint("deleted_records_global") } returns futureDate

        val sinceSlot = slot<String>()
        coEvery { api.getDeletedRecords(capture(sinceSlot), any()) } returns Response.success(
            DeletedRecordsResponse(),
            Headers.headersOf()
        )

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncDeletedRecords()

        val parsedSince = DateUtils.parseApiDate(sinceSlot.captured)!!
        assertThat(parsedSince.time).isEqualTo(lastServerDate.time)
        coVerify {
            checkpointStore.updateCheckpoint(
                "deleted_records_global",
                match { it == lastServerDate }
            )
        }
    }

    @Test
    fun `syncDeletedRecords does not advance checkpoint without server date`() = runTest {
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)
        every { checkpointStore.getCheckpoint(any()) } returns null

        coEvery { api.getDeletedRecords(any(), any()) } returns Response.success(
            DeletedRecordsResponse(),
            Headers.headersOf()
        )

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncDeletedRecords()

        io.mockk.verify(exactly = 0) { checkpointStore.updateCheckpoint("deleted_records_global", any()) }
        io.mockk.verify(exactly = 0) { checkpointStore.updateCheckpoint("deleted_records_server_date", any()) }
    }

    @Test
    fun `syncDeletedRecords filters blank types before calling api`() = runTest {
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)
        every { checkpointStore.getCheckpoint(any()) } returns null

        val sinceSlot = slot<String>()
        val typesSlot = slot<List<String>>()
        coEvery { api.getDeletedRecords(capture(sinceSlot), capture(typesSlot)) } returns Response.success(
            DeletedRecordsResponse(),
            Headers.headersOf()
        )

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncDeletedRecords(listOf("projects", " ", "", "rooms"))

        assertThat(typesSlot.captured).containsExactly("projects", "rooms").inOrder()
        assertThat(sinceSlot.captured).isNotEmpty()
    }

    @Test
    fun `syncDeletedRecords applies deletions for all entity types`() = runTest {
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)
        every { checkpointStore.getCheckpoint(any()) } returns null

        val body = DeletedRecordsResponse(
            projects = listOf(1L),
            rooms = listOf(2L),
            locations = listOf(3L),
            photos = listOf(4L),
            notes = listOf(5L),
            equipment = listOf(6L),
            damageMaterials = listOf(7L),
            atmosphericLogs = listOf(8L),
            moistureLogs = listOf(9L),
            workScopeActions = listOf(10L)
        )
        coEvery { api.getDeletedRecords(any(), any()) } returns Response.success(body, Headers.headersOf())

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncDeletedRecords()

        coVerify { localDataService.markProjectsDeleted(body.projects) }
        coVerify { localDataService.markRoomsDeleted(body.rooms) }
        coVerify { localDataService.markLocationsDeleted(body.locations) }
        coVerify { localDataService.markPhotosDeleted(body.photos) }
        coVerify { localDataService.markNotesDeleted(body.notes) }
        coVerify { localDataService.markEquipmentDeleted(body.equipment) }
        coVerify { localDataService.markDamagesDeleted(body.damageMaterials) }
        coVerify { localDataService.markAtmosphericLogsDeleted(body.atmosphericLogs) }
        coVerify { localDataService.markMoistureLogsDeleted(body.moistureLogs) }
        coVerify { localDataService.markWorkScopesDeleted(body.workScopeActions) }
    }

    @Test
    fun `syncProjectMetadata does not recreate pending notes`() = runTest {
        val api = mockk<OfflineSyncApi>(relaxed = true)
        val localDataService = mockk<LocalDataService>(relaxed = true)
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)
        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)

        val projectId = 7L
        val pendingNote = OfflineNoteEntity(
            noteId = 11L,
            serverId = 22L,
            uuid = "note-uuid",
            projectId = projectId,
            roomId = 5L,
            userId = 2L,
            content = "offline update",
            createdAt = Date(),
            updatedAt = Date(),
            syncStatus = SyncStatus.PENDING,
            isDirty = true
        )

        coEvery { localDataService.getProject(projectId) } returns OfflineProjectEntity(
            projectId = projectId,
            serverId = projectId,
            uuid = "project-uuid",
            title = "Project",
            status = "wip",
            companyId = 1L
        )
        every { localDataService.observeDamages(projectId) } returns flowOf(emptyList<OfflineDamageEntity>())
        coEvery { localDataService.getPendingNotes(projectId) } returns listOf(pendingNote)
        coEvery { api.getProjectNotes(projectId, any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectEquipment(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectDamageMaterials(projectId, any<String>()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectAtmosphericLogs(projectId, any<String>()) } returns PaginatedResponse(data = emptyList())

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler,
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncProjectMetadata(projectId)

        coVerify(exactly = 0) { api.updateNote(any(), any()) }
        coVerify(exactly = 0) { api.createProjectNote(any(), any()) }
    }

    @Test
    fun `syncProjectMetadata fails when project is not synced`() = runTest {
        val api = mockk<OfflineSyncApi>(relaxed = true)
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(any()) } returns flowOf(emptyList<OfflineDamageEntity>())
        coEvery { localDataService.getProject(0) } returns null
        coEvery { localDataService.getPendingNotes(any()) } returns emptyList()
        coEvery { localDataService.getPendingEquipment(any()) } returns emptyList()
        coEvery { localDataService.getPendingMoistureLogs(any()) } returns emptyList()
        coEvery { localDataService.getPendingPhotoDeletions(any()) } returns emptyList()
        coEvery { localDataService.getPendingRoomDeletions(any()) } returns emptyList()

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = mockk(relaxed = true),
            syncCheckpointStore = mockk(relaxed = true),
            roomTypeRepository = mockk(relaxed = true)
        )

        val result = repository.syncProjectMetadata(0)

        assertThat(result).isInstanceOf(Failure::class.java)
    }

    @Test
    fun `syncProjectMetadata falls back to per-room damages when project response lacks room ids`() = runTest {
        val projectId = 55L
        val roomId = 77L
        val api = mockk<OfflineSyncApi>()
        val damageWithoutRoom = DamageMaterialDto(
            id = 1L,
            uuid = "damage-1",
            projectId = projectId,
            roomId = null,
            title = "Damage",
            description = null,
            severity = null,
            createdAt = "2025-05-01T00:00:00Z",
            updatedAt = "2025-05-02T00:00:00Z"
        )
        val damageWithRoom = damageWithoutRoom.copy(id = 2L, roomId = roomId)

        coEvery { api.getProjectNotes(projectId, any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectEquipment(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectDamageMaterials(projectId, any<String>()) } returns PaginatedResponse(
            data = listOf(damageWithoutRoom)
        )
        coEvery { api.getProjectAtmosphericLogs(projectId, any<String>()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomWorkScope(roomId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomMoistureLogs(roomId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomDamageMaterials(roomId) } returns PaginatedResponse(data = listOf(damageWithRoom))

        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(projectId) } returns flowOf(
            listOf(
                OfflineDamageEntity(
                    uuid = "local-damage",
                    projectId = projectId,
                    title = "Local damage"
                )
            )
        )
        coEvery { localDataService.getProject(projectId) } returns OfflineProjectEntity(
            projectId = projectId,
            serverId = projectId,
            uuid = "project-uuid",
            title = "Project",
            status = "wip",
            companyId = companyId
        )
        coEvery { localDataService.getServerRoomIdsForProject(projectId) } returns listOf(roomId)
        coEvery { localDataService.getPendingNotes(projectId) } returns emptyList()
        coEvery { localDataService.getPendingEquipment(projectId) } returns emptyList()
        coEvery { localDataService.getPendingMoistureLogs(projectId) } returns emptyList()
        coEvery { localDataService.getPendingPhotoDeletions(projectId) } returns emptyList()
        coEvery { localDataService.getPendingRoomDeletions(projectId) } returns emptyList()

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = mockk(relaxed = true),
            syncCheckpointStore = mockk(relaxed = true),
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncProjectMetadata(projectId)

        coVerify { api.getRoomDamageMaterials(roomId) }
    }

    @Test
    fun `syncProjectMetadata advances checkpoints for notes damages and atmos logs`() = runTest {
        val projectId = 66L
        val api = mockk<OfflineSyncApi>()
        val noteUpdatedAt = "2025-05-10T00:00:00Z"
        val damageUpdatedAt = "2025-05-11T00:00:00Z"
        val atmosUpdatedAt = "2025-05-12T00:00:00Z"

        coEvery { api.getProjectNotes(projectId, any(), any(), any()) } returns PaginatedResponse(
            data = listOf(
                NoteDto(
                    id = 1L,
                    uuid = "note",
                    projectId = projectId,
                    roomId = null,
                    userId = 1L,
                    body = "note",
                    photoId = null,
                    categoryId = null,
                    createdAt = noteUpdatedAt,
                    updatedAt = noteUpdatedAt
                )
            )
        )
        coEvery { api.getProjectEquipment(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectDamageMaterials(projectId, any<String>()) } returns PaginatedResponse(
            data = listOf(
                DamageMaterialDto(
                    id = 2L,
                    uuid = "damage",
                    projectId = projectId,
                    roomId = 10L,
                    title = "damage",
                    description = null,
                    severity = null,
                    createdAt = damageUpdatedAt,
                    updatedAt = damageUpdatedAt
                )
            )
        )
        coEvery { api.getProjectAtmosphericLogs(projectId, any<String>()) } returns PaginatedResponse(
            data = listOf(
                AtmosphericLogDto(
                    id = 3L,
                    uuid = "log",
                    projectId = projectId,
                    roomId = null,
                    date = atmosUpdatedAt,
                    relativeHumidity = 1.0,
                    temperature = 1.0,
                    dewPoint = null,
                    gpp = null,
                    pressure = null,
                    windSpeed = null,
                    isExternal = null,
                    isInlet = null,
                    inletId = null,
                    outletId = null,
                    photoUrl = null,
                    photoLocalPath = null,
                    photoUploadStatus = null,
                    photoAssemblyId = null,
                    createdAt = atmosUpdatedAt,
                    updatedAt = atmosUpdatedAt
                )
            )
        )
        coEvery { api.getRoomWorkScope(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomMoistureLogs(any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomDamageMaterials(any()) } returns PaginatedResponse(data = emptyList())

        val checkpointStore = mockk<SyncCheckpointStore>(relaxed = true)
        every { checkpointStore.getCheckpoint(any()) } returns null
        val localDataService = mockk<LocalDataService>(relaxed = true)
        every { localDataService.observeDamages(projectId) } returns flowOf(
            listOf(
                OfflineDamageEntity(
                    uuid = "damage",
                    projectId = projectId,
                    roomId = 10L,
                    title = "damage"
                )
            )
        )
        coEvery { localDataService.getProject(projectId) } returns OfflineProjectEntity(
            projectId = projectId,
            serverId = projectId,
            uuid = "project-uuid",
            title = "Project",
            status = "wip",
            companyId = companyId
        )
        coEvery { localDataService.getServerRoomIdsForProject(projectId) } returns listOf(10L)
        coEvery { localDataService.getPendingNotes(projectId) } returns emptyList()
        coEvery { localDataService.getPendingEquipment(projectId) } returns emptyList()
        coEvery { localDataService.getPendingMoistureLogs(projectId) } returns emptyList()
        coEvery { localDataService.getPendingPhotoDeletions(projectId) } returns emptyList()
        coEvery { localDataService.getPendingRoomDeletions(projectId) } returns emptyList()

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = mockk(relaxed = true),
            syncCheckpointStore = checkpointStore,
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.syncProjectMetadata(projectId)

        val notesKey = "project_notes_$projectId"
        val damagesKey = "project_damages_$projectId"
        val atmosKey = "project_atmos_logs_$projectId"
        io.mockk.verify { checkpointStore.updateCheckpoint(notesKey, match { it == DateUtils.parseApiDate(noteUpdatedAt) }) }
        io.mockk.verify { checkpointStore.updateCheckpoint(damagesKey, match { it == DateUtils.parseApiDate(damageUpdatedAt) }) }
        io.mockk.verify { checkpointStore.updateCheckpoint(atmosKey, match { it == DateUtils.parseApiDate(atmosUpdatedAt) }) }
    }

    @Test
    fun `deleteProject enqueues delete with lock from project updatedAt`() = runTest {
        val localProjectId = 1L
        val serverId = 99L
        val serverUpdatedAt = "2025-12-21T00:08:11.123456Z"
        val parsedUpdatedAt = DateUtils.parseApiDate(serverUpdatedAt) ?: error("Invalid date")

        val api = mockk<OfflineSyncApi>(relaxed = true)
        val operationSlot = slot<OfflineSyncQueueEntity>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        coEvery { localDataService.getProject(localProjectId) } returns null
        coEvery { localDataService.getAllProjects() } returns listOf(
            OfflineProjectEntity(
                projectId = localProjectId,
                serverId = serverId,
                uuid = "project-uuid",
                title = "Test Project",
                status = "wip",
                companyId = companyId,
                updatedAt = parsedUpdatedAt
            )
        )
        coEvery { localDataService.getProject(localProjectId) } returns OfflineProjectEntity(
            projectId = localProjectId,
            serverId = serverId,
            uuid = "project-uuid",
            title = "Test Project",
            status = "wip",
            companyId = companyId,
            updatedAt = parsedUpdatedAt
        )
        coEvery { localDataService.getSyncOperationForEntity("project", localProjectId, any()) } returns null
        coEvery { localDataService.enqueueSyncOperation(capture(operationSlot)) } just runs
        coEvery { localDataService.removeSyncOperationsForEntity("project", localProjectId) } just runs

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = mockk(relaxed = true),
            syncCheckpointStore = mockk(relaxed = true),
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.deleteProject(localProjectId)

        val payloadJson = Gson().fromJson(
            String(operationSlot.captured.payload, Charsets.UTF_8),
            JsonObject::class.java
        )
        assertThat(payloadJson["lockUpdatedAt"].asString).isEqualTo(DateUtils.formatApiDate(parsedUpdatedAt))
        assertThat(operationSlot.captured.entityType).isEqualTo("project")
        assertThat(operationSlot.captured.operationType).isEqualTo(SyncOperationType.DELETE)
        coVerify { localDataService.deleteProject(localProjectId) }
    }

    @Test
    fun `deleteProject skips API when project is local only`() = runTest {
        val localProjectId = 11L

        val api = mockk<OfflineSyncApi>()
        everyLog()

        val localDataService = mockk<LocalDataService>(relaxed = true)
        coEvery { localDataService.getProject(localProjectId) } returns null
        coEvery { localDataService.getAllProjects() } returns listOf(
            OfflineProjectEntity(
                projectId = localProjectId,
                serverId = null,
                uuid = "local-uuid",
                title = "Local Project",
                status = "wip",
                companyId = companyId
            )
        )

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = mockk(relaxed = true),
            syncCheckpointStore = mockk(relaxed = true),
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.deleteProject(localProjectId)

        coVerify(exactly = 0) { localDataService.enqueueSyncOperation(any()) }
        coVerify { localDataService.deleteProject(localProjectId) }
    }

    @Test
    fun `deleteProject preserves existing lock when re-queued`() = runTest {
        val localProjectId = 21L
        val serverId = 120L
        val existingLock = "2025-12-21T00:08:11+00:00"

        val api = mockk<OfflineSyncApi>(relaxed = true)
        val existingPayload = JsonObject().apply {
            addProperty("lockUpdatedAt", existingLock)
        }
        val existingOperation = OfflineSyncQueueEntity(
            operationId = "project-$localProjectId-existing",
            entityType = "project",
            entityId = localProjectId,
            entityUuid = "retry-uuid",
            operationType = SyncOperationType.DELETE,
            payload = Gson().toJson(existingPayload).toByteArray(Charsets.UTF_8),
            priority = SyncPriority.HIGH
        )

        val operationSlot = slot<OfflineSyncQueueEntity>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
        coEvery { localDataService.getAllProjects() } returns listOf(
            OfflineProjectEntity(
                projectId = localProjectId,
                serverId = serverId,
                uuid = "retry-uuid",
                title = "Retry Project",
                status = "wip",
                companyId = companyId
            )
        )
        coEvery { localDataService.getProject(localProjectId) } returns OfflineProjectEntity(
            projectId = localProjectId,
            serverId = serverId,
            uuid = "retry-uuid",
            title = "Retry Project",
            status = "wip",
            companyId = companyId
        )
        coEvery { localDataService.getSyncOperationForEntity("project", localProjectId, SyncStatus.PENDING) } returns existingOperation
        coEvery { localDataService.enqueueSyncOperation(capture(operationSlot)) } just runs
        coEvery { localDataService.removeSyncOperationsForEntity("project", localProjectId) } just runs

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = mockk(relaxed = true),
            syncCheckpointStore = mockk(relaxed = true),
            roomTypeRepository = mockk(relaxed = true)
        )

        repository.deleteProject(localProjectId)

        val payloadJson = Gson().fromJson(
            String(operationSlot.captured.payload, Charsets.UTF_8),
            JsonObject::class.java
        )
        assertThat(payloadJson["lockUpdatedAt"].asString).isEqualTo(existingLock)
        coVerify { localDataService.deleteProject(localProjectId) }
    }

    @Test
    fun `deleteProject throws when local project is missing`() = runTest {
        val api = mockk<OfflineSyncApi>(relaxed = true)
        val localDataService = mockk<LocalDataService>(relaxed = true)
        coEvery { localDataService.getAllProjects() } returns emptyList()

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = mockk(relaxed = true),
            syncCheckpointStore = mockk(relaxed = true),
            roomTypeRepository = mockk(relaxed = true)
        )

        val error = try {
            repository.deleteProject(123L)
            null
        } catch (e: Exception) {
            e
        }
        assertThat(error).isNotNull()
    }

    // Conflict handling is exercised at queue-processing time, not during local delete.
}

private fun everyLog() {
    // No-op: android.util.Log is stubbed in unit tests.
}

private fun roomPhotosResponse(vararg photos: PhotoDto): JsonObject {
    val gson = Gson()
    val roomPhotos = photos.map { photo ->
        RoomPhotoDto(
            id = photo.id,
            uuid = photo.uuid,
            createdAt = photo.createdAt,
            updatedAt = photo.updatedAt,
            photo = RoomPhotoFileDto(
                id = photo.id,
                uuid = photo.uuid,
                projectId = photo.projectId,
                roomId = photo.roomId,
                logId = photo.logId,
                moistureLogId = photo.moistureLogId,
                fileName = photo.fileName,
                localPath = photo.localPath,
                remoteUrl = photo.remoteUrl,
                thumbnailUrl = photo.thumbnailUrl,
                assemblyId = photo.assemblyId,
                tusUploadId = photo.tusUploadId,
                fileSize = photo.fileSize,
                width = photo.width,
                height = photo.height,
                mimeType = photo.mimeType,
                capturedAt = photo.capturedAt,
                createdAt = photo.createdAt,
                updatedAt = photo.updatedAt,
                albums = photo.albums
            )
        )
    }
    val meta = JsonObject().apply {
        addProperty("current_page", 1)
        addProperty("last_page", 1)
    }
    return JsonObject().apply {
        add("data", gson.toJsonTree(roomPhotos).asJsonArray)
        add("meta", meta)
    }
}

private fun pagedRoomPhotosResponse(
    currentPage: Int,
    lastPage: Int,
    photos: List<PhotoDto>
): JsonObject {
    val gson = Gson()
    val roomPhotos = photos.map { photo ->
        RoomPhotoDto(
            id = photo.id,
            uuid = photo.uuid,
            createdAt = photo.createdAt,
            updatedAt = photo.updatedAt,
            photo = RoomPhotoFileDto(
                id = photo.id,
                uuid = photo.uuid,
                projectId = photo.projectId,
                roomId = photo.roomId,
                logId = photo.logId,
                moistureLogId = photo.moistureLogId,
                fileName = photo.fileName,
                localPath = photo.localPath,
                remoteUrl = photo.remoteUrl,
                thumbnailUrl = photo.thumbnailUrl,
                assemblyId = photo.assemblyId,
                tusUploadId = photo.tusUploadId,
                fileSize = photo.fileSize,
                width = photo.width,
                height = photo.height,
                mimeType = photo.mimeType,
                capturedAt = photo.capturedAt,
                createdAt = photo.createdAt,
                updatedAt = photo.updatedAt,
                albums = photo.albums
            )
        )
    }
    val meta = JsonObject().apply {
        addProperty("current_page", currentPage)
        addProperty("last_page", lastPage)
    }
    return JsonObject().apply {
        add("data", gson.toJsonTree(roomPhotos).asJsonArray)
        add("meta", meta)
    }
}
