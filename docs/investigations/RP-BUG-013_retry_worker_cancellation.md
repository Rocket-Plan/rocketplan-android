---
bug_id: RP-BUG-013
aliases: []
title: Swallowed Exception in ImageProcessorRetryWorker
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
priority: P3
last_updated: 2026-06-03
---

# Investigation: Swallowed Exception in ImageProcessorRetryWorker

## Symptom

Swallows coroutine cancellation (structured-concurrency violation): the broad
`catch (e: Exception)` block catches `CancellationException` and converts it into
`Result.retry()`, so a worker being stopped/cancelled (e.g. on app shutdown) is
treated as a retryable failure rather than letting cancellation propagate.

**No observed incident.** This is a latent code-review finding, not a reproduced
bug — the worker has no remote/Sentry logging (only ephemeral logcat), so there is
no evidence it ever manifested in the field. The worst *realistic* impact is
throttled retries, not the "infinite retry loop" originally claimed: the worker is
registered as a `PeriodicWorkRequest` with the default exponential backoff
(`RocketPlanApplication.kt:300`), so even if `Result.retry()` is observed by
WorkManager, retries are rate-limited rather than tight-looping. Severity
downgraded P1 → P3 to reflect this (2026-06-03).

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

Catches `Exception` (which includes `CancellationException`) and returns `retry()`. Because cancellation is swallowed instead of propagating, a stopped/cancelled worker can be reported to WorkManager as a retryable failure. Default exponential backoff bounds the practical impact to throttled retries (not a tight loop).

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