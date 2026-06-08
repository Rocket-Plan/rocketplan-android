# Plan — RP-BUG-044: stop `syncAllRoomPhotos` swallowing per-room failures (retry, then report)

- **Bug:** [RP-BUG-044](../investigations/RP-BUG-044_sync_all_room_photos_swallows_per_room_failures.md)
- **Pairs with:** [RP-BUG-043](../investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md) (fixed)
- **Author:** Claude · 2026-06-07
- **Files:** `data/repository/sync/PhotoSyncService.kt` (primary); test in
  `app/src/test/java/.../repository/sync/PhotoSyncServiceTest.kt`
- **RP-CD:** no reporting success after partial/failed sync work (cite the relevant sync-result rule).

## Root cause (recap + the deeper catch)

`PhotoSyncService.syncAllRoomPhotos` (`PhotoSyncService.kt:105–119`) fans out per-room fetches and
aggregates, but returns `SyncResult.success(ALL_ROOM_PHOTOS, …)` **even when `failedRooms > 0`** — a
failed room is only `Log.w`'d. `syncRoomPhotos` itself is already correct: it returns a typed
`SyncResult.Failure(ROOM_PHOTOS, error)` on a real (non-404) fetch error and does **not** advance the
per-room checkpoint, so a retry would re-fetch. The defect is purely the aggregation.

**Deeper catch (confirmed in `SyncQueueManager`):** for `CONTENT_ONLY` / `PHOTOS_ONLY` modes,
`syncSucceeded = results.all { it.success }` and on `false` the orchestrator **only** calls
`logSegmentFailures` — there is **no** auto-retry, no `_projectEssentialsFailed`, no requeue (that flag is
set only for `ESSENTIALS_ONLY`/`FULL`). So simply returning `Failure` makes the failure *honest* but does
**not** get the room's photos fetched. The fix must therefore include an **actual retry**, not just a
corrected return value.

## Approach: bounded in-function retry of failed rooms, then report truthfully

Self-contained in `PhotoSyncService` — no orchestrator changes, no per-room job fan-out into the queue.

### Changes to `syncAllRoomPhotos`

1. **First pass** (unchanged): fetch all rooms in parallel via `syncRoomPhotos`.
2. **Collect failed room IDs** from results where `!result.success`.
3. **Bounded retry loop** over only the failed rooms: up to `MAX_ROOM_PHOTO_RETRIES` (e.g. **2**)
   additional attempts, with a short backoff (e.g. `delay(retryDelayMs)`, ~300–500ms, gated/0 in tests
   via an injectable param to keep them fast). Each attempt re-runs `syncRoomPhotos` for the
   still-failing rooms in parallel; drop rooms that now succeed. Because `syncRoomPhotos` only advances
   the checkpoint on success, already-synced rooms are untouched and retries are incremental.
4. **Aggregate & report truthfully:**
   - Preserve `totalPhotos` synced across all passes (don't discard partial progress).
   - If **no** rooms remain failed → `SyncResult.success(ALL_ROOM_PHOTOS, totalPhotos, duration)` (as today).
   - If rooms **still** fail after retries → `SyncResult.failure(ALL_ROOM_PHOTOS, cause, duration, totalPhotos)`
     where `cause` is the first remaining room's error (or an aggregate exception naming the failed room
     IDs). This makes `results.all { it.success }` correctly `false`, firing `logSegmentFailures` +
     remote telemetry instead of a silent success.
5. **One-shot partial-failure remote log** when any room ultimately fails (terminal-state, not per-room
   spam): `projectId`, `failedRooms`, `totalRooms`, `attempts`, `totalPhotos`. No raw user data.

### Why in-function retry (not requeue)
- Transient per-room errors (timeout, momentary 5xx — the common case) recover **immediately** within
  the same bulk sync, so the room never lands in the empty `photoCount>0 / local=0` state.
- A `ROOM_PHOTOS`-segment requeue (alternative) is heavier and still hits the orchestrator's
  no-auto-retry gap for photo modes; in-function retry sidesteps it entirely.
- Persistent failures still return `Failure` (now honest + logged) and are naturally re-attempted on the
  next project sync / foreground re-entry (`focusProjectSync`). With **RP-BUG-043 fixed**, that re-entry
  is no longer deduped away by a stranded flag — so the two fixes compose: 043 lets the retry happen,
  044 makes the bulk sync retry-and-report instead of lying.

### Signature
Add an injectable for testability, defaulted for production:
```kotlin
suspend fun syncAllRoomPhotos(
    projectId: Long,
    maxRoomRetries: Int = MAX_ROOM_PHOTO_RETRIES,   // default 2
    retryDelayMs: Long = ROOM_PHOTO_RETRY_DELAY_MS, // default ~400; tests pass 0
): SyncResult
```
Callers (`syncProjectSegments` :1012) use the defaults — no call-site change.

## Non-goals / out of scope
- The orchestrator's lack of auto-retry for photo segments generally (a broader change); this plan only
  makes the bulk room-photo sync resilient to per-room transients and honest on persistent failure.
- `syncProjectLevelPhotos` (project-level) — separate path; only fix here if it shows the same swallow
  (it returns its own result; verify but don't expand scope without evidence).
- The early-empty path (`rooms.isEmpty()` → `success(…,0,0)`) is correct and unchanged.

## Regression coverage — `PhotoSyncServiceTest`
Mock `api`/`localDataService`/`syncCheckpointStore` (the constructor deps) and `observeRooms` to return
N rooms. Cases:
1. **All rooms succeed** → `Success`, `itemsSynced == sum`, no remote partial-failure log.
2. **One room fails on first pass, succeeds on retry** (`maxRoomRetries=2`, `retryDelayMs=0`) →
   `Success`; assert `syncRoomPhotos` was called twice for the flaky room, once for the others.
3. **One room fails every attempt** → `Failure(ALL_ROOM_PHOTOS, …)` (the regression guard — today this
   wrongly returns `Success`); assert `totalPhotos` still reflects the rooms that succeeded; assert the
   one-shot partial-failure remote log fired exactly once with the failed room ID.
4. **No rooms** (`observeRooms` empty) → `Success(…, 0, 0)`, no retry, no log (unchanged).
5. **404 from a room** is treated as success/empty (no photos), not a failure → does not trigger retry.

Manual (device, optional): force a room photo 5xx (or airplane-mode mid-sync) and confirm the room
recovers on retry and isn't left at `photoCount>0 / local=0`.

## Steps
1. Implement bounded retry + truthful aggregation in `syncAllRoomPhotos` (+ constants, injectable params,
   one-shot partial-failure remote log).
2. Verify `syncProjectLevelPhotos` for the same pattern; fix only if it swallows (note finding either way).
3. Add `PhotoSyncServiceTest` (5 cases above).
4. `compileDevStandardDebugKotlin` + `testDevStandardDebugUnitTest` (background).
5. Update tracker `state: fixed`, set `related_plan`/`related_test`; add "Fix (implemented)" section.
