---
bug_id: RP-BUG-044
aliases: []
title: syncAllRoomPhotos swallows per-room photo-fetch failures — segment reports success with 0 photos, no retry, room left with photoCount>0 and no local photos
type: functional
classification: pre_existing_latent
source: internal
found_in: "1.0.00"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_044_sync_all_room_photos_partial_failure_2026-06-07.md
related_review: null
related_test: app/src/test/java/com/example/rocketplan_android/data/repository/sync/PhotoSyncServiceTest.kt
last_updated: 2026-06-07
---

# RP-BUG-044: `syncAllRoomPhotos` swallows per-room failures (reports success, never retries)

> Found 2026-06-07 while tracing RP-BUG-043 on-device. This is the defense-in-depth defect: even when
> the photo job *does* run, a transient per-room failure is hidden behind an overall `success`, so the
> room is left empty with no retry. Independent of RP-BUG-043 (the flag leak), but produces the same
> visible end-state (`photoCount>0`, `local photos=0`).

## Symptom

A room shows the correct `photoCount` (from metadata) but its photos never appear, and there is **no
pending sync op** to retry — even though the bulk photo sync ran and "succeeded." The user sees a room
that claims N photos but renders the placeholder / empty state indefinitely until an unrelated full
re-sync happens to succeed.

## Root cause (confirmed statically)

`PhotoSyncService.syncAllRoomPhotos` (`PhotoSyncService.kt:105–119`) fans out per-room fetches, then
aggregates — but a failed room only increments a counter and logs a warning; the segment still returns
**success**:

```kotlin
var totalPhotos = 0
var failedRooms = 0
for ((roomId, result) in results) {
    if (result.success) totalPhotos += result.itemsSynced
    else { failedRooms++; Log.w(TAG, "⚠️ Failed room $roomId", result.error) }   // ← swallowed
}
...
SyncResult.success(SyncSegment.ALL_ROOM_PHOTOS, totalPhotos, duration)            // ← success even if failedRooms > 0
```

Because the segment returns `success`, the caller's `results.all { it.success }` is `true`
(`SyncQueueManager.kt:906–918`), `syncSucceeded = true`, the job drains from the queue, no
`_projectEssentialsFailed` flag is set, and **nothing retries**. A single transient per-room error
(timeout, 5xx, parse failure) therefore permanently leaves that room at `photoCount>0, local=0` until an
unrelated future sync happens to re-fetch it.

The early-empty path (`:85–87`, `rooms.isEmpty()` → `success(…, 0, 0)`) is correct; the problem is
specifically that **partial failure is reported as full success**.

## Suggested fix (Group B)

