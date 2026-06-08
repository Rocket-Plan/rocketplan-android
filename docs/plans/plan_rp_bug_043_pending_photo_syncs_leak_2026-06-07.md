# Plan — RP-BUG-043: fix the `pendingPhotoSyncs` leak (stuck room-card spinner + photos never fetched)

- **Bug:** [RP-BUG-043](../investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md)
- **Pairs with:** [RP-BUG-044](../investigations/RP-BUG-044_sync_all_room_photos_swallows_per_room_failures.md) (per-room swallow; separate change)
- **Author:** Claude · 2026-06-07
- **Files:** `data/sync/SyncQueueManager.kt`, `data/sync/SyncJob.kt`
- **RP-CD:** sync-queue/`taskIndex` consistency — no manually-maintained state may diverge from actual queue state; no silent op loss.

## Root cause (recap)

`pendingPhotoSyncs` is a hand-maintained `Set<Long>` that drives **both** the room-card spinner
(`isProjectPhotoSyncing` → `ProjectDetailViewModel.isLoadingPhotos`) **and** the photo-enqueue dedup
guard. It is mutated as `add()` **then** `enqueue()` in three places, but `enqueue()` coalesces by key
(`"project_<id>"`, mode-agnostic) and can **silently drop** the job (`if (existing.priority <= job.priority) return`).
When the job is dropped, its `finally` — the only place that `remove()`s the flag — never runs, so the
flag strands: spinner forever + every later photo sync deduped away.

Three leak-prone add-then-enqueue sites (all identical shape):
- `refreshCurrentProjectPhotos()` :335 → `enqueue(PHOTOS_ONLY)`
- post-essentials follow-up :944 → `enqueue(CONTENT_ONLY, prio = job.prio + 1)`  ← the one observed leaking
- `resumeProjectPhotoSync()` :428 → `enqueue(CONTENT_ONLY)`

Remove sites: job `finally` (:986, gated `mode.includesPhotos()`), `cancelProjectSync` (:388),
`pauseProjectPhotoSync` (:414), `clear` (:463).

## Why not just "put mode in the key"

