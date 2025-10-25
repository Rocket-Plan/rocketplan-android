package com.example.rocketplan_android.data.repository

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
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
import io.mockk.just
import io.mockk.mockk
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
        coEvery { api.getProjectProperties(projectId) } returns emptyList()
        coEvery { api.getPropertyLevels(any()) } returns emptyList()
        coEvery { api.getPropertyLocations(any()) } returns emptyList()
        coEvery { api.getRoomsForLocation(any()) } returns emptyList()
        coEvery { api.getRoomDetail(any()) } returns RoomDto(
            id = 100L,
            uuid = "room",
            projectId = projectId,
            locationId = null,
            title = "Basement",
            roomType = null,
            level = "Main",
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
        coEvery { api.getProjectDamageMaterials(projectId) } returns emptyList()
        coEvery { api.getProjectNotes(projectId) } returns emptyList()
        coEvery { api.getProjectUsers(projectId) } returns emptyList()
        coEvery { api.getProjectEquipment(projectId) } returns emptyList()

        val localDataService = mockk<LocalDataService>(relaxed = true)
        val savedPhotos = mutableListOf<OfflinePhotoEntity>()
        coEvery { localDataService.savePhotos(capture(savedPhotos)) } just runs

        val scheduler = mockk<PhotoCacheScheduler>(relaxed = true)

        val repository = OfflineSyncRepository(
            api = api,
            localDataService = localDataService,
            photoCacheScheduler = scheduler
        )

        repository.syncProjectGraph(projectId)

        coVerify { api.getProjectLocationPhotos(projectId, 1) }
        assertThat(savedPhotos).isNotEmpty()
        val mapped = savedPhotos.last()
        assertThat(mapped.remoteUrl).isEqualTo("gallery.jpg")
        assertThat(mapped.thumbnailUrl).isEqualTo("small.jpg")
        assertThat(mapped.roomId).isEqualTo(100L)
        coVerify { scheduler.schedulePrefetch() }
    }
}
