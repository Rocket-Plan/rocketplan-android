package com.example.rocketplan_android.data.repository.sync.handlers

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.SyncOperationType
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.model.NoteResourceResponse
import com.example.rocketplan_android.data.model.SingleResourceResponse
import com.example.rocketplan_android.data.model.offline.NoteDto
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.testing.PushHandlerTestFixtures
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.create409WithUpdatedAt
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.create409WithoutUpdatedAt
import com.example.rocketplan_android.testing.PushHandlerTestFixtures.create422Response
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class NotePushHandlerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val remoteLogger: RemoteLogger = mockk(relaxed = true)
    private val gson = Gson()
    private val ctx = PushHandlerTestFixtures.createContext(
        api = api,
        localDataService = localDataService,
        remoteLogger = remoteLogger
    )
    private val handler = NotePushHandler(ctx)

    // ===== Helper Methods =====

    private fun createNoteDto(
        id: Long = 5000L,
        uuid: String = "note-uuid",
        projectId: Long = 1000L,
        roomId: Long? = 4000L,
        body: String = "Test note",
        createdAt: String = "2026-01-30T12:00:00.000000Z",
        updatedAt: String = "2026-01-30T12:00:00.000000Z"
    ) = NoteDto(
        id = id,
        uuid = uuid,
        projectId = projectId,
        roomId = roomId,
        userId = 1L,
        body = body,
        photoId = null,
        categoryId = null,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun createNoteResponse(dto: NoteDto = createNoteDto()): NoteResourceResponse =
        SingleResourceResponse(data = dto)

    private fun createOperation(
        entityUuid: String = "note-uuid",
        operationType: SyncOperationType = SyncOperationType.UPDATE
    ) = PushHandlerTestFixtures.createSyncOperation(
        entityType = "note",
        entityId = 500L,
        entityUuid = entityUuid,
        operationType = operationType
    )

    private fun create404RetrofitResponse(): Response<Unit> {
        val body = "".toResponseBody("application/json".toMediaType())
        return Response.error(404, body)
    }

    private fun create410RetrofitResponse(): Response<Unit> {
        val body = "".toResponseBody("application/json".toMediaType())
        return Response.error(410, body)
    }

    private fun create422RetrofitResponse(): Response<Unit> {
        val body = """{"error":"validation failed"}""".toResponseBody("application/json".toMediaType())
        return Response.error(422, body)
    }

    // =====================================================================
    // handleUpsert - CREATE (serverId == null)
    // =====================================================================

    @Test
    fun `handleUpsert create happy path - creates note on server and saves locally`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = null, roomId = 400L)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val room = PushHandlerTestFixtures.createRoom(roomId = 400L, serverId = 4000L)
        val noteDto = createNoteDto()
        val response = createNoteResponse(noteDto)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.createProjectNote(1000L, any()) } returns response

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.createProjectNote(1000L, any()) }
        coVerify {
            localDataService.saveNote(match { saved ->
                saved.noteId == note.noteId &&
                    saved.uuid == note.uuid &&
                    saved.serverId == 5000L &&
                    !saved.isDirty &&
                    saved.syncStatus == SyncStatus.SYNCED &&
                    !saved.isDeleted
            })
        }
    }

    @Test
    fun `handleUpsert create - project not synced returns SKIP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = null)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = null)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createProjectNote(any(), any()) }
    }

    @Test
    fun `handleUpsert create - room not synced returns SKIP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = null, roomId = 400L)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val room = PushHandlerTestFixtures.createRoom(roomId = 400L, serverId = null)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createProjectNote(any(), any()) }
    }

    @Test
    fun `handleUpsert create - photo not synced returns SKIP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = null, roomId = null, photoId = 1100L)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val photo = PushHandlerTestFixtures.createPhoto(photoId = 1100L, serverId = null)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getPhoto(1100L) } returns photo

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 0) { api.createProjectNote(any(), any()) }
    }

    @Test
    fun `handleUpsert create - deleted note returns DROP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = null, isDeleted = true)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.createProjectNote(any(), any()) }
    }

    @Test
    fun `handleUpsert create - note not found returns DROP`() = runTest {
        coEvery { localDataService.getNoteByUuid("note-uuid") } returns null

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
    }

    @Test
    fun `handleUpsert create - 422 validation error returns DROP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = null, roomId = null)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.createProjectNote(1000L, any()) } throws create422Response()

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { localDataService.saveNote(any()) }
    }

    // =====================================================================
    // handleUpsert - UPDATE (serverId != null)
    // =====================================================================

    @Test
    fun `handleUpsert update happy path - updates note on server and saves locally`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L, roomId = 400L)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val room = PushHandlerTestFixtures.createRoom(roomId = 400L, serverId = 4000L)
        val noteDto = createNoteDto()
        val response = createNoteResponse(noteDto)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { localDataService.getRoom(400L) } returns room
        coEvery { api.updateNote(5000L, any()) } returns response

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.updateNote(5000L, any()) }
        coVerify {
            localDataService.saveNote(match { saved ->
                saved.noteId == note.noteId &&
                    saved.uuid == note.uuid &&
                    saved.serverId == 5000L &&
                    !saved.isDirty &&
                    saved.syncStatus == SyncStatus.SYNCED &&
                    !saved.isDeleted
            })
        }
    }

    @Test
    fun `handleUpsert update - 409 conflict with updatedAt extracts timestamp and retries successfully`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L, roomId = null)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)
        val noteDto = createNoteDto()
        val response = createNoteResponse(noteDto)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateNote(5000L, any()) } throws
            create409WithUpdatedAt("2026-01-30T12:00:00.000000Z") andThen response

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 2) { api.updateNote(5000L, any()) }
        coVerify { localDataService.saveNote(any()) }
    }

    @Test
    fun `handleUpsert update - 409 without updatedAt returns SKIP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L, roomId = null)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateNote(5000L, any()) } throws create409WithoutUpdatedAt()

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.SKIP)
        coVerify(exactly = 1) { api.updateNote(5000L, any()) }
        coVerify(exactly = 0) { localDataService.saveNote(any()) }
    }

    @Test
    fun `handleUpsert update - double 409 returns CONFLICT_PENDING`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L, roomId = null)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        // First call throws 409 with updatedAt, retry also throws 409
        coEvery { api.updateNote(5000L, any()) } throws
            create409WithUpdatedAt("2026-01-30T12:00:00.000000Z") andThenThrows
            create409WithUpdatedAt("2026-01-30T13:00:00.000000Z")

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.CONFLICT_PENDING)
        coVerify(exactly = 2) { api.updateNote(5000L, any()) }
    }

    @Test
    fun `handleUpsert update - 422 validation error returns DROP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L, roomId = null)
        val project = PushHandlerTestFixtures.createProject(projectId = 100L, serverId = 1000L)

        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { localDataService.getProject(100L) } returns project
        coEvery { api.updateNote(5000L, any()) } throws create422Response()

        val operation = createOperation()
        val result = handler.handleUpsert(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { localDataService.saveNote(any()) }
    }

    // =====================================================================
    // handleDelete tests
    // =====================================================================

    @Test
    fun `handleDelete happy path - deletes note on server and marks locally`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L)
        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { api.deleteNote(5000L, any()) } returns Response.success(Unit)

        val operation = createOperation(operationType = SyncOperationType.DELETE)
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify { api.deleteNote(5000L, any()) }
        coVerify {
            localDataService.saveNote(match { saved ->
                saved.isDeleted &&
                    !saved.isDirty &&
                    saved.syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete - note not found returns DROP`() = runTest {
        coEvery { localDataService.getNoteByUuid("note-uuid") } returns null

        val operation = createOperation(operationType = SyncOperationType.DELETE)
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { api.deleteNote(any(), any()) }
    }

    @Test
    fun `handleDelete - no serverId returns SUCCESS without calling API`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = null)
        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note

        val operation = createOperation(operationType = SyncOperationType.DELETE)
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify(exactly = 0) { api.deleteNote(any(), any()) }
    }

    @Test
    fun `handleDelete - 404 response treated as success`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L)
        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { api.deleteNote(5000L, any()) } returns create404RetrofitResponse()

        val operation = createOperation(operationType = SyncOperationType.DELETE)
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveNote(match { saved ->
                saved.isDeleted && saved.syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete - 410 response treated as success`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L)
        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { api.deleteNote(5000L, any()) } returns create410RetrofitResponse()

        val operation = createOperation(operationType = SyncOperationType.DELETE)
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.SUCCESS)
        coVerify {
            localDataService.saveNote(match { saved ->
                saved.isDeleted && saved.syncStatus == SyncStatus.SYNCED
            })
        }
    }

    @Test
    fun `handleDelete - 422 response returns DROP`() = runTest {
        val note = PushHandlerTestFixtures.createNote(serverId = 5000L)
        coEvery { localDataService.getNoteByUuid("note-uuid") } returns note
        coEvery { api.deleteNote(5000L, any()) } returns create422RetrofitResponse()

        val operation = createOperation(operationType = SyncOperationType.DELETE)
        val result = handler.handleDelete(operation)

        assertThat(result).isEqualTo(OperationOutcome.DROP)
        coVerify(exactly = 0) { localDataService.saveNote(any<OfflineNoteEntity>()) }
    }
}
