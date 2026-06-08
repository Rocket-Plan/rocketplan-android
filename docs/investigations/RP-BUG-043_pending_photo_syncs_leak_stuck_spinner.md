---
bug_id: RP-BUG-043
aliases: []
title: pendingPhotoSyncs leaks when the CONTENT_ONLY follow-up job is coalesced/dropped by enqueue — room card spinner hangs forever and the project's photos never download
type: hang
classification: pre_existing_latent
source: internal
found_in: "1.0.00"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_043_pending_photo_syncs_leak_2026-06-07.md
related_review: docs/reviews/code_review_rp_bug_043_2026-06-07.md
related_test: app/src/test/java/com/example/rocketplan_android/data/sync/SyncQueueManagerPhotoSyncFlagTest.kt
last_updated: 2026-06-07
---

# RP-BUG-043: `pendingPhotoSyncs` leak → permanent room-card spinner + photos never fetched

> Found on-device 2026-06-07 by pulling the Room DB (`rocketplan_offline.db`) off the tablet
> (`30407ef`, build `1.29 (32)-dev`) and tracing the sync queue. Distinct from RP-BUG-044 (the
> per-room swallow), though both leave a room at `photoCount>0, local photos=0`.

## Symptom

On Project Detail, a room card (e.g. **Bedroom** in project `5233` / RP-26-1363 "ueuueur") shows an
indeterminate **loading spinner that never stops**, and the room's photos **never download**. Backing
out and re-opening does not clear it. The DB confirms the room knows it should have a photo but has none:

```
offline_rooms:  roomId=6800 serverId=6800 title=Bedroom photoCount=1 thumbnailUrl=NULL
offline_photos: 0 rows for project 5233
offline_sync_queue: empty            ← no pending op, so nothing will ever retry
```

Whole-DB scan — 4 rooms across 2 assigned projects in this state, while 2 other projects downloaded fine:

| Room | Project | server photoCount | local photo rows |
|------|---------|-------------------|------------------|
| 6179 Bathroom / 6232 Bathroom 2 / 6233 Bedroom 2 | 5066 | 24 | 0 |
| 6800 Bedroom (the reported one) | 5233 | 1 | 0 |
| 6801 Bedroom | 5234 | 2 | **2** ✅ |
| 6803 | 5228 | — | **1** ✅ |

All four projects are assigned to the signed-in admin (user 832), so this is **not** the
`skipContentSync` unassigned-gate.

## Root cause (confirmed statically)

One leaked entry in `SyncQueueManager.pendingPhotoSyncs` drives **both** failures, because:

1. The room-card spinner reduces to exactly that flag. In `ProjectDetailViewModel` (~:362):
   ```kotlin
   // Bedroom: resolvedPhotoCount=1, roomPhotos empty, serverId!=null
   isLoadingPhotos = hasAnyPhotos && ( serverId==null
       || (isProjectPhotoSyncing && resolvedPhotoCount > roomPhotos.size)
       || (isProjectPhotoSyncing && roomPhotos.isEmpty()) )
   // ⇒ isLoadingPhotos == isProjectPhotoSyncing
   ```
   and `isProjectPhotoSyncing` is `pendingPhotoSyncs.contains(projectId)` exposed via
   `photoSyncingProjects` (`SyncQueueManager` :361, :1175–1176).
2. The same set is the photo-enqueue dedup guard (:331, :940). A leaked entry → every future photo
   enqueue logs `⏭️ Photo sync already pending … skipping duplicate` and never runs.

### Why it leaks

`SyncProjectGraph`'s queue key **ignores the mode** (`SyncJob.kt:32`):
```kotlin
SyncProjectGraph(...) : SyncJob(priority = prio, key = "project_$projectId")
```
So ESSENTIALS / CONTENT / PHOTOS / METADATA / FULL for one project all collide on `project_<id>`.
`enqueue()` coalesces by key and silently drops the lower-priority loser (`SyncQueueManager.kt:483`):
```kotlin
val existing = taskIndex[job.key]
if (existing != null) {
    if (existing.priority <= job.priority) return     // ← drop the new job entirely
    else queue.remove(existing)
}
```

The fatal, non-atomic sequence after an ESSENTIALS sync completes (`SyncQueueManager.kt:940–950`):
```kotlin
val shouldEnqueuePhotos = mutex.withLock {
    if (pendingPhotoSyncs.contains(job.projectId)) false
    else { pendingPhotoSyncs.add(job.projectId); ...; true }   // (1) flag committed
}
if (shouldEnqueuePhotos) {
    enqueue(SyncProjectGraph(mode = CONTENT_ONLY, prio = job.prio + 1))  // (2) may be dropped
}
```
`prio + 1` is a *low* priority, so if any other `project_<id>` task is already in `taskIndex`
(background `SyncProjects` refresh, a Pusher `refreshCurrentProjectPhotos`, or re-navigation — all
routine), `existing.priority <= job.priority` holds and the CONTENT_ONLY job in step (2) is dropped.
But step (1) already added the project to `pendingPhotoSyncs`. The **only** code that removes it is the
job's `finally` (`:986`, gated on `mode.includesPhotos()`) — which never runs because the job never
existed. → `pendingPhotoSyncs` leaks the project permanently.

This matches the captured logcat exactly: `queueing photo sync at priority 0` immediately followed by
`Photo sync already pending … skipping duplicate`, then silence; and the empty `offline_sync_queue`.

