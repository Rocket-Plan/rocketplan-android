**Bug ID:** RP-BUG-043
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md) · [Plan](../plans/plan_rp_bug_043_pending_photo_syncs_leak_2026-06-07.md)

# Code Review: RP-BUG-043 pendingPhotoSyncs leak plan

**Bug ID(s):** RP-BUG-043
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 18:26:56 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
 M docs/BUG_TRACKER.md
?? data/
?? docs/investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md
?? docs/investigations/RP-BUG-044_sync_all_room_photos_swallows_per_room_failures.md
?? docs/plans/plan_rp_bug_043_pending_photo_syncs_leak_2026-06-07.md
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

The plan's primary direction is good: **derive, don't maintain** is the right fix class for a strand-able flag.

I agree with the decision to reject "put mode in the key" as the primary fix. The current mode-agnostic `project_<id>` key appears intentional, and the bug is fundamentally about a manually maintained flag diverging from real queue state.

However, the current derived-state proposal is **missing one queue state** that matters in this manager: `deferredProjectSyncs`.

## Findings

### Must Fix

1. **The derived photo-sync state must include `deferredProjectSyncs`, not just `activeProjectModes + taskIndex`.**

   The plan proposes:
   - active = `activeProjectModes.filterValues { includesPhotos() }`
   - queued = `taskIndex.values ... SyncProjectGraph ... includesPhotos()`

   But `SyncQueueManager` also has a third in-flight state:
   - `deferredProjectSyncs`

   In `processLoop()`, a `SyncProjectGraph` is first polled out of `taskIndex`, then may be moved into `deferredProjectSyncs` when `activeProjectSyncJobs.size >= MAX_CONCURRENT_PROJECT_SYNCS`.

   In that state the job is:
   - not in `taskIndex`
   - not in `activeProjectModes`
   - but still genuinely pending and intended to run later

   So if `recomputePhotoSyncingLocked()` only uses active + taskIndex, it will produce a **false negative**: a photo-bearing job can still be pending in `deferredProjectSyncs` while the spinner clears and dedup logic thinks nothing is pending.

   The same gap applies to the proposed derived dedup predicate (`isPhotoBearingSyncQueuedOrActiveLocked`). It should consider **active + queued + deferred** photo-bearing `SyncProjectGraph`s for the project.

### Should Fix

1. **State explicitly in the plan that all three queue states are authoritative inputs:**
   - active (`activeProjectModes`)
   - queued (`taskIndex`)
   - deferred (`deferredProjectSyncs`)

2. **Add a regression test for the deferred case.**
   Separate from the leak repro, add:
   - photo-bearing job moves to `deferredProjectSyncs`
   - `isProjectPhotoSyncing(projectId)` remains `true`
   - dedup still blocks duplicate photo enqueue while deferred
   - clears only after deferred job is re-enqueued/run/drained

### Consider

1. **The derive approach is still better than the minimal-diff fallback.**
   Even with the deferred-state amendment, the plan's main approach is preferable to keeping `pendingPhotoSyncs` and trying to harden every add/remove path forever.

### Verified Safe

1. **The root-cause analysis is strong.**
   The cited `add()`-then-`enqueue()` ordering and mode-agnostic key collision are real in the current code.

2. **Rejecting mode-in-key as the primary fix is reasonable.**
   The current queue semantics appear to rely on one project sync lane per project, and the plan correctly avoids broadening the fix unnecessarily.

## Recommended tweak to the plan

The recompute helper should be conceptually closer to:

```kotlin
private fun recomputePhotoSyncingLocked() {
    val active = activeProjectModes.filterValues { it.includesPhotos() }.keys
    val queued = taskIndex.values.mapNotNull { it.job as? SyncJob.SyncProjectGraph }
        .filter { it.mode.includesPhotos() }
        .map { it.projectId }
    val deferred = deferredProjectSyncs
        .filter { it.mode.includesPhotos() }
        .map { it.projectId }
    _photoSyncingProjects.value = (active + queued + deferred).toSet()
}
```

and the derived dedup predicate should use the same three sources.

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | Codex | 2026-06-07 |