Tempting (`key = "project_${projectId}_${mode}"`), but rejected as the primary fix:
- The mode-agnostic key is **partly intentional** — it enforces *one* project sync in flight at a time.
- Photos require essentials first (`syncAllRoomPhotos`: *"rooms must already be synced by
  syncProjectEssentials"*). Distinct keys would let a PHOTOS/CONTENT job and an ESSENTIALS job sit in the
  queue simultaneously and potentially run out of order.

So the fix targets the **flag lifecycle**, not the coalescing semantics.

## Approach (recommended): derive `isProjectPhotoSyncing` from real queue state

Eliminate the strand-able flag entirely. A project is "photo syncing" **iff** it has an **active**,
**queued**, *or* **deferred** `SyncProjectGraph` whose `mode.includesPhotos()`. There is then no separate
set to leak — the spinner is always consistent with the queue.

> **Review correction (RP-BUG-043 review, 2026-06-07 — `docs/reviews/code_review_rp_bug_043_2026-06-07.md`):**
> the derived state must include **`deferredProjectSyncs`**, not just `activeProjectModes + taskIndex`.
> A photo-bearing job that was pulled but parked for a slot lives in `deferredProjectSyncs`
> (`Set<SyncProjectGraph>`, RP-BUG-010 no-busy-spin) — **outside `taskIndex`**. Omitting it reintroduces
> the bug as a false-negative: the spinner clears while a photo job is still pending, and the dedup
> predicate stops protecting that project (a second photo job could be enqueued, or worse, the UI reports
> "done" prematurely). All three sources are required.

### Changes

1. **Derive the StateFlow** from all three photo-bearing job sources, under `mutex`:
   ```kotlin
   private fun recomputePhotoSyncingLocked() {
       val active = activeProjectModes.filterValues { it.includesPhotos() }.keys
       val queued = taskIndex.values
           .mapNotNull { it.job as? SyncJob.SyncProjectGraph }
           .filter { it.mode.includesPhotos() }
           .map { it.projectId }
       val deferred = deferredProjectSyncs                      // ← REQUIRED (review fix)
           .filter { it.mode.includesPhotos() }
           .map { it.projectId }
       _photoSyncingProjects.value = (active + queued + deferred).toSet()
   }
   ```
   Call it anywhere any of the three change: `enqueue` (insert/replace **and** the early-return drop),
   `pollLocked`, execute start/finish (`activeProjectModes` put/remove), the defer add (:1013) and the
   defer drain (:998–1000), and `cancel`/`pause`/`clear`. `isProjectPhotoSyncing(p)` reads
   `_photoSyncingProjects.value.contains(p)`.

2. **Delete `pendingPhotoSyncs` and its add/remove sites.** Replace the three dedup checks
   (`if (pendingPhotoSyncs.contains(p)) skip`) with a derived predicate that covers **all three**
   sources:
   ```kotlin
   private fun isPhotoBearingSyncPendingLocked(projectId: Long): Boolean =
       activeProjectModes[projectId]?.includesPhotos() == true ||
       (taskIndex["project_$projectId"]?.job as? SyncJob.SyncProjectGraph)?.mode?.includesPhotos() == true ||
       deferredProjectSyncs.any { it.projectId == projectId && it.mode.includesPhotos() }   // ← REQUIRED
   ```
   This preserves the "don't double-queue photos" intent without a separate flag, and — critically — a
   project with a *deferred* photo job is still treated as protected.

3. **Keep the enqueue coalescing as-is**, but recompute the derived set inside `enqueue()` after a
   successful insert/replace and after the early-return drop, so the flow never reflects a job that was
   dropped — and recompute on every defer add/drain so a parked job is reflected, not lost.

### Net effect
- A dropped CONTENT_ONLY follow-up no longer strands anything; if an ESSENTIALS job is what occupied the
  key, its completion re-enqueues the photo follow-up (existing behavior), which now succeeds or is
  correctly reflected.
- Spinner shows exactly while a photo-bearing job is active/queued, and clears the instant the queue has
  none — even if a job was coalesced away.

## Alternative (minimal diff) — `enqueue` returns acceptance

If we want the smallest change instead of the derive refactor:
1. `private suspend fun enqueue(job: SyncJob): Boolean` — return `false` on the early-return drop, `true`
   when inserted or when it replaced a lower-priority existing job.
2. At each of the 3 sites: do the dedup check, call `enqueue(...)`, and **only** commit
   `pendingPhotoSyncs.add(p)` (under lock) when it returned `true`; never add on `false`.
   ```kotlin
   val accepted = enqueue(SyncProjectGraph(..., mode = CONTENT_ONLY, prio = job.prio + 1))
   if (accepted) mutex.withLock { pendingPhotoSyncs.add(job.projectId); updatePhotoSyncingProjectsLocked() }
   ```
   (Drop the pre-emptive add; the per-key coalescing in `enqueue` already prevents true double-queueing,
   so losing the flag as the *primary* dedup guard is safe.)

Trade-off: smaller diff, but keeps the hand-maintained set — still strand-able if any *future* path
removes a photo-bearing job from the queue without clearing the flag. The derive approach is immune to
that class. **Recommend the derive approach**; fall back to this if scope must stay tight.

## Regression coverage

Add `SyncQueueManagerPhotoSyncFlagTest` (or extend existing queue tests):
1. **Leak repro → fixed:** enqueue an ESSENTIALS for project P at prio 1; while it's "active," have its
   completion enqueue the CONTENT_ONLY follow-up at prio 2 while a same-key job at prio ≤ 2 occupies
   `taskIndex` (forces the drop). Assert `isProjectPhotoSyncing(P)` returns to `false` after the queue
   drains (today: stays `true`).
2. **Spinner consistency:** assert `photoSyncingProjects` contains P exactly while a photo-bearing job is
   active/queued and is empty once drained.
3. **Dedup still holds:** two rapid `refreshCurrentProjectPhotos()` for the same foreground project →
   only one photo-bearing job ends up queued/active.
4. **No false-negative (queued):** a legitimately queued PHOTOS_ONLY job marks the project
   photo-syncing.
5. **No false-negative (deferred) — review fix:** saturate the project-sync slots so a photo-bearing
   `SyncProjectGraph` is parked in `deferredProjectSyncs` (out of `taskIndex`). Assert
   `isProjectPhotoSyncing(P)` is still `true` while parked, the dedup predicate still skips a second
   photo enqueue for P, and the flag clears only after the deferred job is drained and completes.

Manual verification (device): reproduce on project 5233/room 6800 (currently stuck), apply fix, confirm
spinner clears and `offline_photos` populates after the photo sync runs.

## Out of scope
- The per-room failure swallow → **RP-BUG-044** (separate change; the two compose — once the flag can't
  strand, a returned failure actually gets retried instead of deduped).
- `skipPhotos` Project Detail behavior and the cloud indicator (RP-BUG-041) are unrelated.

## Steps
1. Implement derive approach in `SyncQueueManager.kt` (recompute helper covering
   **active + queued + deferred**; remove `pendingPhotoSyncs`). Wire `recomputePhotoSyncingLocked()`
   into every site that mutates `activeProjectModes`, `taskIndex`, **or `deferredProjectSyncs`** —
   including the defer add (:1013) and defer drain (:998–1000).
2. Update the 3 dedup checks to the derived predicate (`isPhotoBearingSyncPendingLocked`, which also
   checks `deferredProjectSyncs`).
3. Add `SyncQueueManagerPhotoSyncFlagTest`.
4. `./gradlew testDevStandardDebugUnitTest` (background) + `compileDevStandardDebugKotlin`.
5. Device verify on 5233/6800; update tracker `state: fixed`, set `related_plan`/`related_test`.
