**Bug ID(s):** RP-BUG-009, RP-BUG-019, RP-BUG-022
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [RP-BUG-009](../investigations/RP-BUG-009_bitmap_memory_leak.md) · [RP-BUG-019](../investigations/RP-BUG-019_concurrent_modification.md) · [RP-BUG-022](../investigations/RP-BUG-022_lru_cleanup.md) · [Plan](./plan_rp_bug_009_019_022_photo_cache_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-009/019/022] Harden PhotoCacheManager bitmap, concurrency, and LRU cleanup

**Bug ID(s):** RP-BUG-009, RP-BUG-019, RP-BUG-022
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

Three pre-existing latent defects all live in
`app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt`
and are bundled here because they share a file, a code owner, and a single test surface:

- **RP-BUG-009 (P1, memory):** `generateThumbnail()` recycles bitmaps outside a
  `try/finally`, so a thrown `compress()`/`FileOutputStream` leaks both `sampled`
  and `scaled`. It also guards the double-recycle with structural `!=` rather than
  reference `!==`, which is the wrong semantic for `Bitmap` identity and is fragile.
- **RP-BUG-019 (P2, crash):** `cachePhotos()` iterates a caller-supplied `List` with
  a suspend body. The list itself is never mutated here, and the only scheduler
  (`PhotoCacheScheduler`) enqueues work with `ExistingWorkPolicy.KEEP` so two
  prefetch passes cannot run concurrently today. The fix is defensive: snapshot the
  input and make the iteration robust against an aliased/mutating caller list rather
  than relying on the scheduler invariant.
- **RP-BUG-022 (P3, performance):** `cleanUpUnused()` mis-tracks bytes. Files already
  added as "expired/zero-byte" victims are never subtracted from `totalBytes` before
  the LRU pass, so the LRU loop over-counts remaining usage; and bytes are decremented
  assuming deletion succeeds, with no accounting when `delete()` fails.

None of these involve schema, persisted data, or server changes.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt` | RP-BUG-009: wrap bitmap lifecycle in `try/finally`, use reference identity for double-recycle guard. RP-BUG-019: snapshot input list in `cachePhotos()`. RP-BUG-022: subtract expired victims before LRU pass, clear DB cache state only on confirmed full deletion, and log partial-delete failures for retryable recovery. |
| `app/src/test/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManagerTest.kt` | New unit tests for thumbnail cleanup, snapshot iteration, and LRU byte accounting. |

## Implementation Notes

### Step 1 (RP-BUG-009): Leak-safe thumbnail generation

`generateThumbnail()` currently (lines ~167-188) recycles after a `compress()` call
that can throw, and uses `scaled != sampled`:

```kotlin
// before
val sampled = BitmapFactory.decodeFile(originalFile.absolutePath, decodeOptions) ?: return null

val maxDimension = maxOf(sampled.width, sampled.height)
val scaled = if (maxDimension > THUMBNAIL_MAX_DIMENSION) {
    val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxDimension
    val thumbWidth = (sampled.width * scale).toInt().coerceAtLeast(64)
    val thumbHeight = (sampled.height * scale).toInt().coerceAtLeast(64)
    Bitmap.createScaledBitmap(sampled, thumbWidth, thumbHeight, true)
} else {
    sampled
}

