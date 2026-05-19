---
bug_id: RP-BUG-007
aliases: []
title: Unshared OkHttpClient Configuration for Photo Cache
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

# Investigation: Unshared OkHttpClient Configuration for Photo Cache

## Symptom

Photo downloads may timeout unexpectedly or bypass security policies (cert pinning).

## Discovery

- **Reported by:** Code review
- **Evidence:** `PhotoCacheManager.kt:27-29`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt`

```kotlin
// TODO: Share OkHttpClient config (timeouts, cert pinning) with RetrofitClient
private val httpClient: OkHttpClient = OkHttpClient()
```

## Root Cause

Creates a new `OkHttpClient` without timeouts, cert pinning, or interceptors. Photo downloads may timeout unexpectedly or bypass security policies.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: Photo download failures
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into photo download security policy violations
- Inconsistent timeout behavior

### Proposed Instrumentation
- Share OkHttpClient from RetrofitClient
- Add metrics for photo download timeouts