package com.example.rocketplan_android.data.sync

import android.net.ConnectivityManager
import android.net.Network
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.repository.AuthRepository
import com.example.rocketplan_android.data.repository.OfflineSyncRepository
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import com.example.rocketplan_android.testing.MainDispatcherRule
import com.example.rocketplan_android.work.PhotoCacheScheduler
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Tests for the connectivity-fallback fix in [SyncQueueManager] (RP-BUG-004).
 *
 * The fix makes [SyncQueueManager.isNetworkAvailable] default to "available"
 * (optimistic) when the [ConnectivityManager] is null or a lookup throws, and
 * emits a one-shot-per-reason WARN through [RemoteLogger] so the fallback is
 * observable without spamming logs on every sync tick.
 *
 * Testing surface: [isNetworkAvailable] and [logConnectivityFallback] are
 * private. They are exercised through the public [SyncQueueManager.syncOnForeground]
 * entry point, which calls isNetworkAvailable() and (because the network is
 * treated as available) proceeds far enough to trigger the fallback log. We then
 * assert the observable behavior: exactly one WARN log per distinct reason,
 * regardless of how many times the entry point runs.
 *
 * Note on dispatchers: SyncQueueManager owns an internal CoroutineScope backed by
 * Dispatchers.IO (not Dispatchers.Main), so the fallback log is emitted on a real
 * background thread. We therefore use coVerify(timeout = ...) to wait for it
 * rather than advancing virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncQueueManagerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private fun newManager(
        connectivityManager: ConnectivityManager?,
        remoteLogger: RemoteLogger,
    ): SyncQueueManager {
        val authRepository = mockk<AuthRepository>(relaxed = true)
        val syncRepository = mockk<OfflineSyncRepository>(relaxed = true)
        // observeSyncOperations / hasDueScheduledOperations / resetFailedOperationsForRetry
        // are all covered by the relaxed mock (empty flow, false, 0 respectively),
        // so the internal init coroutines complete harmlessly.
        val localDataService = mockk<LocalDataService>(relaxed = true)
        val photoCacheScheduler = mockk<PhotoCacheScheduler>(relaxed = true)

        return SyncQueueManager(
            authRepository = authRepository,
            syncRepository = syncRepository,
            localDataService = localDataService,
            photoCacheScheduler = photoCacheScheduler,
            remoteLogger = remoteLogger,
            connectivityManager = connectivityManager,
        )
    }

    @Test
    fun `null connectivityManager logs one-shot WARN fallback exactly once across multiple syncs`() =
        runTest {
            val remoteLogger = mockk<RemoteLogger>(relaxed = true)
            val manager = newManager(connectivityManager = null, remoteLogger = remoteLogger)

            // Drive the public entry point that calls isNetworkAvailable() several
            // times. With a null ConnectivityManager the optimistic path is taken
            // (network treated as available), and the fallback should be logged.
            repeat(3) { manager.syncOnForeground() }

            // One-shot per reason: the WARN must be emitted exactly once even though
            // isNetworkAvailable() was reached multiple times.
            coVerify(timeout = 5_000, exactly = 1) {
                remoteLogger.log(
                    level = LogLevel.WARN,
                    tag = any(),
                    message = any(),
                    metadata = match { it?.get("reason") == "connectivity_manager_unavailable" },
                )
            }
        }

    @Test
    fun `throwing connectivityManager logs connectivity_lookup_failed fallback once`() = runTest {
        val remoteLogger = mockk<RemoteLogger>(relaxed = true)
        // Non-null ConnectivityManager whose activeNetwork is present but whose
        // capability lookup throws — this induces the runCatching catch branch in
        // isNetworkAvailable(), which logs the "connectivity_lookup_failed" reason
        // and still defaults to available.
        val connectivityManager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(any()) } throws RuntimeException("boom")

        val manager = newManager(
            connectivityManager = connectivityManager,
            remoteLogger = remoteLogger,
        )

        repeat(3) { manager.syncOnForeground() }

        coVerify(timeout = 5_000, exactly = 1) {
            remoteLogger.log(
                level = LogLevel.WARN,
                tag = any(),
                message = any(),
                metadata = match { it?.get("reason") == "connectivity_lookup_failed" },
            )
        }
    }
}
