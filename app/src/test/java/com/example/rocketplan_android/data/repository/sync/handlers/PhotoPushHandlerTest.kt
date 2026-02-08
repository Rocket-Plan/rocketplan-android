package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoPushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val ctx = PushHandlerTestFixtures.createContext(
        api = api,
        localDataService = localDataService,
        remoteLogger = remoteLogger
    )
    private val handler = PhotoPushHandler(ctx)

    // =====================================================================
    // handleDelete tests
    // =====================================================================

    @Test
    fun `handleDelete happy path - photo deleted successfully`() = runTest {
        val photo = PushHandlerTestFixtures.createPhoto(photoId = 1100L, serverId = 11000L, uuid = "photo-uuid")
        coEvery { localDataService.getPhoto(1100L) } returns photo
        coEvery { api.deletePhoto(any(), any()) } returns Response.success(Unit)

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "photo",
            entityId = 1100L,
            entityUuid = "photo-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deletePhoto(11000L, any()) }
        coVerify {
            localDataService.savePhotos(match { photos ->
                photos.size == 1 &&
                    photos[0].isDeleted &&
                    !photos[0].isDirty &&
                    photos[0].syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete returns DROP when photo not found locally`() = runTest {
        coEvery { localDataService.getPhoto(1100L) } returns null

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "photo",
            entityId = 1100L,
            entityUuid = "photo-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.deletePhoto(any(), any()) }
        coVerify(exactly = 0) { localDataService.savePhotos(any()) }
    }

    @Test
    fun `handleDelete returns SUCCESS when photo has no serverId`() = runTest {
        val photo = PushHandlerTestFixtures.createPhoto(photoId = 1100L, serverId = null, uuid = "photo-uuid")
        coEvery { localDataService.getPhoto(1100L) } returns photo

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "photo",
            entityId = 1100L,
            entityUuid = "photo-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deletePhoto(any(), any()) }
        coVerify(exactly = 0) { localDataService.savePhotos(any()) }
    }

    @Test
    fun `handleDelete returns SUCCESS on 404 from server`() = runTest {
        val photo = PushHandlerTestFixtures.createPhoto(photoId = 1100L, serverId = 11000L, uuid = "photo-uuid")
        coEvery { localDataService.getPhoto(1100L) } returns photo
        coEvery { api.deletePhoto(any(), any()) } returns Response.error<Unit>(
            404, "".toResponseBody("application/json".toMediaType())
        )

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "photo",
            entityId = 1100L,
            entityUuid = "photo-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deletePhoto(11000L, any()) }
        coVerify {
            localDataService.savePhotos(match { photos ->
                photos.size == 1 && photos[0].isDeleted
            })
        }
    }

    @Test
    fun `handleDelete returns DROP on 422 from server`() = runTest {
        val photo = PushHandlerTestFixtures.createPhoto(photoId = 1100L, serverId = 11000L, uuid = "photo-uuid")
        coEvery { localDataService.getPhoto(1100L) } returns photo
        coEvery { api.deletePhoto(any(), any()) } returns Response.error<Unit>(
            422, "".toResponseBody("application/json".toMediaType())
        )

        val operation = PushHandlerTestFixtures.createSyncOperation(
            entityType = "photo",
            entityId = 1100L,
            entityUuid = "photo-uuid",
            operationType = SyncOperationType.DELETE
        )

        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify { api.deletePhoto(11000L, any()) }
        coVerify(exactly = 0) { localDataService.savePhotos(any()) }
    }
}