val thumbnailFile = File(originalFile.parentFile, "${originalFile.nameWithoutExtension}_thumb.jpg")
FileOutputStream(thumbnailFile).use { output ->
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
}
if (scaled != sampled) {
    scaled.recycle()
}
sampled.recycle()
thumbnailFile
```

```kotlin
// after
val sampled = BitmapFactory.decodeFile(originalFile.absolutePath, decodeOptions) ?: return null
var scaled: Bitmap? = null
try {
    val maxDimension = maxOf(sampled.width, sampled.height)
    scaled = if (maxDimension > THUMBNAIL_MAX_DIMENSION) {
        val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxDimension
        val thumbWidth = (sampled.width * scale).toInt().coerceAtLeast(64)
        val thumbHeight = (sampled.height * scale).toInt().coerceAtLeast(64)
        Bitmap.createScaledBitmap(sampled, thumbWidth, thumbHeight, true)
    } else {
        sampled
    }

    val thumbnailFile = File(originalFile.parentFile, "${originalFile.nameWithoutExtension}_thumb.jpg")
    FileOutputStream(thumbnailFile).use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
    }
    thumbnailFile
} finally {
    // Reference identity: only recycle the scaled copy if it is a distinct bitmap.
    if (scaled != null && scaled !== sampled) {
        scaled.recycle()
    }
    sampled.recycle()
}
```

Notes:
- `try/finally` guarantees both bitmaps are recycled even if `createScaledBitmap`,
  `FileOutputStream(...)`, or `compress()` throws. The throw still propagates and is
  swallowed by the surrounding `runCatching { ... }.getOrNull()`, so the method keeps
  returning `null` on failure (no behavior change for callers).
- `scaled !== sampled` (reference inequality) is the correct guard against
  double-recycling the same instance when no scaling occurred; `!=` invoked
  `Bitmap.equals`, which is reference equality at runtime but semantically wrong and
  brittle.

### Step 2 (RP-BUG-019): Snapshot the input list in cachePhotos()

```kotlin
// before
suspend fun cachePhotos(photos: List<OfflinePhotoEntity>) {
    photos.forEach { photo ->
        runCatching { cachePhoto(photo) }.onFailure { error ->
            ...
        }
    }
}
```

```kotlin
// after
suspend fun cachePhotos(photos: List<OfflinePhotoEntity>) {
    // Defensive copy: the suspending body yields the dispatcher between items, so a
    // caller that aliases/mutates the passed-in list could otherwise trigger a
    // ConcurrentModificationException. Today PhotoPrefetchWorker passes a fresh DB
    // query result and the worker is enqueued KEEP-uniquely, but iterating a stable
    // snapshot removes the latent hazard regardless of caller.
    photos.toList().forEach { photo ->
        runCatching { cachePhoto(photo) }.onFailure { error ->
            ...
        }
    }
}
```

This is intentionally minimal. We are not introducing a mutex or a concurrent map:
the only mutable shared state is Room (`localDataService`), which is already
thread-safe, and the sole caller (`PhotoPrefetchWorker` via
`PhotoCacheScheduler.schedulePrefetch`, `ExistingWorkPolicy.KEEP`) cannot run two
passes at once. The snapshot is the proportional fix for a P2 latent crash.

### Step 3 (RP-BUG-022): Correct LRU byte accounting in cleanUpUnused()

If only one of the two files deletes successfully, leave the DB cache flag untouched so the entry remains eligible for a later cleanup/recovery pass. The plan should log enough detail to diagnose repeated partial-delete failures rather than pretending the cache entry was fully removed.

```kotlin
// before
var totalBytes = entries.sumOf { it.totalBytes }

// Enforce maxBytes using LRU (oldest lastAccessedAt first)
if (totalBytes > maxBytes) {
    val lru = entries
        .filterNot { victims.contains(it) }
        .sortedBy { it.photo.lastAccessedAt?.time ?: 0L }

    lru.forEach { entry ->
        if (totalBytes <= maxBytes) return@forEach
        victims.add(entry)
        totalBytes -= entry.totalBytes
    }
}

victims.forEach { entry ->
    entry.original?.takeIf { it.exists() }?.delete()
    entry.thumbnail?.takeIf { it.exists() }?.delete()
    localDataService.markPhotoCacheFailed(entry.photo.photoId)
}
```

```kotlin
// after
// Bytes already accounted for by expired/zero-byte victims selected above must be
// removed from the running total before the LRU pass, otherwise we over-count and
// evict too many (or too few) files.
var totalBytes = entries.sumOf { it.totalBytes } - victims.sumOf { it.totalBytes }

// Enforce maxBytes using LRU (oldest lastAccessedAt first)
if (totalBytes > maxBytes) {
    val lru = entries
        .filterNot { victims.contains(it) }
        .sortedBy { it.photo.lastAccessedAt?.time ?: 0L }

    lru.forEach { entry ->
        if (totalBytes <= maxBytes) return@forEach
        victims.add(entry)
        totalBytes -= entry.totalBytes
    }
}

