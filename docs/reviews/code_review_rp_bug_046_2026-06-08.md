**Bug ID:** RP-BUG-046
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-046_moisture_log_422_dropped_no_detail.md) · [Prior Review](../reviews/code_review_rp_bug_046_047_2026-06-07.md)

# Code Review: RP-BUG-046 stranded moisture-log 422 path

**Bug ID(s):** RP-BUG-046
**Reviewer:** Codex
**Date:** 2026-06-08
**Timestamp:** 2026-06-08 11:31:46 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

RP-BUG-048 removes the most likely trigger for fresh moisture-log 422s, but RP-BUG-046 itself is still
not safe to close. I found one remaining must-fix issue: any upsert 422 still returns `DROP`, and the
queue processor responds by deleting the only sync operation while leaving the moisture-log row locally
`PENDING`/dirty. That recreates the exact "silently dropped and never retried" failure mode this bug is
tracking.

## Findings

### Must Fix

1. **Upsert 422s still strand moisture logs in `PENDING` with no queue operation.**

   `MoistureLogPushHandler.handleUpsert()` still maps validation failures to `OperationOutcome.DROP`
   (`app/src/main/java/com/example/rocketplan_android/data/repository/sync/handlers/MoistureLogPushHandler.kt:42-58`).
   `SyncQueueProcessor` handles `DROP` by removing the sync op outright
   (`app/src/main/java/com/example/rocketplan_android/data/repository/sync/SyncQueueProcessor.kt:221-237`).
   But `MoistureLogSyncService.upsertMoistureLogOffline()` only enqueues on author/edit time
   (`app/src/main/java/com/example/rocketplan_android/data/repository/sync/MoistureLogSyncService.kt:18-26`),
   and although `OfflineDao.getPendingMoistureLogs()` can find dirty/non-synced rows, nothing uses it to
   reconstruct missing ops (`app/src/main/java/com/example/rocketplan_android/data/local/dao/OfflineDao.kt:865-873`).

   So if a 422 is recoverable, misclassified, or already present in historical data, the log remains in the
   UI as pending forever but can never sync again. RP-BUG-048 lowers recurrence risk; it does not repair
   this data-loss behavior.

### Should Fix

1. **Add a processor-level regression test for the stranded-row behavior.**

   Current handler tests verify `DROP`, detail capture, and retry-path logging, but they do not assert that
   a 422 leaves the system in a recoverable state. Add coverage around the queue processor or repository
   flow that proves one of these outcomes after a 422:

   - the op is retained/retryable, or
   - the row is marked with an explicit surfaced failure state, or
   - a repair path re-enqueues it.

2. **Add a repair path for already-stranded moisture logs.**

   The four known room-6804 readings were re-pointed to the canonical material by RP-BUG-048's collapse
   step, but this review did not find any code that recreates their missing queue entries. A targeted
   repair/re-enqueue pass is still needed before RP-BUG-046 can be closed.

### Consider

1. **Re-run the live repro after re-enqueue/repair lands.**

   Once stranded rows can be replayed, verify whether RP-BUG-048 fully removed the original 422 trigger or
   whether a second backend-rule issue still exists.

### Verified Safe

1. **The diagnosability work from the prior review remains in place.**

   Both the initial 422 path and the 409→retry→422 path now capture bounded response-body detail.

2. **RP-BUG-048 meaningfully reduces fresh RP-BUG-046 risk.**

   Canonical material reuse plus duplicate collapse/backfill removes the known duplicate-material path that
   was stranding moisture-log children.
