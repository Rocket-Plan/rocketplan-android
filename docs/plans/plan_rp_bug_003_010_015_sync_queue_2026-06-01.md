**Bug ID(s):** RP-BUG-003, RP-BUG-010, RP-BUG-015
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [RP-BUG-003 Investigation](../investigations/RP-BUG-003_priorityqueue_threadsafety.md) · [RP-BUG-010 Investigation](../investigations/RP-BUG-010_sync_job_blocking.md) · [RP-BUG-015 Investigation](../investigations/RP-BUG-015_debounce_rapid_enqueues.md) · [Plan](./plan_rp_bug_003_010_015_sync_queue_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-003 / 010 / 015] Sync queue concurrency, head-of-line blocking, and debounce hardening

**Bug ID(s):** RP-BUG-003, RP-BUG-010, RP-BUG-015
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

Three closely-related issues in `SyncQueueManager.kt` (the single orchestrator for all sync jobs):

- **RP-BUG-003 (P1, thread-safety):** The investigation describes the `PriorityQueue` and `taskIndex` as accessed by multiple coroutines without protection. **On inspection this is largely already mitigated** — a `Mutex` exists at line 55 and *every* `queue` access (`add` line 464, `poll` line 474, `remove` lines 355/384/460/954, `clear` line 433) and every `taskIndex` access is wrapped in `mutex.withLock {}`. The residual risk is not a live unguarded path but the *fragility of relying on convention*: nothing structurally prevents a future caller from touching `queue`/`taskIndex` outside the lock, and the data structures are exposed as mutable fields. The fix is to make the invariant enforceable by encapsulating queue+index mutation behind locked helpers rather than relying on Android/JVM assertions to catch misuse.

- **RP-BUG-010 (P1, head-of-line blocking):** Confirmed real. In `execute()` the `SyncProjectGraph` branch launches the actual sync work as a child coroutine (`scope.launch(start = LAZY)`, line 844) and then calls `syncJob.join()` at line 938. Because `execute()` is called from the single `processLoop()` coroutine (line 497), this `join()` blocks the *entire queue* — including high-priority `ProcessPendingOperations` and `EnsureUserContext` — until one project's full graph + photo sync finishes. With many projects queued, critical pending-op upload is starved.

- **RP-BUG-015 (P2, debounce):** `observePendingOperations()` (line 996) uses `.debounce(750)` then `enqueue(ProcessPendingOperations)`. The enqueue is already idempotent (the `taskIndex[job.key]` dedup at line 455 collapses repeats into one queued job), so "multiple enqueues" do not actually produce duplicate work today. The useful clean-up is to keep the debounce as a named constant and make the coalescing story explicit: debounce controls noise, while queue deduplication is the real guarantee that rapid emissions collapse into one queued job.

These three are intentionally bundled because they touch the same fields and methods and **interact** — see the Ordering / Interaction section below.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/sync/SyncQueueManager.kt` | (003) Encapsulate `queue`/`taskIndex` mutation behind lock-guarded private helpers so direct mutation is structurally impossible outside the locked paths. (010) Stop `join()`-ing the project sync job inside `processLoop`; track it as an in-flight job and let the loop continue draining higher-priority work while keeping project sync concurrency capped at 1 in the first implementation. (015) Keep the debounce as a named constant and make queue coalescing explicit via enqueue dedup. |
| `app/src/test/java/com/example/rocketplan_android/data/sync/SyncQueueManagerTest.kt` | Add regression coverage: pending-ops job is not starved while a project sync is in flight (010); concurrent enqueue/poll stress test (003); rapid pending-op emissions coalesce to a single queued job (015). |

## Implementation Notes

### RP-BUG-010 — Step 1: Do not block the queue loop on the project sync job

Today the `SyncProjectGraph` branch registers the lazy job, starts it, then **joins** it on the loop coroutine:

```kotlin
// SyncQueueManager.kt ~928-938  (BEFORE)
mutex.withLock {
    activeProjectSyncJobs[job.projectId] = syncJob
    activeProjectModes[job.projectId] = mode
    updateProjectSyncingProjectsLocked()
}
syncJob.start()

// Wait for completion
syncJob.join()