- Return failure (or a partial-failure result the orchestrator treats as retryable) when
  `failedRooms > 0`, so the segment is re-queued instead of silently abandoned. Preserve any photos that
  *did* sync (don't discard `totalPhotos`); just don't mark the whole segment done.
- Alternatively, requeue only the failed room IDs (`ROOM_PHOTOS` segment already exists, `:1014`) with
  bounded retries, so one bad room doesn't force a whole-project re-fetch.
- Pairs with RP-BUG-043: with the flag-leak fixed, a returned failure will actually get a retry instead
  of being deduped away.

Cite the relevant **RP-CD** rule on not reporting success after partial/failed sync work when planning.

## Fix (implemented 2026-06-07)

`PhotoSyncService.syncAllRoomPhotos` now **bounded-retries** the rooms that fail, then reports the
terminal state truthfully:
- First parallel pass → collect failed room IDs → retry only those, up to `MAX_ROOM_PHOTO_RETRIES`
  (2) more attempts with `ROOM_PHOTO_RETRY_DELAY_MS` (400ms) backoff. `syncRoomPhotos` advances the
  per-room checkpoint only on success, so retries are incremental and synced rooms aren't refetched.
- Injectable `maxRoomRetries` / `retryDelayMs` (default in prod; tests pass `0`); callers
  (`syncProjectSegments`) unchanged.
- `totalPhotos` accumulates across passes (partial progress preserved).
- If no rooms remain failed → `Success` (as before). If rooms still fail → `SyncResult.Failure(
  ALL_ROOM_PHOTOS, …, itemsSynced = totalPhotos)` via the data-class ctor (the `failure()` factory drops
  `itemsSynced`), flipping the silent-success regression so `results.all { it.success }` is correctly
  `false` and `logSegmentFailures` fires.
- One-shot `SyncTelemetry` WARN remote log on terminal partial failure (`projectId`, `failedRooms`,
  `totalRooms`, `attempts`, `itemsSynced`, `failedRoomIds`) — terminal-state, no per-room spam, no raw
  user data.

Composes with **RP-BUG-043** (fixed): transient per-room errors recover in-place; persistent ones return
honest `Failure` and are re-attempted on foreground re-entry — which 043 no longer dedups away.

**Tests:** `PhotoSyncServiceTest` (5 cases): all-succeed; fail-then-retry-succeeds; **fail-every-attempt
→ Failure + partial progress kept + one-shot log** (the regression guard); no-rooms; 404/empty treated as
success (no retry). Full unit suite green.

### Related finding (not changed — scoped out)
`syncProjectLevelPhotos` (floor/location/unit) has a **milder** form of the same swallow: it returns
`Failure` only when **all three** types fail, so 1–2 failed types are reported as `success`. It is a
different surface (project-level, not the per-room `photoCount>0/local=0` symptom) and each type advances
its own checkpoint, so a failed type re-fetches incrementally on the next project sync. Left as-is per the
plan's scope discipline; revisit (same retry-and-report treatment) if evidence of a user-visible gap
appears.

## Verification (on-device 2026-06-07)

Confirmed live on the tablet (`30407ef`, in-place `install -r`, repro DB preserved). The room that was
stuck at `photoCount>0 / local=0` recovered when opened:
```
🔄 [syncAllRoomPhotos] Starting for project 5233
💾 [syncRoomPhotos] Saved 1 photos for room 6800
✅ [syncAllRoomPhotos] Synced 1 photos from 1/1 rooms in 721ms
```
DB: room 6800 went `local 0 → 1` (project 5235 rooms also fully populated: 4/4, 2/2). No transient
per-room failure occurred this session (healthy network), so the retry/terminal-failure path
(`🔁 Retrying` / `❌ still failed`) was **not** exercised on-device — that path is covered by the unit
tests (`fail-every-attempt → Failure + one-shot log`). Full suite green.

## Observability

### Current Signals
- Local console logs: `PhotoSyncService` — `⚠️ [syncAllRoomPhotos] Failed room <id>` and
  `✅ [syncAllRoomPhotos] Synced N photos from X/Y rooms` (when `X < Y`, rooms silently failed).
- Remote logs: `remoteLogger` is injected but the per-room failure is `Log.w` only — not sent remotely.
- Sentry: none (no throw; the error is caught into a non-success `SyncResult` and discarded).
- Existing metrics/watchdogs: none.

### Gaps
- A partially-failed bulk photo sync is indistinguishable from a fully-successful one at the segment
  boundary; the orchestrator can't tell it should retry.
- No durable record that room X's photos failed — only an ephemeral `Log.w`.

### Proposed Instrumentation
- Local debug logs to add: include the failure count and failed room IDs in the segment result.
- Remote logs to add: one-shot remote log when `failedRooms > 0` after the bulk sync completes
  (terminal-state, not per-room spam).
- Log category names: `sync.photo.partial_failure`.
- Key fields: `projectId`, `failedRooms`, `totalRooms`, `totalPhotos`.
- Sampling / throttling: one per project per bulk-sync run.
- Build/env gating: remote log in all builds; verbose IDs in debug.

### Success Criteria
- QA: simulate a per-room photo fetch failure (e.g. one room 5xx); the segment is reported failed/
  retryable and the room's photos appear after retry, not silently dropped.
- Wild: `sync.photo.partial_failure` remote logs correlate with eventual successful re-fetch (retry
  works), and no rooms remain at `photoCount>0, local=0`.
