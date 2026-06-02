package com.example.rocketplan_android.data.local.cache

import android.content.Context
import com.example.rocketplan_android.data.local.LocalDataService
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Date

/**
 * Unit tests for the PhotoCacheManager fixes:
 *  - RP-BUG-019: cachePhotos iterates a snapshot (photos.toList()) so concurrent
 *    mutation of the source list mid-iteration does not throw
 *    ConcurrentModificationException.
 *  - RP-BUG-022: cleanUpUnused evicts via LRU down to <= maxBytes, and the running
 *    byte total accounts for already-expired victims upfront (uses break, not
 *    return@forEach). Partial-delete failures must NOT mark the photo as failed.
 *  - RP-BUG-009: generateThumbnail returns a thumbnail File on success and null on
 *    decode/compress failure without throwing. The bitmap decode/compress path
 *    requires the real Android graphics stack (Bitmap / BitmapFactory), which is
 *    not available in plain JVM unit tests and the repo has no Robolectric setup —
 *    see the RP-BUG-009 test below for what is feasible to assert here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoCacheManagerTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var localDataService: LocalDataService

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        localDataService = mockk(relaxed = true)

        // filesDir is read in the PhotoCacheManager constructor to build cacheRoot.
        every { context.filesDir } returns tempFolder.root

        // android.util.Log is NOT mocked here: the standard flavor ships
        // slf4j-android, and mocking Log makes MockK's own internal SLF4J logger
        // re-enter the recorder via Log.isLoggable. The mockable android.jar used by
        // unit tests returns default no-op values for Log.* (see everyLog() in
        // OfflineSyncRepositoryTest), so the production log calls are harmless.
    }

    // ---------------------------------------------------------------------
    // RP-BUG-019: snapshot iteration
    // ---------------------------------------------------------------------

    @Test
    fun `cachePhotos iterates a snapshot and survives concurrent mutation of the source list`() =
        runTest {
            val manager = PhotoCacheManager(
                context = context,
                localDataService = localDataService,
                remoteLogger = null,
                ioDispatcher = StandardTestDispatcher(testScheduler)
            )

            // A mutable list that we mutate while cachePhotos is iterating it.
            // Each photo has a remoteUrl so production reaches markPhotoCacheInProgress
            // (the first per-photo dependency call) before any real network work.
            val photos = mutableListOf<OfflinePhotoEntity>()
            repeat(5) { idx ->
                photos.add(photo(photoId = idx.toLong(), remoteUrl = "https://example.com/$idx.jpg"))
            }

            // markPhotoCacheInProgress is the first per-photo dependency call. Mutating
            // the ORIGINAL list from inside it proves iteration runs over a snapshot:
            // without photos.toList() this would throw ConcurrentModificationException.
            // We also throw afterward so cachePhoto fails fast (no real network call):
            // the runCatching in cachePhotos swallows it.
            coEvery { localDataService.markPhotoCacheInProgress(any()) } answers {
                // Structurally modify the source list mid-iteration.
                photos.add(photo(photoId = 999, remoteUrl = null))
                throw IllegalStateException("stop before network")
            }

            // Should complete without throwing ConcurrentModificationException.
            manager.cachePhotos(photos)

            // All 5 original snapshot entries were visited.
            coVerify(exactly = 5) { localDataService.markPhotoCacheInProgress(any()) }
        }

    // ---------------------------------------------------------------------
    // RP-BUG-022: LRU eviction down to <= maxBytes
    // ---------------------------------------------------------------------

    @Test
    fun `cleanUpUnused evicts oldest accessed photos via LRU until under maxBytes`() = runTest {
        val manager = PhotoCacheManager(
            context = context,
            localDataService = localDataService,
            remoteLogger = null,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val now = System.currentTimeMillis()
        // Three photos, each 100 bytes, none expired. maxBytes = 250 forces eviction
        // of the single oldest (LRU) to bring total 300 -> 200 (<= 250).
        val oldest = cachedPhoto(photoId = 1, sizeBytes = 100, lastAccessed = Date(now - 30_000))
        val mid = cachedPhoto(photoId = 2, sizeBytes = 100, lastAccessed = Date(now - 20_000))
        val newest = cachedPhoto(photoId = 3, sizeBytes = 100, lastAccessed = Date(now - 10_000))

        coEvery { localDataService.getCachedPhotos() } returns
            listOf(newest.entity, oldest.entity, mid.entity)

        manager.cleanUpUnused(threshold = Date(now - 60_000), maxBytes = 250)

        // Only the oldest should be evicted (its files deleted + marked failed).
        coVerify(exactly = 1) { localDataService.markPhotoCacheFailed(1) }
        coVerify(exactly = 0) { localDataService.markPhotoCacheFailed(2) }
        coVerify(exactly = 0) { localDataService.markPhotoCacheFailed(3) }
        assertTrue("oldest original file should be deleted", !oldest.original.exists())
        assertTrue("mid file should remain", mid.original.exists())
        assertTrue("newest file should remain", newest.original.exists())
    }

    @Test
    fun `cleanUpUnused running total subtracts expired victims before LRU so no over-eviction`() =
        runTest {
            val manager = PhotoCacheManager(
                context = context,
                localDataService = localDataService,
                remoteLogger = null,
                ioDispatcher = StandardTestDispatcher(testScheduler)
            )

            val now = System.currentTimeMillis()
            // expired (already a victim, 200 bytes) + two fresh 100-byte photos.
            // Pre-fix: totalBytes started at full 400; the early-return bug meant the
            // expired victim's bytes weren't reflected, causing LRU over-eviction.
            // Fixed: totalBytes = 400 - 200(expired) = 200 <= maxBytes(250), so NO
            // additional LRU eviction beyond the expired one occurs.
            val expired = cachedPhoto(
                photoId = 1,
                sizeBytes = 200,
                lastAccessed = Date(now - 120_000) // before threshold -> expired
            )
            val freshOld = cachedPhoto(photoId = 2, sizeBytes = 100, lastAccessed = Date(now - 20_000))
            val freshNew = cachedPhoto(photoId = 3, sizeBytes = 100, lastAccessed = Date(now - 10_000))

            coEvery { localDataService.getCachedPhotos() } returns
                listOf(expired.entity, freshOld.entity, freshNew.entity)

            manager.cleanUpUnused(threshold = Date(now - 60_000), maxBytes = 250)

            // Only the expired photo is evicted; the two fresh ones survive because
            // the running total correctly subtracts the expired victim upfront.
            coVerify(exactly = 1) { localDataService.markPhotoCacheFailed(1) }
            coVerify(exactly = 0) { localDataService.markPhotoCacheFailed(2) }
            coVerify(exactly = 0) { localDataService.markPhotoCacheFailed(3) }
        }

    // ---------------------------------------------------------------------
    // RP-BUG-022 partial-delete: do not mark failed when a file can't be deleted
    // ---------------------------------------------------------------------

    @Test
    fun `cleanUpUnused does not mark photo failed when its file cannot be deleted`() = runTest {
        val manager = PhotoCacheManager(
            context = context,
            localDataService = localDataService,
            remoteLogger = null,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )

        val now = System.currentTimeMillis()

        // A normal evictable photo whose file deletes cleanly.
        val deletable = cachedPhoto(photoId = 1, sizeBytes = 100, lastAccessed = Date(now - 30_000))

        // A victim whose original points at a *directory* — File.delete() on a
        // non-empty directory returns false, so originalDeleted == false and the
        // entry must NOT be marked failed.
        val undeletableDir = File(tempFolder.root, "undeletable_dir").apply { mkdirs() }
        // Put a child inside so delete() definitely fails (non-empty dir).
        File(undeletableDir, "child.bin").writeBytes(ByteArray(50))
        val stuckEntity = photo(
            photoId = 2,
            remoteUrl = "https://example.com/2.jpg",
            lastAccessed = Date(now - 40_000)
        ).copy(cachedOriginalPath = undeletableDir.absolutePath, cachedThumbnailPath = null)

        coEvery { localDataService.getCachedPhotos() } returns
            listOf(deletable.entity, stuckEntity)

        // maxBytes 0? No — 0 short-circuits. Use a tiny maxBytes to force eviction
        // of both entries. The dir reports length 50 (child) won't be counted since
        // File.length() on a directory is platform-defined; use threshold expiry to
        // make both victims regardless of size.
        manager.cleanUpUnused(threshold = Date(now), maxBytes = 1)

        // deletable file gone + marked failed.
        coVerify(exactly = 1) { localDataService.markPhotoCacheFailed(1) }
        assertTrue("deletable original removed", !deletable.original.exists())

        // stuck (directory) could not be deleted -> NOT marked failed, still present.
        coVerify(exactly = 0) { localDataService.markPhotoCacheFailed(2) }
        assertTrue("undeletable dir still present", undeletableDir.exists())
    }

    // ---------------------------------------------------------------------
    // RP-BUG-009: generateThumbnail null/no-throw behavior
    // ---------------------------------------------------------------------

    @Test
    fun `cachePhotos with null remoteUrl returns without throwing (thumbnail path not reached)`() =
        runTest {
            // generateThumbnail is private and only reachable after a successful
            // network download inside cachePhoto. Its decode/compress logic relies on
            // android.graphics.BitmapFactory / Bitmap, which are stub-only on the JVM
            // and would throw "not mocked"; the repo has no Robolectric. So the actual
            // bitmap decode-success and decode-failure paths of generateThumbnail
            // CANNOT be unit-tested in plain JVM here.
            //
            // What IS verifiable on the JVM is the documented invariant that the cache
            // path is resilient and never throws to the caller. cachePhotos wraps each
            // cachePhoto in runCatching, and cachePhoto returns early on a null
            // remoteUrl before any bitmap work. This asserts the no-throw contract.
            val manager = PhotoCacheManager(
                context = context,
                localDataService = localDataService,
                remoteLogger = null,
                ioDispatcher = StandardTestDispatcher(testScheduler)
            )

            val photos = listOf(
                photo(photoId = 1, remoteUrl = null),
                photo(photoId = 2, remoteUrl = null)
            )

            // Must not throw.
            manager.cachePhotos(photos)

            // Null remoteUrl returns before touching cache-status dependencies.
            coVerify(exactly = 0) { localDataService.markPhotoCacheInProgress(any()) }
            coVerify(exactly = 0) { localDataService.markPhotoCacheSuccess(any(), any(), any()) }
        }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun photo(
        photoId: Long,
        remoteUrl: String?,
        lastAccessed: Date? = null
    ): OfflinePhotoEntity =
        OfflinePhotoEntity(
            photoId = photoId,
            uuid = "uuid-$photoId",
            projectId = 42,
            fileName = "photo-$photoId.jpg",
            localPath = "/local/photo-$photoId.jpg",
            remoteUrl = remoteUrl,
            mimeType = "image/jpeg",
            lastAccessedAt = lastAccessed
        )

    private class CachedPhotoFixture(
        val entity: OfflinePhotoEntity,
        val original: File
    )

    /**
     * Creates a real temp file of [sizeBytes] bytes and an OfflinePhotoEntity whose
     * cachedOriginalPath points at it, so File.length()/delete() behave for real.
     */
    private fun cachedPhoto(
        photoId: Long,
        sizeBytes: Int,
        lastAccessed: Date
    ): CachedPhotoFixture {
        val file = File(tempFolder.root, "cached-$photoId.jpg")
        file.writeBytes(ByteArray(sizeBytes))
        val entity = photo(photoId, remoteUrl = "https://example.com/$photoId.jpg", lastAccessed = lastAccessed)
            .copy(cachedOriginalPath = file.absolutePath, cachedThumbnailPath = null)
        return CachedPhotoFixture(entity, file)
    }
}
