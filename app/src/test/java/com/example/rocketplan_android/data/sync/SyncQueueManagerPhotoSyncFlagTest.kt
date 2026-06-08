package com.example.rocketplan_android.data.sync

import android.net.ConnectivityManager
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.work.PhotoCacheScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test

/**
 * RP-BUG-043 regression — the photo-syncing state that drives the room-card spinner
 * (`ProjectDetailViewModel.isLoadingPhotos`) and the photo-enqueue dedup guard must be **derived**
 * from actual queue state, never a hand-maintained flag that can strand when an enqueue is coalesced
 * away (the bug: leaked flag ⇒ permanent spinner + photos never fetched).
 *
 * The derived set must union all three sources — **active + queued + deferred** photo-bearing
 * [SyncJob.SyncProjectGraph] jobs. The `deferred` source is the one the RP-BUG-043 review flagged:
 * a job parked in `deferredProjectSyncs` (RP-BUG-010 no-busy-spin) lives outside `taskIndex`, so
 * omitting it would clear the spinner / drop dedup protection too early (a false negative).
 *
 * Testing surface: the derive (`updateProjectSyncingProjectsLocked`) and predicate
 * (`isPhotoBearingSyncPendingLocked`) are private and read private mutable queue state. We seed that
 * state and invoke the private members via reflection, then assert the **public** observable —
 * `photoSyncingProjects.value` — plus the predicate result. This is deterministic (no IO-scope racing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncQueueManagerPhotoSyncFlagTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val projectId = 5233L

    private fun newManager(): SyncQueueManager = SyncQueueManager(
        authRepository = mockk<AuthRepository>(relaxed = true),
        syncRepository = mockk<OfflineSyncRepository>(relaxed = true),
        localDataService = mockk<LocalDataService>(relaxed = true),
        photoCacheScheduler = mockk<PhotoCacheScheduler>(relaxed = true),
        remoteLogger = mockk<RemoteLogger>(relaxed = true),
        connectivityManager = mockk<ConnectivityManager>(relaxed = true),
    )

    // --- reflection helpers ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <T> SyncQueueManager.field(name: String): T =
        SyncQueueManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this) as T

    private fun SyncQueueManager.recompute() {
        SyncQueueManager::class.java.getDeclaredMethod("updateProjectSyncingProjectsLocked")
            .apply { isAccessible = true }
            .invoke(this)
    }

    private fun SyncQueueManager.isPhotoPending(id: Long): Boolean =
        SyncQueueManager::class.java
            .getDeclaredMethod("isPhotoBearingSyncPendingLocked", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(this, id) as Boolean

    private fun SyncQueueManager.deferred(): MutableSet<SyncJob.SyncProjectGraph> = field("deferredProjectSyncs")

    private fun SyncQueueManager.activeModes(): MutableMap<Long, SyncJob.ProjectSyncMode> = field("activeProjectModes")

    @Suppress("UNCHECKED_CAST")
    private fun SyncQueueManager.putQueued(job: SyncJob.SyncProjectGraph) {
        val qtClass = Class.forName("com.example.rocketplan_android.data.sync.SyncQueueManager\$QueuedTask")
        val ctor = qtClass.declaredConstructors.first().apply { isAccessible = true }
        val task = ctor.newInstance(job.key, job, job.priority, 0L)
        val index = field<MutableMap<String, Any?>>("taskIndex")
        index[job.key] = task
    }

    // --- tests -------------------------------------------------------------------------------

    @Test
    fun `deferred photo-bearing job marks project photo-syncing (review fix)`() {
        val m = newManager()
        m.deferred().add(SyncJob.SyncProjectGraph(projectId, mode = SyncJob.ProjectSyncMode.CONTENT_ONLY))
        m.recompute()

        assertThat(m.photoSyncingProjects.value).contains(projectId)
        assertThat(m.isPhotoPending(projectId)).isTrue()
    }

    @Test
    fun `active photo-bearing mode marks project photo-syncing`() {
        val m = newManager()
        m.activeModes()[projectId] = SyncJob.ProjectSyncMode.PHOTOS_ONLY
        m.recompute()

        assertThat(m.photoSyncingProjects.value).contains(projectId)
        assertThat(m.isPhotoPending(projectId)).isTrue()
    }

    @Test
    fun `queued photo-bearing job marks project photo-syncing`() {
        val m = newManager()
        m.putQueued(SyncJob.SyncProjectGraph(projectId, mode = SyncJob.ProjectSyncMode.PHOTOS_ONLY))
        m.recompute()

        assertThat(m.photoSyncingProjects.value).contains(projectId)
        assertThat(m.isPhotoPending(projectId)).isTrue()
    }

    @Test
    fun `draining the deferred photo job clears state - no strand (leak fix)`() {
        val m = newManager()
        val job = SyncJob.SyncProjectGraph(projectId, mode = SyncJob.ProjectSyncMode.CONTENT_ONLY)
        m.deferred().add(job)
        m.recompute()
        assertThat(m.photoSyncingProjects.value).contains(projectId)

        // Job drains out of the deferred set (slot opened) and is not re-parked anywhere.
        m.deferred().remove(job)
        m.recompute()

        // The bug was a permanent strand; the derived state must drop the project the instant no
        // photo-bearing job remains in active/queued/deferred.
        assertThat(m.photoSyncingProjects.value).doesNotContain(projectId)
        assertThat(m.isPhotoPending(projectId)).isFalse()
    }

    @Test
    fun `non-photo essentials job does not mark photo-syncing`() {
        val m = newManager()
        m.activeModes()[projectId] = SyncJob.ProjectSyncMode.ESSENTIALS_ONLY
        m.recompute()

        // ESSENTIALS is a project sync but carries no photos — it must not light the photo spinner.
        assertThat(m.photoSyncingProjects.value).doesNotContain(projectId)
        assertThat(m.isPhotoPending(projectId)).isFalse()
        // ...but it should still register as a (non-photo) project sync in flight.
        assertThat(m.projectSyncingProjects.value).contains(projectId)
    }
}
