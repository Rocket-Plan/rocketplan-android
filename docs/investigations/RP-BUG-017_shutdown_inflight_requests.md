---
bug_id: RP-BUG-017
aliases: []
title: ImageProcessorQueueManager Shutdown Doesn't Wait for In-Flight Requests
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

# Investigation: ImageProcessorQueueManager Shutdown Doesn't Wait for In-Flight Requests

## Symptom

Uploads in progress fail without graceful completion on shutdown.

## Discovery

- **Reported by:** Code review
- **Evidence:** `ImageProcessorQueueManager.kt:130-132`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/queue/ImageProcessorQueueManager.kt`

```kotlin
fun shutdown() {
    scope.cancel()
    okHttpClient.dispatcher.executorService.shutdown()
    okHttpClient.connectionPool.evictAll()
}
```

## Root Cause

`shutdown()` doesn't wait for in-flight requests to complete. Uploads in progress will fail without graceful completion.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: Upload failures on shutdown
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into graceful shutdown
- Failed uploads not tracked

### Proposed Instrumentation
- Use `shutdown()` followed by `awaitTermination()` with timeout
- Track in-flight requests before allowing shutdown