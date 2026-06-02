---
bug_id: RP-BUG-004
aliases: []
title: Silent Fallback When ConnectivityManager Unavailable
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: fixed
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_004_connectivity_silent_fallback_2026-06-01.md
last_updated: 2026-06-01
related_review: docs/reviews/code_review_bug_bundle_2_2026-06-01.md
related_test: null
priority: P2
---

# Investigation: Silent Fallback When ConnectivityManager Unavailable

## Symptom

Sync operations proceed when offline because `isNetworkAvailable()` returns `true` when `connectivityManager` is null.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SyncQueueManager.kt:234-241`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/sync/SyncQueueManager.kt`

```kotlin
private fun isNetworkAvailable(): Boolean {
    val cm = connectivityManager ?: return true // Assume available if no manager provided
    ...
}
```

## Root Cause

Returns `true` when no connectivity manager is available. This causes sync operations to proceed offline, leading to unnecessary failures and poor user experience.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: Sync failures without context
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility when connectivity manager is unavailable
- Sync failures without clear root cause

### Proposed Instrumentation
- Add debug log when `connectivityManager` is null
- Add remote log: `connectivity_manager_unavailable`