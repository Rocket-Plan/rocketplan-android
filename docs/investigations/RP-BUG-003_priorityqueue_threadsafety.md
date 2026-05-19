---
bug_id: RP-BUG-003
aliases: []
title: PriorityQueue Thread-Safety Violation in SyncQueueManager
type: crash
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
priority: P1
---

# Investigation: PriorityQueue Thread-Safety Violation in SyncQueueManager

## Symptom

App crashes with `ConcurrentModificationException` during sync operations.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SyncQueueManager.kt:56-58, 355, 464, 474, 954`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/sync/SyncQueueManager.kt`

```kotlin
private val queue = PriorityQueue<QueuedTask>(
    compareBy<QueuedTask> { it.priority }.thenBy { it.enqueuedAt }
)
```

## Root Cause

`PriorityQueue` is NOT thread-safe. Multiple coroutines access:
- `queue.poll()` at line 474
- `queue.add()` at line 355
- `queue.remove()` at lines 464 and 954

These access patterns are not protected by mutex, causing race conditions.

## Observability

### Current Signals
- Local console logs: ConcurrentModificationException stack trace
- Remote logs: None
- Sentry: Would capture crash if enabled
- Existing metrics/watchdogs: None

### Gaps
- No visibility into queue access patterns
- No observability into thread contention

### Proposed Instrumentation
- Add debug logs for queue operations
- Track queue size over time
- Add mutex protection verification