victims.forEach { entry ->
    val originalDeleted = entry.original?.takeIf { it.exists() }?.delete() ?: true
    val thumbDeleted = entry.thumbnail?.takeIf { it.exists() }?.delete() ?: true
    if (originalDeleted && thumbDeleted) {
        localDataService.markPhotoCacheFailed(entry.photo.photoId)
    } else {
        Log.w(TAG, "Cache cleanup could not fully delete files for photo ${entry.photo.photoId}")
    }
}
```

Notes:
- The leading `- victims.sumOf { it.totalBytes }` fixes the core RP-BUG-022 bug: the
  expire pass (old/zero-byte files) populates `victims` before `totalBytes` is
  computed, but those victims' bytes were never deducted, so the LRU loop started
  from an inflated total.
- Only clearing the DB cache flag (`markPhotoCacheFailed`) once the on-disk files are
  actually gone keeps Room consistent with disk when a `delete()` fails, addressing
  the "removed bytes not tracked if deletion fails" symptom. The warn log is
  console-only per the project's logging convention (no remote spam for a P3 path).

## Observability

### Current Signals
- Partial file-delete failures during cleanup are easy to miss today.

### New/Changed Signals
- Add a local `Log.w` when cleanup deletes only one side of an original/thumbnail pair or otherwise cannot fully clear a cache entry.
- Include only safe fields such as `photoId`, `originalExists`, `thumbExists`, `originalDeleted`, and `thumbDeleted`.

### Noise Control
- Local warn only; do not add remote log spam for routine cache cleanup retries.

### QA Verification Signal
- A forced partial-delete test run should emit exactly one warn with enough context to explain why the DB cache state was left retryable.

## Test Plan

- [ ] Unit tests added in `PhotoCacheManagerTest.kt`:
  - `cachePhotos` iterates a snapshot — mutating the passed list mid-iteration does
    not throw `ConcurrentModificationException` (RP-BUG-019).
  - `cleanUpUnused` evicts to at or below `maxBytes` and the running total reflects
    expired victims (assert exact remaining set) (RP-BUG-022).
  - `cleanUpUnused` does not mark a photo's cache as cleared when the on-disk file is
    undeletable / still present (RP-BUG-022).
  - Partial delete case: original deleted but thumbnail undeletable (and vice versa) leaves the DB cache state retryable and emits one local warn (RP-BUG-022).
  - Thumbnail generation on a large image produces a thumb and leaves no leaked
    bitmaps; a forced `compress` failure still returns `null` and does not crash
    (RP-BUG-009; bitmap recycle is hard to assert directly — verify no exception
    escapes and the method returns `null`).
- [ ] `./gradlew testDevStandardDebugUnitTest` green.
- [ ] `./gradlew compileDevStandardDebugKotlin` and `compileDevFlirDebugKotlin` clean.
- [ ] Manual QA:
  1. Prereq: project with many large photos, low cache budget.
  2. Action: prefetch photos, force-rotate / background the app repeatedly to push
     thumbnail generation, then trigger cache cleanup.
  3. Expected: no `IllegalStateException` (double-recycle) or OOM in logcat; cache
     size settles at or below `maxBytes`; DB cache flags match files on disk.

## Rollback Plan

Revert the single source file change (and the new test file). All three changes are
self-contained within `PhotoCacheManager.kt` with no schema, migration, or persisted
data impact, so reverting restores prior behavior immediately.

## Dependencies

- Requires: none (no server/API change).
- Blocking: none. The three fixes are independent within the file and can land
  together or be split per-bug if review prefers.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-009] Thumbnail generation no longer leaks bitmaps when JPEG encoding fails and uses a safe double-recycle guard.
- [RP-BUG-019] Photo caching iterates a stable snapshot, removing a latent ConcurrentModificationException risk.
- [RP-BUG-022] Photo cache LRU cleanup now accounts for expired victims and undeletable files correctly.
```
