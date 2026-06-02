---
bug_id: RP-BUG-020
aliases: []
title: Error Handling Swallows Failures in ImageProcessorQueueManager
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_013_017_020_image_queue_2026-06-01.md
related_review: docs/reviews/code_review_bug_bundle_2_2026-06-01.md
related_test: null
priority: P2
last_updated: 2026-06-01
---

# Investigation: Error Handling Swallows Failures in ImageProcessorQueueManager

## Symptom

Assembly could be stuck in limbo when queue processing fails.

## Discovery

- **Reported by:** Code review
- **Evidence:** `ImageProcessorQueueManager.kt:708-717`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/queue/ImageProcessorQueueManager.kt`

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "❌ Error processing queue", e)
    isProcessingQueue.set(false)
    // Only logs - doesn't update assembly status or trigger recovery
}
```

## Root Cause

Generic `Exception` caught, assembly status not updated, no retry scheduled, no user notification. Assembly could be stuck in limbo.

## Observability

### Current Signals
- Local console logs: Error logged
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into assemblies stuck in limbo
- No recovery triggered

### Proposed Instrumentation
- Update assembly status to RETRYING
- Schedule retry with exponential backoff
- Track stuck assemblies