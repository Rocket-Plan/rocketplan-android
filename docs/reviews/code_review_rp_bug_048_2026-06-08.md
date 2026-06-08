**Bug ID:** RP-BUG-048
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-048_duplicate_local_materials_collapse_to_one_serverid.md)

# Code Review: RP-BUG-048 create-side duplicate-material fix

**Bug ID(s):** RP-BUG-048
**Reviewer:** Codex
**Date:** 2026-06-08
**Timestamp:** 2026-06-08 09:18:35 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

The implementation addresses the core create-side failure correctly.

The prior must-fix from the investigation review is now satisfied:
- RocketDry no longer re-resolves the material by a project-wide name match
- `addMaterialDryingGoal` now returns the canonical `materialId`
- `SetLatestAverageFragment` threads that `materialId` directly into `addMaterialMoistureLog`

The new `getMaterialByNameInRoom(roomId, name)` lookup is appropriately room-scoped, case-insensitive,
and deterministic. I did not find a new must-fix issue in the landed code.

## Findings

### Must Fix

None.

### Should Fix

1. **Add a higher-level regression test for the full RocketDry flow, not just the DAO lookup.**

   `MaterialByNameInRoomDaoTest` is good coverage for the canonical-pick query, but the bug was really in
   the end-to-end "new material + goal + first reading" flow. A focused view-model or repository-level
   test should pin that:

   - repeated by-name authoring in the same room reuses one `materialId`
   - the returned `materialId` is the one used for the reading insert
   - no caller can regress back to name re-resolution

2. **Keep the collapse/backfill follow-up explicit in implementation tracking.**

   This commit prevents **new** duplicates, but it intentionally does not repair **existing** duplicate
   rows or collapse future serverId collisions if old data is already bad. That is acceptable for this fix,
   but the follow-up should remain clearly tracked until the four stranded rows are repaired.

### Consider

1. **Add an observability rerun note once the detector is re-executed after authoring fresh data.**

   The detector script is a strong production-like check. After QA creates multiple readings for the same
   material on a clean path, recording a fresh `scripts/check_sync_duplicates.sh` pass for materials would
   make the closure evidence stronger.

### Verified Safe

1. **The prior review's identity-threading concern is resolved.**

   `addMaterialMoistureLogByName` was removed, so the reading no longer attaches via an arbitrary
   project-wide `firstOrNull` match.

2. **The reuse lookup is room-scoped in a way that matches the bug.**

   `OfflineDao.getMaterialByNameInRoom(roomId, name)` joins through non-deleted moisture logs in the room,
   which matches the RocketDry concept of "a material already present here."

3. **Canonical selection is deterministic.**

   The DAO prefers a synced row (`serverId > 0`) and then lowest `materialId`, which is a sensible and
   stable tie-breaker when duplicates already exist locally.

4. **The fix is narrowly scoped.**

   The change is contained to RocketDry material authoring plus a DAO/helper addition; it does not alter
   broader sync reconciliation behavior.
