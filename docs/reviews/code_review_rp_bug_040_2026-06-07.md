**Bug ID:** RP-BUG-040
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-040_delete_409_stale_timestamp_retry_loop.md) · [Test](../../app/src/test/java/com/example/rocketplan_android/data/repository/sync/handlers/ResolveDeleteWithStaleRetryTest.kt)

# Code Review: RP-BUG-040 delete 409 stale-timestamp recovery

**Bug ID(s):** RP-BUG-040
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 13:55:33 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

`RP-BUG-040` is real, and the implemented helper-based fix is sound.

The new shared delete helpers correctly normalize the two Retrofit delete shapes:
- `Response<Unit>` endpoints now inspect HTTP status explicitly and retry a stale-lock `409` once with `updated_at = null`
- `Unit` endpoints now recover from thrown `HttpException(409)` the same way

That fixes both previously broken branches:
- Group A retry loops that kept resubmitting the stale timestamp
- Group B false-success deletes that ignored non-success `Response<Unit>` codes entirely

## Findings

### Must Fix

None.

### Should Fix

1. **Add direct tests for `resolveDeleteThrowingWithStaleRetry()`.**
   Current tests cover `resolveDeleteWithStaleRetry()` well, but the Room/Location/Property path uses the separate throwing helper. Its logic looks correct on inspection, but a small dedicated test file would lock down the `HttpException(409) -> retry null -> success / 422 / 404 / 500` behavior.

2. **Consider one handler-level regression test for a former Group B path.**
   Equipment or MoistureLog would be the highest-value candidate, because that was the worst prior behavior: `409/422/5xx` silently treated as success.

### Consider

1. **The helper names and comments are doing important safety work here.**
   Keep call sites routed through these helpers rather than open-coding per-handler delete behavior again.

### Verified Safe

1. **The core fix shape is correct.**
   `resolveDeleteWithStaleRetry()` retries only when the first response is `409` and a lock was actually supplied, then maps:
   - success / `404` / `410` -> finalize locally
   - `422` -> `DROP`
   - other failures -> throw `HttpException` for queue retry

2. **The throwing Retrofit variant is correctly handled.**
   `resolveDeleteThrowingWithStaleRetry()` applies the same recovery logic for `Unit` delete APIs where Retrofit throws instead of returning an error `Response`.

3. **The affected handlers are wired to the right helper type.**
   - `resolveDeleteWithStaleRetry()`: Note, AtmosphericLog, Equipment, MoistureLog
   - `resolveDeleteThrowingWithStaleRetry()`: Room, Location, Property

4. **The Group B silent-swallow class is actually fixed.**
   Equipment and MoistureLog now inspect the HTTP result through the helper and only return success after an effective delete; non-success responses no longer disappear silently.

5. **Existing tests cover the critical response-helper contract.**
   `ResolveDeleteWithStaleRetryTest` verifies:
   - straight success
   - `409` -> retry with `null`
   - `404` finalize
   - `422` drop
   - `500` throw for retry
   - `409` with no lock throws without second attempt

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | Codex | 2026-06-07 |
