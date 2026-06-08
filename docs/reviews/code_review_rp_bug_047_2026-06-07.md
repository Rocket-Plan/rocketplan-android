**Bug ID:** RP-BUG-047
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-047_moisture_log_pull_400_invalid_include.md)

# Code Review: RP-BUG-047 moisture-log invalid include

**Bug ID(s):** RP-BUG-047
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 23:07:20 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

The fix is correct and low-risk. Android was sending an extra `moisture_log` include that the backend
rejects with HTTP 400; changing the request to `include=photo` matches iOS and leaves the response parsing
path unchanged.

I found no must-fix defects in the implementation itself. The only notable gap is regression coverage: the
current test suite does not pin the include parameter, so this exact wire-format bug could be reintroduced
without a failing test.

## Findings

### Must Fix

None.

### Should Fix

1. **Add a regression test that asserts `getRoomMoistureLogs(..., include = "photo")`.**

   Current coverage in `OfflineSyncRepositoryTest` stubs `api.getRoomMoistureLogs(..., any())`, so the
   previous broken value (`"photo,moisture_log"`) would still have passed tests. A focused metadata-sync
   test should verify the exact include string to keep this backend contract from drifting again.

### Consider

1. **Keep the call-site comment.**

   The inline note in `ProjectMetadataSyncService.syncRoomMoistureLogs` usefully records the backend
   contract and the iOS parity source of truth for future maintainers.

### Verified Safe

1. **The code change is narrowly scoped.**

   The functional change is limited to the request query parameter at the single `getRoomMoistureLogs`
   call site in `ProjectMetadataSyncService`.

2. **The data-shaping logic is unchanged.**

   Parsing and persistence of the moisture-log response are untouched, so the fix does not alter local save
   semantics beyond allowing the request to succeed.

3. **The investigation evidence matches the implementation.**

   The investigation doc shows the failing Android URL, the iOS `include=photo` behavior, and the device
   verification after the fix (`200` responses with readings pulling down).
