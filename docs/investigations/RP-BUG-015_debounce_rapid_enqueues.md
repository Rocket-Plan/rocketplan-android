---
bug_id: RP-BUG-015
aliases: []
title: Debounce May Not Prevent Rapid Re-enqueues
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: open
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P2
---

# Investigation: Debounce May Not Prevent Rapid Re-enqueues

## Symptom

Rapid changes within 750ms debounce window could still cause multiple enqueues.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SyncQueueManager.kt:996-1004`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/sync/SyncQueueManager.kt`

```kotlin
private suspend fun observePendingOperations() {
    localDataService.observeSyncOperations(SyncStatus.PENDING)
        .debounce(750)  // 750ms debounce
        .collect { ops ->
```

## Root Cause

While there IS a 750ms debounce, the `debounce` operator waits for silence before emitting. Rapid changes within 750ms could still cause multiple enqueues.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: Sync operation count spikes
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into debounce effectiveness
- Multiple enqueues not tracked

### Proposed Instrumentation
- Track enqueue frequency
- Consider `collectLatest` or reducing debounce to 500ms