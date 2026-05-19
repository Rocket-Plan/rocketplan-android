---
bug_id: RP-BUG-012
aliases: []
title: Untracked Coroutine Scope in Application Startup
type: threading
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

# Investigation: Untracked Coroutine Scope in Application Startup

## Symptom

Image processor startup tasks may fail silently with no visibility.

## Discovery

- **Reported by:** Code review
- **Evidence:** `RocketPlanApplication.kt:307-314`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/RocketPlanApplication.kt`

```kotlin
CoroutineScope(Dispatchers.IO).launch {
    imageProcessorQueueManager.recoverStrandedAssemblies()
    imageProcessorQueueManager.processNextQueuedAssembly()
```

## Root Cause

Creates coroutine scope without lifecycle management. If `recoverStrandedAssemblies()` throws, error is only logged via `runCatching`. No way to track completion or failure.

## Observability

### Current Signals
- Local console logs: Exception in runCatching
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into startup task failures
- No way to track completion status

### Proposed Instrumentation
- Use structured concurrency with tracked tasks
- Add remote log for startup task failures