// If fast sync succeeded, queue photo sync ...
if (syncSucceeded && mode == SyncJob.ProjectSyncMode.ESSENTIALS_ONLY) { ... }
```

The follow-up logic after `join()` (queueing the photo/content follow-up at lines 941-975, and the foreground-resume at lines 978-991) currently lives *inline after the join*. To stop blocking the loop, that follow-up logic must move *into* the project sync coroutine's completion path so the loop can return immediately.

Concretely, fold the post-join block into the launched coroutine (run it after the work completes, before/within the existing `finally`-driven cleanup), and remove the `join()` from the loop:

```kotlin
// (AFTER) — sketch
val syncJob = scope.launch(start = CoroutineStart.LAZY) {
    try {
        when (mode) { /* unchanged segment dispatch, sets syncSucceeded */ }

        // Follow-up work that previously ran AFTER join(), now runs here on the
        // child coroutine so processLoop() is free to drain other jobs.
        if (syncSucceeded && mode == SyncJob.ProjectSyncMode.ESSENTIALS_ONLY && !job.skipContentSync) {
            val shouldEnqueuePhotos = mutex.withLock { /* unchanged dedup logic, lines 946-961 */ }
            if (shouldEnqueuePhotos) {
                enqueue(SyncJob.SyncProjectGraph(projectId = job.projectId, prio = job.prio + 1,
                        skipPhotos = false, mode = SyncJob.ProjectSyncMode.CONTENT_ONLY))
            }
        }
        val shouldResumeBackground = mutex.withLock { /* unchanged logic, lines 978-985 */ }
        if (shouldResumeBackground) {
            val forcePending = pendingSyncProjectsForce.getAndSet(false)
            enqueue(SyncJob.SyncProjects(force = forcePending))
        }
    } finally {
        mutex.withLock { /* unchanged cleanup, lines 907-923 */ }
        notifier.tryEmit(Unit)
    }
}

