package com.example.rocketplan_android.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.rocketplan_android.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.sentry.ScopeCallback
import io.sentry.Sentry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the auth-token race fix in [SecureStorage] (RP-BUG-006).
 *
 * Production wiring: EncryptedSharedPreferences requires Android Keystore and
 * is stubbed via mockk; legacy DataStore reads/clears are injected so the test
 * can control migration timing deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecureStorageTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var encryptedPrefs: SharedPreferences
    private lateinit var encryptedEditor: SharedPreferences.Editor

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        // clearAll() and the legacy-read defaults touch context.dataStore, whose
        // delegate builds a real file under context.filesDir. A relaxed mock
        // returns null there -> File(null) NPE. Point it at a per-test temp dir
        // (TemporaryFolder gives a fresh root per test, so DataStore files don't
        // collide across tests).
        val filesDir = tempFolder.newFolder("files")
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        encryptedPrefs = mockk(relaxed = true)
        encryptedEditor = mockk(relaxed = true)
        every { encryptedPrefs.edit() } returns encryptedEditor
        every { encryptedEditor.putString(any(), any()) } returns encryptedEditor
        every { encryptedEditor.remove(any()) } returns encryptedEditor
        every { encryptedEditor.clear() } returns encryptedEditor
        every { encryptedEditor.commit() } returns true

        // EncryptedSharedPreferences.create() and MasterKey.Builder().build()
        // touch Android Keystore on real devices; stub both to return our mock.
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<Context>(), any(), any(), any(), any()
            )
        } returns encryptedPrefs

        mockkStatic("androidx.security.crypto.MasterKey\$Builder")
        // MasterKey.Builder is a regular constructor; the relaxed Context is
        // enough — the Builder will produce a mock-friendly result and the
        // EncryptedSharedPreferences.create stub above intercepts the consumer.
    }

    @After
    fun tearDown() {
        unmockkStatic(EncryptedSharedPreferences::class)
        unmockkStatic(Sentry::class)
    }

    private fun newStorage(
        scope: CoroutineScope,
        initialEncryptedToken: String? = null,
        legacyToken: suspend () -> String? = { null },
        legacyClear: suspend () -> Unit = {},
    ): SecureStorage {
        every {
            encryptedPrefs.getString("auth_token", null)
        } returns initialEncryptedToken
        return SecureStorage(context, scope, legacyToken, legacyClear)
    }

    @Test
    fun `getAuthTokenSync returns migrated legacy token when no encrypted token exists`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val storage = newStorage(
                scope = scope,
                initialEncryptedToken = null,
                legacyToken = { "legacy-token" },
            )

            val result = storage.getAuthTokenSync()

            assertEquals("legacy-token", result)
        }

    @Test
    fun `migration runs eagerly and surfaces via getAuthToken Flow without calling getAuthTokenSync`() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val storage = newStorage(
                scope = scope,
                initialEncryptedToken = null,
                legacyToken = { "legacy-token" },
            )

            // Never call getAuthTokenSync(); the eager init migration should run on
            // its own and publish the migrated token to the Flow (RP-BUG-028).
            advanceUntilIdle()

            assertEquals("legacy-token", storage.getAuthToken().first())
        }

    @Test
    fun `getAuthTokenSync returns null when no legacy or encrypted token exists`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val storage = newStorage(scope = scope)

        val result = storage.getAuthTokenSync()

        assertNull(result)
    }

    @Test
    fun `fresh saveAuthToken during pending migration is not clobbered by legacy value`() =
        runTest {
            // Block the legacy read until we explicitly release it. This forces
            // the migration deferred to remain pending while saveAuthToken runs.
            val releaseLegacy = CompletableDeferred<String?>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            val storage = newStorage(
                scope = scope,
                legacyToken = { releaseLegacy.await() },
            )

            // Fresh sign-in lands while migration is still suspended.
            storage.saveAuthToken("fresh-token")

            // Now release the legacy read with a stale token. compareAndSet
            // must refuse to overwrite the fresh value.
            releaseLegacy.complete("legacy-token")
            advanceUntilIdle()

            val result = storage.getAuthTokenSync()
            assertEquals("fresh-token", result)
        }

    @Test
    fun `getAuthTokenSync does not hang when legacy read exceeds migration timeout`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        val storage = newStorage(
            scope = scope,
            initialEncryptedToken = null,
            // Never returns — simulates a stuck DataStore.
            legacyToken = { awaitCancellation() },
        )

        // Run getAuthTokenSync in a child coroutine so the test scheduler can
        // advance virtual time past MIGRATION_TIMEOUT_MS.
        val pending = async { storage.getAuthTokenSync() }
        advanceTimeBy(6_000) // > 5s timeout
        advanceUntilIdle()

        assertNull(pending.await())
    }

    @Test
    fun `clearAll cancels in-flight migration and authTokenState stays null after old migration completes`() =
        runTest {
            val releaseLegacy = CompletableDeferred<String?>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            val storage = newStorage(
                scope = scope,
                legacyToken = { releaseLegacy.await() },
            )

            // Trigger migration to start
            val pendingMigration = async { storage.getAuthTokenSync() }

            // clearAll runs while migration is still pending
            storage.clearAll()

            // Old migration work finishes late — should not affect null state after clearAll
            releaseLegacy.complete("legacy-token")
            advanceUntilIdle()

            // verify pending migration completes (cancelled deferred returns null)
            assertNull(pendingMigration.await())
            // authTokenState must remain null after clearAll regardless of late migration
            assertNull(storage.getAuthTokenSync())
        }

    @Test
    fun `logout followed by immediate re-login keeps new token even if old migration finishes late`() =
        runTest {
            val releaseLegacy = CompletableDeferred<String?>()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            val storage = newStorage(
                scope = scope,
                legacyToken = { releaseLegacy.await() },
            )

            // Trigger migration but don't let it complete yet
            val pendingMigration = async { storage.getAuthTokenSync() }

            // Logout clears everything
            storage.clearAll()

            // Re-login with fresh token
            storage.saveAuthToken("fresh-token")

            // Old migration finally finishes
            releaseLegacy.complete("legacy-token")
            advanceUntilIdle()

            // Old migration should have been cancelled/replaced, fresh token must remain
            assertEquals("fresh-token", storage.getAuthTokenSync())
        }

    @Test
    fun `getAuthTokenSync after clearAll does not await stale migration`() = runTest {
        val releaseLegacy = CompletableDeferred<String?>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

        val storage = newStorage(
            scope = scope,
            legacyToken = { releaseLegacy.await() },
        )

        // Start migration
        val pendingMigration = async { storage.getAuthTokenSync() }

        // clearAll cancels and replaces
        storage.clearAll()

        // Old migration never completes
        // getAuthTokenSync must not block waiting for stale migration
        val result = storage.getAuthTokenSync()

        // Should return null immediately, not block on old migration
        assertNull(result)
        assertNull(storage.getAuthTokenSync())
    }

    @Test
    fun `no-legacy-token path still behaves as a fast no-op`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val storage = newStorage(
            scope = scope,
            initialEncryptedToken = null,
            legacyToken = { null },
        )

        val result = storage.getAuthTokenSync()

        assertNull(result)
    }

    @Test
    fun `saveAuthTokenInternal calls commit not apply`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val storage = newStorage(scope = scope)

        storage.saveAuthToken("test-token")

        verify { encryptedPrefs.edit() }
        verify { encryptedEditor.putString("auth_token", "test-token") }
        verify { encryptedEditor.commit() }
        verify(exactly = 0) { encryptedEditor.apply() }
    }

    @Test
    fun `failed commit reports to Sentry with auth_token_commit_failed tag`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        every { encryptedEditor.commit() } returns false

        mockkStatic(Sentry::class)
        val sentryScope = mockk<io.sentry.IScope>(relaxed = true)
        val scopeCallback = slot<ScopeCallback>()
        every { Sentry.withScope(capture(scopeCallback)) } answers {
            scopeCallback.captured.run(sentryScope)
        }
        every { Sentry.captureMessage(any<String>()) } returns mockk(relaxed = true)

        val storage = newStorage(scope = scope)

        val error = runCatching { storage.saveAuthToken("test-token") }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)

        verify { sentryScope.setTag("event", "auth_token_commit_failed") }
        verify { Sentry.captureMessage("Auth token commit() returned false") }
    }
}