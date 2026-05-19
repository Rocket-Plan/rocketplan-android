**Bug ID:** RP-BUG-024
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-024_pusher_throttle_map.md) · [Review](../reviews/code_review_full_2026-05-18.md)

# Fix Plan: [RP-BUG-024] Bound Pusher error-throttle cache growth

**Bug ID(s):** RP-BUG-024
**Author:** jeremie.blais@jot.digital
**Date:** 2026-05-18
**State:** approved

---

## Summary

`PusherService.shouldThrottleRemoteLog()` stores error signatures in `throttledErrorTimestamps`, but the current cleanup only removes entries older than the 15-minute throttle window. A burst of many unique warnings inside that window can grow the map far beyond the intended cap of 128 entries. Fix by making the cache size-bounded independent of entry age, while preserving duplicate-log suppression.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/realtime/PusherService.kt` | Replace age-only cleanup with deterministic size-bounded eviction; keep warning/error throttling behavior intact. |
| `app/src/test/java/com/example/rocketplan_android/realtime/PusherServiceTest.kt` | Add regression tests for cache growth, throttle-window behavior, and eviction of oldest entries. |

## Implementation Notes

### Step 1: Separate "duplicate suppression" from "cache capacity"

Keep the current key shape (`message|code|exception`) and 15-minute suppression window, but stop relying on "old enough" cleanup as the only bound. After inserting a new key:

- remove expired entries first
- if the cache is still over `MAX_THROTTLE_CACHE_SIZE`, evict the oldest timestamps until the map is back under the cap

This makes the cap real even when all errors are fresh.

### Step 2: Keep the logic local and thread-safe

`ConcurrentHashMap` is already used, but capacity trimming should happen in one helper so the behavior is easy to reason about. The simplest implementation is:

- compute `now`
- early-return for recent duplicate keys
- write `key -> now`
- call a helper that prunes expired entries, then trims oldest survivors down to the cap

Given the tiny target size (128), an O(n log n) oldest-first trim is acceptable.

### Step 3: Preserve current remote logging policy

- `LogLevel.ERROR` remains unthrottled
- warning/debug/info connection chatter still uses the cache
- no new remote log spam should be added just for cache maintenance

## Observability

- Add a local debug log only when a trim actually happens and the cache was above the configured cap.
- Do not emit a remote log for normal trimming; this is an internal housekeeping path and could become noisy during outages.

## Test Plan

- [ ] Unit test: inserting more than 128 distinct non-error keys leaves the cache bounded at `MAX_THROTTLE_CACHE_SIZE`.
- [ ] Unit test: a repeated warning inside the throttle window is still suppressed.
- [ ] Unit test: an expired entry is allowed through again after the throttle window passes.
- [ ] Unit test: when all entries are fresh and unique, the oldest entries are evicted instead of allowing unbounded growth.

## Rollback Plan

Revert the `PusherService.kt` helper change. This is isolated to log-throttling state and does not affect persisted data or network protocol behavior.

## Dependencies

- Requires: none
- Blocking: none

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-024] Bounded Pusher warning-throttle cache growth during high-volume realtime connection errors.
```

## Post-implementation Review Notes

- **Review:** [code_review_rp_bug_024_027_2026-05-18.md](../reviews/code_review_rp_bug_024_027_2026-05-18.md)
- **Status:** implemented and validated

### Follow-up Required

- No bug-specific blockers remain.
- Any remaining `PusherServiceTest.kt` compilation/dependency cleanup should be tracked separately as test-suite maintenance, not as RP-BUG-024 work.