mutex.withLock {
    activeProjectSyncJobs[job.projectId] = syncJob
    activeProjectModes[job.projectId] = mode
    updateProjectSyncingProjectsLocked()
}
syncJob.start()
// NOTE: no join() — execute() returns; processLoop continues to the next job.
```

### RP-BUG-010 — Step 2: Bound concurrency so we don't fan out unbounded project syncs

Removing `join()` means `processLoop` will immediately poll the next job. If that next job is another `SyncProjectGraph`, two project syncs now run concurrently — and with many queued, the loop could launch many at once. That is the *opposite* failure mode and must be bounded.

Take the conservative first implementation: keep project syncs effectively serialized with `MAX_CONCURRENT_PROJECT_SYNCS = 1`, but move the heavy work off the queue loop so pending operations and other non-project jobs are no longer starved. That resolves the reported starvation without introducing a second queue-selection algorithm or broad multi-project fan-out.

Add the constants in the companion object:

```kotlin
// companion object (~line 1042)
private const val MAX_CONCURRENT_PROJECT_SYNCS = 1
private const val PENDING_OPS_DEBOUNCE_MS = 750L
```

If future profiling shows value in running more than one project sync at a time, treat that as a follow-up with explicit fairness rules and a data-structure update. It is not required to fix RP-BUG-010.

### RP-BUG-003 — Step 1: Encapsulate queue + index mutation behind lock-guarded helpers

The queue is already only touched under `mutex`. Make that structurally enforced instead of convention-based by funnelling all mutations through private helpers, so future edits (including the 010 changes above, which add concurrency) cannot regress it. Do not rely on JVM `assert(...)` as the primary safeguard on Android; the main protection should be that queue/index mutation is impossible outside the helpers:

```kotlin
// (AFTER) private helpers — all mutate queue + taskIndex together
private fun enqueueLocked(task: QueuedTask) {
    queue.add(task)
    taskIndex[task.key] = task
}
private fun pollLocked(): QueuedTask? {
    return queue.poll()?.also { taskIndex.remove(it.key) }
}
private fun removeLocked(task: QueuedTask) {
    queue.remove(task)
    taskIndex.remove(task.key)
}
private fun clearLocked() {
    queue.clear(); taskIndex.clear()
}
```

Then replace the inline `queue.*`/`taskIndex.*` pairs at lines 353-356, 380-385, 433-434, 455-465, 474-475, 952-955 with these helpers. Net effect: the queue↔index invariant (they must stay consistent) lives in one place and future callers cannot partially mutate one structure without the other.

### RP-BUG-015 — Step 1: Make pending-ops coalescing explicit

```kotlin
// SyncQueueManager.kt 996-1004  (BEFORE)
private suspend fun observePendingOperations() {
    localDataService.observeSyncOperations(SyncStatus.PENDING)
        .debounce(750)
        .collect { ops ->
            if (ops.isNotEmpty()) {
                enqueue(SyncJob.ProcessPendingOperations)
            }
        }
}
```

```kotlin
// (AFTER)
private suspend fun observePendingOperations() {
    localDataService.observeSyncOperations(SyncStatus.PENDING)
        .debounce(PENDING_OPS_DEBOUNCE_MS)
        .collect { ops ->
            if (ops.isNotEmpty()) {
                enqueue(SyncJob.ProcessPendingOperations)   // already idempotent via taskIndex dedup
            }
        }
}
```

Keep the named `PENDING_OPS_DEBOUNCE_MS` constant so the debounce window is tunable in one place, but treat queue deduplication as the primary coalescing guarantee. Because the collector body only enqueues a single idempotent job, `collectLatest` does not add meaningful safety here; explicit debounce + dedup is the clearer plan.

## Ordering / Interaction between the fixes

These do not commute; implement in this order:

1. **RP-BUG-003 first (encapsulation).** Introduce the lock-guarded helpers and migrate existing call sites *with no behavioural change*. This establishes the single, audited choke point for queue mutation.
2. **RP-BUG-010 second (de-block + bound).** This is the highest-risk change: removing `join()` introduces real concurrency between project sync coroutines and the loop. Doing 003 first means the follow-up `enqueue(...)` and `removeLocked(...)` calls that move *into* the child coroutine already go through the audited helpers — so the new concurrency can't silently corrupt the queue/index pair. The concurrency cap (Step 2) is mandatory, not optional: without it, de-blocking converts head-of-line blocking into unbounded fan-out.
3. **RP-BUG-015 last (debounce).** Independent and lowest-risk, but it *feeds* the queue that 010 drains. Validate it after 010 so the test "rapid emissions → single job, and the loop is never starved by project syncs" exercises both fixes together.

Inter-fix dependency summary: 015 produces queue work; 010 changes how that work drains (and adds concurrency); 003 protects the shared structure both touch. 003 is a prerequisite-by-prudence for 010 (not a hard compile dependency). 015 is functionally independent but shares the same test surface.

## Observability

- (010) Add a debug log when a `SyncProjectGraph` job is deferred because `MAX_CONCURRENT_PROJECT_SYNCS` is reached, and when it eventually launches — so QA can confirm pending-ops jobs interleave. Keep console-only unless prod stalls persist.
- (003) No new production logging is added; the safety improvement is structural rather than log-based.
- Reuse existing `remoteLogger` failure path in `processLoop` (lines 500-511) — no new remote signals required.

## Testability / Test Seams

- Prefer behavior-based tests over raw internal state checks: assert job execution order, dedup outcomes, and that pending-op work can proceed while a project sync remains in flight.
- If queue internals must be inspected in tests, expose minimal test-visible helpers rather than reaching into mutable fields directly.

## Test Plan

- [ ] Unit test (010): enqueue a long-running `SyncProjectGraph` (fake repo that suspends) followed by `ProcessPendingOperations`; assert the pending-ops job executes while the project sync is still in flight (no starvation).
- [ ] Unit test (010): enqueue N+1 project graph jobs with `MAX_CONCURRENT_PROJECT_SYNCS = N`; assert at most N run concurrently and the rest stay queued.
- [ ] Unit test (003): concurrency stress — many coroutines call `enqueue` + drive `processLoop` simultaneously; assert no `ConcurrentModificationException` and that `queue.size` always equals `taskIndex.size` at quiescence.
- [ ] Unit test (015): emit several `PENDING` snapshots within the debounce window; assert exactly one `ProcessPendingOperations` is queued (debounce + enqueue dedup).
- [ ] Manual QA:
  1. Prereq: device with many assigned projects + several offline-created edits pending upload.
  2. Action: go online; observe sync. Watch logcat (`adb -s 30407ef logcat ... | grep -i -E 'sync|pending|project'`).
  3. Expected: pending edits upload promptly even while large project photo syncs are running (no multi-minute stall); no crash; no duplicate `ProcessPendingOperations` spam.

## Rollback Plan

All changes are confined to `SyncQueueManager.kt` (plus tests); no schema, no persisted-data, no API changes. Revert per-fix:
- 010 carries the real behavioural risk — if prod shows network/DB contention or ordering regressions, revert by restoring `syncJob.join()` in the loop and moving the follow-up block back out of the child coroutine (set `MAX_CONCURRENT_PROJECT_SYNCS = 1` as an intermediate step before full revert).
- 003 and 015 are low-risk; revert independently by inlining the helpers / restoring `.collect`.

## Dependencies

- Requires: none (no server/API change).
- Blocking: 010 should land on top of 003 (ordering above). 015 independent.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-010] Project photo/graph sync no longer blocks the sync queue; pending edits upload promptly even during large project syncs (with bounded project-sync concurrency).
- [RP-BUG-003] Hardened the sync queue's concurrency invariant by routing all queue/index mutation through lock-guarded helpers.
- [RP-BUG-015] Pending-operation observation now uses an explicit named debounce window plus queue dedup so rapid edit bursts coalesce into a single sync job.
```
