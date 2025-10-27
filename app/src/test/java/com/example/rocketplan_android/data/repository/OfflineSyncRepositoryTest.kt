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
import com.example.rocketplan_android.data.model.offline.EquipmentDto
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.MoistureLogDto
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.data.model.offline.NoteableDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.PhotoDto
import com.example.rocketplan_android.data.model.offline.ProjectDetailDto
import com.example.rocketplan_android.data.model.offline.ProjectDto
import com.example.rocketplan_android.data.model.offline.ProjectPhotoListingDto
import com.example.rocketplan_android.data.model.offline.PropertyDto
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.data.model.offline.UserDto
import com.example.rocketplan_android.data.model.offline.WorkScopeDto
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

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
        coEvery { api.getPropertyLevels(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getPropertyLocations(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomsForLocation(any(), any(), any()) } returns PaginatedResponse(data = emptyList())
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
        coEvery { api.getRoomPhotos(any(), any(), any()) } returns emptyList()
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
        coEvery { api.getProjectNotes(projectId) } returns emptyList()
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

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler
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
        coEvery { api.getPropertyLevels(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getPropertyLocations(any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomsForLocation(any(), any(), any()) } returns PaginatedResponse(data = emptyList())
        coEvery { api.getRoomPhotos(any(), any(), any()) } returns emptyList()
        coEvery { api.getRoomPhotos(roomId, any(), any()) } returns listOf(roomPhotoDto)
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
        coEvery { api.getProjectNotes(projectId) } returns emptyList()
        coEvery { api.getProjectUsers(projectId) } returns emptyList()
        coEvery { api.getProjectEquipment(projectId) } returns emptyList()

        mockkStatic(Log::class)
        everyLog()

        val localDataService = mockk<LocalDataService>(relaxed = true)
        val savedPhotos = mutableListOf<List<OfflinePhotoEntity>>()
        coEvery { localDataService.savePhotos(capture(savedPhotos)) } just runs
        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler
        )

        repository.syncProjectGraph(projectId)

        coVerify { api.getRoomPhotos(roomId, any(), any()) }
        val flattenedPhotos = savedPhotos.flatten()
        assertThat(flattenedPhotos.any { it.serverId == roomPhotoDto.id && it.roomId == roomId }).isTrue()
        coVerify { scheduler.schedulePrefetch() }
    }
}

private fun everyLog() {
    every { Log.d(any<String>(), any<String>()) } returns 0
    every { Log.d(any<String>(), any<String>(), any<Throwable>()) } returns 0
    every { Log.i(any<String>(), any<String>()) } returns 0
    every { Log.w(any<String>(), any<String>()) } returns 0
    every { Log.e(any<String>(), any<String>()) } returns 0
}
