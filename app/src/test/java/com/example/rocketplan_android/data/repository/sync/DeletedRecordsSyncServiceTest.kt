package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.cache.PhotoCacheManager
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.model.offline.DeletedRecordsResponse
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class DeletedRecordsSyncServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val syncCheckpointStore: SyncCheckpointStore = mockk(relaxed = true)
    private val photoCacheManager: PhotoCacheManager = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun createService() = DeletedRecordsSyncService(
        api = api,
        localDataService = localDataService,
        syncCheckpointStore = syncCheckpointStore,
        photoCacheManager = photoCacheManager,
        remoteLogger = remoteLogger,
        ioDispatcher = testDispatcher
    )

    private fun createApiResponse(
        body: DeletedRecordsResponse,
        dateHeader: String? = "Sat, 01 Feb 2026 00:00:00 GMT"
    ): Response<DeletedRecordsResponse> {
        val headersBuilder = Headers.Builder()
        if (dateHeader != null) {
            headersBuilder.add("Date", dateHeader)
        }
        val response = mockk<Response<DeletedRecordsResponse>>()
        every { response.isSuccessful } returns true
        every { response.body() } returns body
        every { response.headers() } returns headersBuilder.build()
        return response
    }

    private fun createErrorResponse(
        code: Int = 500,
        errorBody: String = "Internal Server Error"
    ): Response<DeletedRecordsResponse> {
        val response = mockk<Response<DeletedRecordsResponse>>()
        every { response.isSuccessful } returns false
        every { response.code() } returns code
        every { response.errorBody() } returns errorBody.toResponseBody("text/plain".toMediaType())
        every { response.headers() } returns Headers.Builder().build()
        return response
    }

    // ============================================================================
    // syncDeletedRecords - Happy Path
    // ============================================================================

    @Test
    fun `syncDeletedRecords happy path applies deletions and updates checkpoint`() = runTest(testDispatcher) {
        val body = DeletedRecordsResponse(
            projects = listOf(1L, 2L),
            rooms = listOf(10L, 20L),
            photos = listOf(100L)
        )
        val apiResponse = createApiResponse(body)

        every { syncCheckpointStore.getCheckpoint("deleted_records_global") } returns null
        every { syncCheckpointStore.getCheckpoint("deleted_records_server_date") } returns null
        coEvery { api.getDeletedRecords(any(), any(), any()) } returns apiResponse
        coEvery { localDataService.cascadeDeleteProjectsByServerIds(any(), any()) } returns emptyList()

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isSuccess).isTrue()

        // Verify deletions were applied
        coVerify { localDataService.cascadeDeleteProjectsByServerIds(listOf(1L, 2L)) }
        coVerify { localDataService.markRoomsDeleted(listOf(10L, 20L)) }
        coVerify { localDataService.markPhotosDeleted(listOf(100L)) }

        // Verify checkpoint was updated with server Date header
        val checkpointSlot = slot<Date>()
        coVerify { syncCheckpointStore.updateCheckpoint("deleted_records_global", capture(checkpointSlot)) }
        coVerify { syncCheckpointStore.updateCheckpoint("deleted_records_server_date", any()) }
    }

    // ============================================================================
    // syncDeletedRecords - Clock Skew (future checkpoint clamped to server time)
    // ============================================================================

    @Test
    fun `syncDeletedRecords clamps future checkpoint to last server time`() = runTest(testDispatcher) {
        val pastServerDate = Date(1700000000000L) // some past date
        val futureCheckpoint = Date(1800000000000L) // checkpoint after server date

        every { syncCheckpointStore.getCheckpoint("deleted_records_server_date") } returns pastServerDate
        every { syncCheckpointStore.getCheckpoint("deleted_records_global") } returns futureCheckpoint

        val body = DeletedRecordsResponse()
        val apiResponse = createApiResponse(body)
        coEvery { api.getDeletedRecords(any(), any(), any()) } returns apiResponse
        coEvery { localDataService.cascadeDeleteProjectsByServerIds(any(), any()) } returns emptyList()

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isSuccess).isTrue()

        // Verify the checkpoint was clamped to the last server date
        coVerify { syncCheckpointStore.updateCheckpoint("deleted_records_global", pastServerDate) }
    }

    // ============================================================================
    // syncDeletedRecords - Clock Skew (future checkpoint with no server time -> epoch)
    // ============================================================================

    @Test
    fun `syncDeletedRecords clamps future checkpoint to epoch when no server time`() = runTest(testDispatcher) {
        val futureCheckpoint = Date(System.currentTimeMillis() + 86400000L) // 1 day in future

        every { syncCheckpointStore.getCheckpoint("deleted_records_server_date") } returns null
        every { syncCheckpointStore.getCheckpoint("deleted_records_global") } returns futureCheckpoint

        val body = DeletedRecordsResponse()
        val apiResponse = createApiResponse(body)
        coEvery { api.getDeletedRecords(any(), any(), any()) } returns apiResponse
        coEvery { localDataService.cascadeDeleteProjectsByServerIds(any(), any()) } returns emptyList()

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isSuccess).isTrue()

        // Verify the checkpoint was clamped to epoch (Date(0)) before the API call.
        // There will be a second updateCheckpoint call from the Date header, so we verify
        // that epoch was passed at least once.
        coVerify { syncCheckpointStore.updateCheckpoint("deleted_records_global", Date(0)) }
    }

    // ============================================================================
    // syncDeletedRecords - API failure
    // ============================================================================

    @Test
    fun `syncDeletedRecords returns failure on API exception`() = runTest(testDispatcher) {
        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getDeletedRecords(any(), any(), any()) } throws IOException("Network error")

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("Network error")
    }

    // ============================================================================
    // syncDeletedRecords - Non-success HTTP response
    // ============================================================================

    @Test
    fun `syncDeletedRecords returns failure on non-success HTTP response`() = runTest(testDispatcher) {
        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        val errorResponse = createErrorResponse(code = 500, errorBody = "Server Error")
        coEvery { api.getDeletedRecords(any(), any(), any()) } returns errorResponse

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()!!.message).contains("HTTP 500")
    }

    // ============================================================================
    // syncDeletedRecords - Empty body
    // ============================================================================

    @Test
    fun `syncDeletedRecords returns failure on empty body`() = runTest(testDispatcher) {
        every { syncCheckpointStore.getCheckpoint(any()) } returns null

        val response = mockk<Response<DeletedRecordsResponse>>()
        every { response.isSuccessful } returns true
        every { response.body() } returns null
        every { response.headers() } returns Headers.Builder().build()

        coEvery { api.getDeletedRecords(any(), any(), any()) } returns response

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.exceptionOrNull()!!.message).contains("body missing")
    }

    // ============================================================================
    // syncDeletedRecords - moistureLogs + damageMaterialRoomLogs merged
    // ============================================================================

    @Test
    fun `syncDeletedRecords merges moistureLogs and damageMaterialRoomLogs`() = runTest(testDispatcher) {
        val body = DeletedRecordsResponse(
            moistureLogs = listOf(1L, 2L, 3L),
            damageMaterialRoomLogs = listOf(3L, 4L, 5L) // 3L is a duplicate
        )
        val apiResponse = createApiResponse(body)

        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getDeletedRecords(any(), any(), any()) } returns apiResponse
        coEvery { localDataService.cascadeDeleteProjectsByServerIds(any(), any()) } returns emptyList()

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isSuccess).isTrue()

        // Verify merged and deduplicated list was passed (1, 2, 3, 4, 5)
        val mergedSlot = slot<List<Long>>()
        coVerify { localDataService.markMoistureLogsDeleted(capture(mergedSlot)) }
        assertThat(mergedSlot.captured).containsExactly(1L, 2L, 3L, 4L, 5L)
    }

    // ============================================================================
    // syncDeletedRecords - cascade deletes projects
    // ============================================================================

    @Test
    fun `syncDeletedRecords cascades project deletions and cleans photo cache`() = runTest(testDispatcher) {
        val cachedPhotos = listOf(
            mockk<OfflinePhotoEntity>(relaxed = true),
            mockk<OfflinePhotoEntity>(relaxed = true)
        )
        val body = DeletedRecordsResponse(
            projects = listOf(1L, 2L)
        )
        val apiResponse = createApiResponse(body)

        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getDeletedRecords(any(), any(), any()) } returns apiResponse
        coEvery { localDataService.cascadeDeleteProjectsByServerIds(listOf(1L, 2L)) } returns cachedPhotos

        val service = createService()
        val result = service.syncDeletedRecords()

        assertThat(result.isSuccess).isTrue()

        // Verify cascade delete was called with project server IDs
        coVerify { localDataService.cascadeDeleteProjectsByServerIds(listOf(1L, 2L)) }

        // Verify photo cache cleanup
        coVerify { photoCacheManager.removeCachedPhotos(cachedPhotos) }
    }

    // ============================================================================
    // syncDeletedRecordsForProject - Happy Path
    // ============================================================================

    @Test
    fun `syncDeletedRecordsForProject happy path applies child deletions`() = runTest(testDispatcher) {
        val body = DeletedRecordsResponse(
            rooms = listOf(10L, 20L),
            photos = listOf(100L),
            notes = listOf(200L)
        )
        val apiResponse = createApiResponse(body)

        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getDeletedRecords(any(), any(), projectId = 42L) } returns apiResponse

        val service = createService()
        val result = service.syncDeletedRecordsForProject(
            projectServerId = 42L,
            localProjectId = 99L
        )

        assertThat(result.isSuccess).isTrue()

        // Verify child entity deletions were applied
        coVerify { localDataService.markRoomsDeleted(listOf(10L, 20L)) }
        coVerify { localDataService.markPhotosDeleted(listOf(100L)) }
        coVerify { localDataService.markNotesDeleted(listOf(200L)) }

        // Verify the API was called with projectId
        coVerify { api.getDeletedRecords(any(), any(), projectId = 42L) }
    }

    // ============================================================================
    // syncDeletedRecordsForProject - Returns deletion count
    // ============================================================================

    @Test
    fun `syncDeletedRecordsForProject returns correct deletion count`() = runTest(testDispatcher) {
        val body = DeletedRecordsResponse(
            rooms = listOf(10L, 20L),        // 2
            photos = listOf(100L),           // 1
            notes = listOf(200L, 201L),      // 2
            equipment = listOf(300L),        // 1
            moistureLogs = listOf(400L),     // merged with damageMaterialRoomLogs
            damageMaterialRoomLogs = listOf(401L)  // distinct from moistureLogs
        )
        val apiResponse = createApiResponse(body)

        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getDeletedRecords(any(), any(), projectId = 42L) } returns apiResponse

        val service = createService()
        val result = service.syncDeletedRecordsForProject(
            projectServerId = 42L,
            localProjectId = 99L
        )

        assertThat(result.isSuccess).isTrue()
        // rooms(2) + photos(1) + notes(2) + equipment(1) + merged moisture(2) = 8
        assertThat(result.getOrNull()).isEqualTo(8)
    }

    // ============================================================================
    // syncDeletedRecordsForProject - Missing Date header
    // ============================================================================

    @Test
    fun `syncDeletedRecordsForProject does not advance checkpoint without Date header`() = runTest(testDispatcher) {
        val body = DeletedRecordsResponse(rooms = listOf(10L))
        val apiResponse = createApiResponse(body, dateHeader = null) // no Date header

        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getDeletedRecords(any(), any(), projectId = 42L) } returns apiResponse

        val service = createService()
        val result = service.syncDeletedRecordsForProject(
            projectServerId = 42L,
            localProjectId = 99L
        )

        assertThat(result.isSuccess).isTrue()

        // Verify checkpoint was NOT updated (no Date header to parse)
        coVerify(exactly = 0) {
            syncCheckpointStore.updateCheckpoint("deleted_records_project_42", any())
        }
        coVerify(exactly = 0) {
            syncCheckpointStore.updateCheckpoint("deleted_records_project_42_server_date", any())
        }
    }

    // ============================================================================
    // syncDeletedRecordsForProject - API failure
    // ============================================================================

    @Test
    fun `syncDeletedRecordsForProject returns failure on API exception`() = runTest(testDispatcher) {
        every { syncCheckpointStore.getCheckpoint(any()) } returns null
        coEvery { api.getDeletedRecords(any(), any(), projectId = 42L) } throws IOException("Connection refused")

        val service = createService()
        val result = service.syncDeletedRecordsForProject(
            projectServerId = 42L,
            localProjectId = 99L
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(result.exceptionOrNull()!!.message).isEqualTo("Connection refused")
    }
}