## Suggested fix (Group A)

The flag mutation must be paired to an *accepted* enqueue, and/or jobs of different purpose must stop
colliding. Options (combine 1+2 preferred):
1. Make `enqueue()` return whether it accepted the job; only `pendingPhotoSyncs.add(...)` when it did
   (and reconcile/clear the flag when a photo-bearing job is dropped or replaced).
2. Include `mode` (or at least a photos-vs-not discriminator) in `SyncProjectGraph.key` so the photo
   follow-up no longer coalesces against essentials/metadata jobs for the same project.
3. Derive `isProjectPhotoSyncing` from actual in-flight/queued state rather than a manually-maintained
   set, so it can't strand.

Touches **RP-CD** sync-queue invariants — cite the relevant rule when planning (queue/`taskIndex`
consistency; no silent op loss).

## Fix (implemented 2026-06-07)

Took the **derive** option (plan option 3), amended per the review to include the deferred source.
In `SyncQueueManager.kt`:
- **Deleted the `pendingPhotoSyncs` flag.** `_photoSyncingProjects` is now recomputed inside
  `updateProjectSyncingProjectsLocked()` from **active + queued + deferred** photo-bearing
  `SyncProjectGraph` jobs (`mode.includesPhotos()`). All three sources required (review fix:
  `deferredProjectSyncs` lives outside `taskIndex`).
- **New `isPhotoBearingSyncPendingLocked(projectId)`** predicate (active OR queued OR deferred) replaces
  every `pendingPhotoSyncs.contains(...)` dedup/in-flight check (5 sites: `refreshCurrentProjectPhotos`,
  `resumeProjectPhotoSync`, the post-essentials follow-up, `isProjectSyncInFlight`, `focusProjectSync`).
- **Removed all `add()`/`remove()` flag mutations**; the dedup sites now just check the predicate and let
  the follow-up `enqueue()` (with its per-key coalescing) be the real double-queue guard — so a coalesced
  drop can no longer strand anything.
- **Wired the recompute into the missed sites:** the defer-add (a parked photo job now counts) and the
  defensive cleanup; `cancelProjectSync`/`pauseProjectPhotoSync` now also purge matching deferred jobs so
  derived state clears.
- Added a `📷 photoSyncingProjects changed: … → …` log so the lifecycle (including the clear) is visible
  in logcat.

A leaked-flag strand is now structurally impossible — the spinner state is a pure function of the queue.

**Tests:** `SyncQueueManagerPhotoSyncFlagTest` (5 cases): deferred/active/queued each mark photo-syncing;
**drain-clears-no-strand** (the leak-fix guard); non-photo ESSENTIALS excluded from the photo set but
still counted as a project sync. Full unit suite green. Remaining: on-device confirmation on 5233/6800.

## Verification (on-device 2026-06-07)

Confirmed live on the tablet (`30407ef`, in-place `install -r` over `1.29 (32)-dev`, repro DB preserved).
Opening the originally-stuck Bedroom (room 6800, project 5233) produced:
```
RoomDetailVM: ensureRoomPhotosFresh -> syncRoomPhotos(projectId=5233, remoteRoomId=6800)
💾 [syncRoomPhotos] Saved 1 photos for room 6800
✅ [syncAllRoomPhotos] Synced 1 photos from 1/1 rooms in 721ms
📷 photoSyncingProjects changed: [5233] → []     ← derived flag clears, no strand
```
The `photoSyncingProjects` set was observed transitioning back to `[]` after every sync cycle across
multiple projects (5235, 5233) — the leak's permanent-`[projectId]` state no longer occurs. DB: room 6800
went `local 0 → 1`. (Plus unit tests + full suite green.)

## Observability

### Current Signals
- Local console logs: `SyncQueueManager` — `⏭️ Fast sync completed … queueing photo sync at priority N`
  then `⏭️ Photo sync already pending for project N, skipping duplicate` (the leak fingerprint).
- Remote logs: none for this path (the dedup lines are `Log.d`, console-only).
- Sentry: none (no crash; it's a silent hang).
- Existing metrics/watchdogs: none. `offline_sync_queue` being empty while a room shows a spinner is the
  only durable signal, and it requires a manual DB pull.

### Gaps
- A dropped/coalesced photo job leaves no trace and no retry; the leaked `pendingPhotoSyncs` flag is
  invisible at runtime.
- "Spinner is spinning" is indistinguishable from "sync in progress" vs "sync flag stranded."

### Proposed Instrumentation
- Local debug logs to add: log when `enqueue()` drops/replaces a job that the caller had already
  reflected in `pendingPhotoSyncs`; log every `pendingPhotoSyncs` add/remove with the reason.
- Remote logs to add: one-shot terminal log when a project's photo sync is requested but the flag is
  still set with an empty queue after the loop drains (stranded-flag detector).
- Log category names: `sync.photo.flag`, `sync.queue.coalesce`.
- Key fields: `projectId`, `mode`, `priority`, `existingPriority`, `accepted`.
- Sampling / throttling: one-shot per project per strand; no progress spam.
- Build/env gating: debug adds verbose; remote strand-detector in all builds.

### Success Criteria
- QA: open every project's rooms after a fresh login; no room card spins indefinitely, and
  `offline_photos` populates for every room with `photoCount>0`.
- Wild: stranded-flag remote log count trends to zero; no "spinner forever" reports.
