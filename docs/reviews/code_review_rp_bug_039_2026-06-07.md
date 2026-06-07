**Bug ID:** RP-BUG-039
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-039_timecard_downsync_unwired.md) · [Test](../../app/src/test/java/com/example/rocketplan_android/data/repository/sync/TimecardSyncServiceTest.kt) · related [RP-BUG-038](../investigations/RP-BUG-038_material_duplicate_on_metadata_refresh.md)

# Code Review: RP-BUG-039 timecard down-sync unwired

**Bug ID(s):** RP-BUG-039
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 13:06:05 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

`RP-BUG-039` is real, and the implemented fix is directionally correct: Android now actively pulls project timecards via `TimecardSyncService.syncTimecards(projectId)`, wires that pull into `TimecardViewModel.init`, and persists the result through `saveTimecards(..., reconcileByServerId = true)` so the newly-live down-sync does not immediately introduce the RP-BUG-038 duplicate class.

I did not find a blocker in the current fix.

## Findings

### Must Fix

None.

### Should Fix

1. **Consider adding one test for the LocalDataService reconcile branch itself.**
   `TimecardSyncServiceTest` proves the service enables `reconcileByServerId = true`, but not that `saveTimecards()` actually preserves local PK + uuid for an existing timecard. A focused DAO/LocalDataService test would lock down the duplicate-prevention half of the fix.

2. **Consider eventually using the incremental `updated_date` filter.**
   The investigation already notes this as a non-blocking follow-up. The full fetch on screen open is acceptable for correctness, but likely not ideal long-term.

### Consider

1. **ViewModel-triggered pull on init is a minimal correctness fix, not necessarily the final sync architecture.**
   If timecards later join project metadata sync or a broader background sync pass, keep the reconcile requirement intact.

### Verified Safe

1. **The bug diagnosis is correct.**
   `OfflineSyncApi.getTimecards(projectId)` existed, `LocalDataService.saveTimecards(...)` existed, and the new `syncTimecards(projectId)` now closes that gap.

2. **The duplicate-prevention fix shape is correct.**
   `saveTimecards(..., reconcileByServerId = true)` uses `getTimecardsByServerIds(...)` plus `mergePulledRowsByServerId(...)`, adopting the local `timecardId` and `uuid` for clean rows and preserving dirty rows. That is the right mitigation for the RP-BUG-038 class once pull becomes live.

3. **The mapper evidence supports the investigation.**
   `TimecardDto.toEntity(existing = null)` falls back to `uuid ?: existing?.uuid ?: UuidUtils.generateUuidV7()`, so without reconciliation a pulled row would indeed get a fresh uuid when the backend provides none.

4. **The UI wiring is present.**
   `TimecardViewModel.init` now calls `syncTimecards()`, so opening the timecard screen triggers the down-sync.

5. **Tests cover the new service contract.**
   `TimecardSyncServiceTest` verifies:
   - timecards are pulled from `api.getTimecards(projectId)`,
   - persistence uses `reconcileByServerId = true`, and
   - API failure is surfaced as a failed `Result`.

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | Codex | 2026-06-07 |
