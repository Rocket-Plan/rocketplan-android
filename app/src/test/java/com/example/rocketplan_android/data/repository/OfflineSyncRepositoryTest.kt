package com.example.rocketplan_android.data.repository

import android.util.Log
import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineAlbumPhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
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
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSyncRepositoryTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val projectId = 42L

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
            updatedAt = "2025-05-06T18:01:14.000000Z"
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
        coEvery { api.getProjectDetail(projectId) } returns ProjectDetailDto(
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
        coEvery { api.getProjectProperties(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProperty(10L) } returns propertyDto
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
            roomType = null,
            level = null,
            squareFootage = null,
            isAccessible = true,
            createdAt = "2025-05-01T00:00:00Z",
            updatedAt = "2025-05-01T00:00:00Z"
        )
        coEvery { api.getRoomPhotos(any(), any(), any(), any(), any()) } returns JsonObject()
        coEvery { api.getRoomAtmosphericLogs(any()) } returns emptyList()
        coEvery { api.getRoomMoistureLogs(any()) } returns emptyList()
        coEvery { api.getRoomDamageMaterials(any()) } returns emptyList()
        coEvery { api.getRoomWorkScope(any()) } returns emptyList()
        coEvery { api.getRoomEquipment(any()) } returns emptyList()
        coEvery { api.getProjectAtmosphericLogs(projectId) } returns emptyList()
        coEvery { api.getProjectFloorPhotos(projectId, any()) } returns PaginatedResponse(
            data = emptyList()
        )
        coEvery { api.getProjectLocationPhotos(projectId, any()) } returns PaginatedResponse(
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
        coEvery { api.getProjectAlbums(projectId, any()) } answers {
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
        coEvery { api.getProjectDamageMaterials(projectId) } returns emptyList()
        coEvery { api.getProjectNotes(projectId, any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectUsers(projectId) } returns emptyList()
        coEvery { api.getProjectEquipment(projectId) } returns emptyList()

        mockkStatic(Log::class)
        everyLog()

        val localDataService = mockk<LocalDataService>(relaxed = true)
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

        coVerify { api.getProjectLocationPhotos(projectId, 1) }
        coVerify { api.getProjectAlbums(projectId, 1) }
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
        assertThat(albumEntity.photoCount).isEqualTo(1)
        assertThat(albumEntity.thumbnailUrl).contains("album_thumb")

        val flattenedAlbumPhotos = savedAlbumPhotos.flatten()
        assertThat(flattenedAlbumPhotos).isNotEmpty()
        val albumPhotoLink = flattenedAlbumPhotos.first()
        assertThat(albumPhotoLink.albumId).isEqualTo(300L)
        assertThat(albumPhotoLink.photoServerId).isEqualTo(999L)
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
            updatedAt = "2025-05-06T18:01:14.000000Z"
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
        coEvery { api.getProjectDetail(projectId) } returns ProjectDetailDto(
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
        coEvery { api.getProjectProperties(projectId) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProperty(10L) } returns propertyDto
        coEvery { api.getPropertyLevels(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getPropertyLocations(any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomsForLocation(any(), any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomPhotos(any(), any(), any(), any(), any()) } returns JsonObject()
        coEvery { api.getRoomPhotos(roomId, any(), any(), any(), any()) } returns roomPhotosResponse(roomPhotoDto)
        coEvery { api.getRoomAtmosphericLogs(any()) } returns emptyList()
        coEvery { api.getRoomMoistureLogs(any()) } returns emptyList()
        coEvery { api.getRoomDamageMaterials(any()) } returns emptyList()
        coEvery { api.getRoomWorkScope(any()) } returns emptyList()
        coEvery { api.getRoomEquipment(any()) } returns emptyList()
        coEvery { api.getProjectAtmosphericLogs(projectId) } returns emptyList()
        coEvery { api.getProjectFloorPhotos(projectId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectLocationPhotos(projectId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectUnitPhotos(projectId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectAlbums(projectId, any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectDamageMaterials(projectId) } returns emptyList()
        coEvery { api.getProjectNotes(projectId, any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getProjectUsers(projectId) } returns emptyList()
        coEvery { api.getProjectEquipment(projectId) } returns emptyList()

        mockkStatic(Log::class)
        everyLog()

        val localDataService = mockk<LocalDataService>(relaxed = true)
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
    fun `syncRoomPhotos updates checkpoint using latest server timestamp`() = runTest {
        val roomId = 77L
        val timestamp = "2025-06-01T10:15:30Z"
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
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
    fun `syncDeletedRecords stores server provided timestamp`() = runTest {
        val api = mockk<OfflineSyncApi>()
        val localDataService = mockk<LocalDataService>(relaxed = true)
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
}

private fun everyLog() {
    every { Log.d(any<String>(), any<String>()) } returns 0
    every { Log.d(any<String>(), any<String>(), any<Throwable>()) } returns 0
    every { Log.i(any<String>(), any<String>()) } returns 0
    every { Log.w(any<String>(), any<String>()) } returns 0
    every { Log.e(any<String>(), any<String>()) } returns 0
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
                fileName = photo.fileName,
                remoteUrl = photo.remoteUrl,
                thumbnailUrl = photo.thumbnailUrl,
                mimeType = photo.mimeType,
                capturedAt = photo.capturedAt,
                createdAt = photo.createdAt,
                updatedAt = photo.updatedAt
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
