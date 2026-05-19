---
bug_id: RP-BUG-010
aliases: []
title: Unbounded Photo Sync Job Blocking Other Operations
type: hang
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

# Investigation: Unbounded Photo Sync Job Blocking Other Operations

## Symptom

Sync operations block indefinitely waiting for photo sync job to complete.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SyncQueueManager.kt:937-938`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/sync/SyncQueueManager.kt`

```kotlin
syncJob.start()
// Wait for completion
syncJob.join()
```

## Root Cause

`syncJob.join()` blocks the entire `processLoop()` coroutine until the project sync completes. If many projects are queued, this blocks all other sync operations including critical pending operations.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: Sync stalls reported by users
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into blocking duration
- Sync stalls not well characterized

### Proposed Instrumentation
- Add debug logs for join() duration
- Track sync operation queue depth
- Add timeout for join() with metrics