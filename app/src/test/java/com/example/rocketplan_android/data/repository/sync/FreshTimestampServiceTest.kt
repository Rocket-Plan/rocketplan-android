package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.ProjectDetailResourceResponse
import com.example.rocketplan_android.data.model.PropertyResourceResponse
import com.example.rocketplan_android.data.model.offline.LocationDto
import com.example.rocketplan_android.data.model.offline.PaginatedResponse
import com.example.rocketplan_android.data.model.offline.RoomDto
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreshTimestampServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val service = FreshTimestampService(api, localDataService, testDispatcher)

    // ===== fetchFreshTimestamp tests =====

    @Test
    fun `fetchFreshTimestamp project type returns updatedAt from API`() = runTest {
        val projectDetailResponse = mockk<ProjectDetailResourceResponse>(relaxed = true) {
            every { data } returns mockk(relaxed = true) {
                every { updatedAt } returns "2026-01-30T12:00:00.000000Z"
            }
        }
        coEvery { api.getProjectDetail(1000L) } returns projectDetailResponse

        val result = service.fetchFreshTimestamp("project", 1000L)

        assertThat(result).isEqualTo("2026-01-30T12:00:00.000000Z")
        coVerify { api.getProjectDetail(1000L) }
    }

    @Test
    fun `fetchFreshTimestamp property type returns updatedAt from API`() = runTest {
        val propertyResponse = mockk<PropertyResourceResponse>(relaxed = true) {
            every { data } returns mockk(relaxed = true) {
                every { updatedAt } returns "2026-01-30T14:00:00.000000Z"
            }
        }
        coEvery { api.getProperty(2000L) } returns propertyResponse

        val result = service.fetchFreshTimestamp("property", 2000L)

        assertThat(result).isEqualTo("2026-01-30T14:00:00.000000Z")
        coVerify { api.getProperty(2000L) }
    }

    @Test
    fun `fetchFreshTimestamp room type returns updatedAt from API`() = runTest {
        val roomDto = mockk<RoomDto>(relaxed = true) {
            every { updatedAt } returns "2026-01-30T16:00:00.000000Z"
        }
        coEvery { api.getRoomDetail(4000L) } returns roomDto

        val result = service.fetchFreshTimestamp("room", 4000L)

        assertThat(result).isEqualTo("2026-01-30T16:00:00.000000Z")
        coVerify { api.getRoomDetail(4000L) }
    }

    @Test
    fun `fetchFreshTimestamp location type resolves chain and returns updatedAt`() = runTest {
        val location = PushHandlerTestFixtures.createLocation(
            locationId = 300L,
            serverId = 3000L,
            projectId = 100L
        )
        val project = PushHandlerTestFixtures.createProject(
            projectId = 100L,
            propertyId = 200L
        )
        val property = PushHandlerTestFixtures.createProperty(
            propertyId = 200L,
            serverId = 2000L
        )

        coEvery { localDataService.getLocationByServerId(3000L) } returns location
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getProperty(200L) } returns property

        val locationDto = mockk<LocationDto>(relaxed = true) {
            every { id } returns 3000L
            every { updatedAt } returns "2026-01-30T18:00:00.000000Z"
        }
        val locationsResponse = mockk<PaginatedResponse<LocationDto>> {
            every { data } returns listOf(locationDto)
        }
        coEvery { api.getPropertyLocations(2000L) } returns locationsResponse

        val result = service.fetchFreshTimestamp("location", 3000L)

        assertThat(result).isEqualTo("2026-01-30T18:00:00.000000Z")
        coVerify { localDataService.getLocationByServerId(3000L) }
        coVerify { localDataService.getProject(100L) }
        coVerify { localDataService.getProperty(200L) }
        coVerify { api.getPropertyLocations(2000L) }
    }

    @Test
    fun `fetchFreshTimestamp location type with no localDataService returns null`() = runTest {
        val serviceWithoutLocal = FreshTimestampService(api, null, testDispatcher)

        val result = serviceWithoutLocal.fetchFreshTimestamp("location", 3000L)

        assertThat(result).isNull()
        coVerify(exactly = 0) { api.getPropertyLocations(any()) }
    }

    @Test
    fun `fetchFreshTimestamp unsupported type returns null`() = runTest {
        val result = service.fetchFreshTimestamp("unknown_entity", 9999L)

        assertThat(result).isNull()
    }

    @Test
    fun `fetchFreshTimestamp API error returns null and does not throw`() = runTest {
        coEvery { api.getProjectDetail(any()) } throws RuntimeException("Network error")

        val result = service.fetchFreshTimestamp("project", 1000L)

        assertThat(result).isNull()
    }

    // ===== fetchFreshTimestamps (batch) test =====

    @Test
    fun `fetchFreshTimestamps returns map of successful fetches only`() = runTest {
        // ID 1000 succeeds
        val successResponse = mockk<ProjectDetailResourceResponse>(relaxed = true) {
            every { data } returns mockk(relaxed = true) {
                every { updatedAt } returns "2026-01-30T12:00:00.000000Z"
            }
        }
        coEvery { api.getProjectDetail(1000L) } returns successResponse

        // ID 2000 fails with exception
        coEvery { api.getProjectDetail(2000L) } throws RuntimeException("Not found")

        // ID 3000 succeeds
        val successResponse2 = mockk<ProjectDetailResourceResponse>(relaxed = true) {
            every { data } returns mockk(relaxed = true) {
                every { updatedAt } returns "2026-01-31T08:00:00.000000Z"
            }
        }
        coEvery { api.getProjectDetail(3000L) } returns successResponse2

        val result = service.fetchFreshTimestamps("project", listOf(1000L, 2000L, 3000L))

        assertThat(result).hasSize(2)
        assertThat(result[1000L]).isEqualTo("2026-01-30T12:00:00.000000Z")
        assertThat(result[2000L]).isNull()
        assertThat(result[3000L]).isEqualTo("2026-01-31T08:00:00.000000Z")
    }
}
