package com.example.rocketplan_android.data.repository.sync

import com.example.rocketplan_android.data.api.OfflineSyncApi
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.model.offline.FlexibleDataResponse
import com.example.rocketplan_android.data.storage.SyncCheckpointStore
import com.example.rocketplan_android.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * RP-BUG-047 regression — the room moisture-log pull must request `include=photo` only.
 *
 * Android previously sent `include=photo,moisture_log`; the backend rejects the invalid `moisture_log`
 * relation with HTTP 400 for every room, so moisture readings never downloaded. iOS
 * (`DamageService.getRoomMoistureLogs`) sends `include=photo`. This test pins the exact include string so
 * the invalid relation can't creep back in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectMetadataSyncServiceTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val dispatcher = UnconfinedTestDispatcher()
    private val api: OfflineSyncApi = mockk(relaxed = true)
    private val localDataService: LocalDataService = mockk(relaxed = true)
    private val checkpointStore: SyncCheckpointStore = mockk(relaxed = true)
    private val workScopeSyncService: WorkScopeSyncService = mockk(relaxed = true)

    private fun newService() = ProjectMetadataSyncService(
        api = api,
        localDataService = localDataService,
        syncCheckpointStore = checkpointStore,
        workScopeSyncService = workScopeSyncService,
        resolveServerProjectId = { it },
        ioDispatcher = dispatcher,
    )

    @Test
    fun `syncRoomMoistureLogs requests include=photo only (RP-BUG-047)`() = runTest(dispatcher) {
        // Empty payload short-circuits parsing; we only care about the include argument.
        coEvery { api.getRoomMoistureLogs(any(), any()) } returns FlexibleDataResponse(data = null)

        newService().syncRoomMoistureLogs(projectId = 5233L, roomId = 6800L)

        // Pins the exact include string — must be "photo", never "photo,moisture_log".
        coVerify(exactly = 1) { api.getRoomMoistureLogs(6800L, "photo") }
        coVerify(exactly = 0) { api.getRoomMoistureLogs(any(), "photo,moisture_log") }
    }
}
