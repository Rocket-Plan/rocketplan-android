---
bug_id: RP-BUG-013
aliases: []
title: Swallowed Exception in ImageProcessorRetryWorker
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
priority: P1
---

# Investigation: Swallowed Exception in ImageProcessorRetryWorker

## Symptom

Infinite retry loops on app shutdown due to CancellationException being caught.

## Discovery

- **Reported by:** Code review
- **Evidence:** `ImageProcessorRetryWorker.kt:35-38`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/worker/ImageProcessorRetryWorker.kt`

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "❌ ImageProcessorRetryWorker failed", e)
    Result.retry()
}
```

## Root Cause

Catches `Exception` (includes `CancellationException`) and returns `retry()`. On app shutdown, this could cause infinite retry loops.

## Observability

### Current Signals
- Local console logs: Retry worker failures
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into retry loops
- App shutdown not properly handled

### Proposed Instrumentation
- Check `e is CancellationException` and re-throw
- Add metrics for retry counts