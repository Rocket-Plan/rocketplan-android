**Bug ID:** RP-BUG-046, RP-BUG-047
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [RP-BUG-046 Investigation](../investigations/RP-BUG-046_moisture_log_422_dropped_no_detail.md) · [RP-BUG-047 Investigation](../investigations/RP-BUG-047_moisture_log_pull_400_invalid_include.md)

# Code Review: RP-BUG-046 / RP-BUG-047 moisture-log sync fixes

**Bug ID(s):** RP-BUG-046, RP-BUG-047
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 23:18:06 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
 M docs/BUG_TRACKER.md
 M docs/investigations/RP-BUG-046_moisture_log_422_dropped_no_detail.md
 M docs/investigations/RP-BUG-047_moisture_log_pull_400_invalid_include.md
?? data/
?? docs/reviews/code_review_rp_bug_047_2026-06-07.md
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

RP-BUG-047 looks correct and low-risk: Android was sending an invalid extra relation in the moisture-log
pull include string, and changing it to `include=photo` matches iOS without changing parsing or save logic.

RP-BUG-046's diagnosability patch is directionally right, but I found one remaining blind-drop path: a
422 returned during the 409-retry branch still drops without capturing the response body or sending the
remote diagnostic detail. Since RP-BUG-046 is explicitly about making 422s diagnosable before deciding the
real fix, that path should be brought up to the same standard.

## Findings

### Must Fix

1. **RP-BUG-046: the 409-retry 422 path still drops without the response-body detail.**

   In `MoistureLogPushHandler.handle409Conflict`, if the retry call returns 422, the handler currently
   does:

   - `Log.w(... "server validation error (422)")`
   - `return OperationOutcome.DROP`

   It does **not** extract `errorBody()?.string()`, does **not** attach `detail` to the remote log, and
   therefore reintroduces the same diagnosability gap this bug is trying to close.

   This matters because the investigation and commit message both state that `handleUpsert` now captures
   the 422 detail so the *next occurrence* names the failing rule. That statement is not true for this
   terminal retry branch today. Every terminal upsert 422 path should capture and report the same detail.

### Should Fix

1. **RP-BUG-047: add a regression test that asserts the exact include string is `photo`.**

   Existing tests stub `getRoomMoistureLogs(..., any())`, so the previous broken wire value
   (`photo,moisture_log`) would still have passed. A focused metadata-sync test should pin the exact query
   parameter.

2. **RP-BUG-046: add tests for the 422 detail-logging behavior.**

   The current suite verifies `DROP` on 422, but not that the handler extracts and forwards the response
   detail. Once the must-fix above is implemented, tests should cover:

   - create/update 422 → local/remote diagnostic detail recorded
   - 409 retry then 422 → same detail recorded there too

### Consider

1. **Centralize moisture-log 422 reporting in one helper.**

   The create/update path and the 409-retry path are already drifting. A small helper that formats the
   local log message, truncates detail, and emits the remote log would reduce repetition and make future
   behavior consistent.

### Verified Safe

1. **RP-BUG-047 is narrowly scoped.**

   The functional change is a one-line query-parameter correction in
   `ProjectMetadataSyncService.syncRoomMoistureLogs`.

2. **RP-BUG-047 leaves response handling untouched.**

   The dict/array/object parsing and local save semantics are unchanged, so the fix only enables the
   request to succeed.

3. **RP-BUG-046's current diagnostic payload is reasonably bounded.**

   The detail is truncated to 500 characters and emitted only on a terminal 422 path, which fits the
   tracker guidance against noisy remote logging.
