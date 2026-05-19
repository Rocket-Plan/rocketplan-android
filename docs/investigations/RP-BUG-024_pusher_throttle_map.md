---
bug_id: RP-BUG-024
aliases: []
title: Pusher throttledErrorTimestamps Unbounded Growth
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: planned
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_024_pusher_throttle_map_2026-05-18.md
related_review: docs/reviews/code_review_rp_bug_024_027_2026-05-18.md
related_test: null
priority: P2
---

# Investigation: Pusher throttledErrorTimestamps Unbounded Growth

## Symptom

Remote error logging could consume excessive memory under heavy error activity with diverse error keys.

## Discovery

- **Reported by:** Code review
- **Evidence:** `PusherService.kt:48`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/realtime/PusherService.kt`

```kotlin
private val throttledErrorTimestamps = ConcurrentHashMap<String, Long>()
```

The throttling cache in `shouldThrottleRemoteLog()` (line 524-543) grows unbounded within the 60-second cutoff window. The cleanup at line 541 removes entries older than `max(EXPECTED_ERROR_LOG_THROTTLE_MS, 60_000)` which is 60 seconds - but the map can still grow very large during that window if there are many different errors.

## Root Cause

`MAX_THROTTLE_CACHE_SIZE = 128` at line 572 provides an additional bound, but entries are only removed when size exceeds this AND the entry is older than the cutoff. Under heavy error activity with diverse errors, this could still be large.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into throttle map size growth
- No upper bound on memory consumption

### Proposed Instrumentation
- Log warning when throttledErrorTimestamps exceeds threshold
- Add periodic cleanup of old entries

### Success Criteria
- Map size stays bounded under heavy error conditions
