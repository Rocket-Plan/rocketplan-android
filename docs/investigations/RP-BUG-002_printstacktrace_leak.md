---
bug_id: RP-BUG-002
aliases: []
title: printStackTrace() Leaks Sensitive Data in Production
type: functional
classification: new_code_bug
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_002_018_logging_pii_2026-06-01.md
related_review: docs/reviews/code_review_bug_bundle_2_2026-06-01.md
related_test: null
priority: P0
last_updated: 2026-06-01
---

# Investigation: printStackTrace() Leaks Sensitive Data in Production

## Symptom

Stack traces with potential tokens/credentials are written to System.err, bypassing Android logcat filtering.

## Discovery

- **Reported by:** Code review
- **Evidence:** `LoginViewModel.kt:178`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/ui/login/LoginViewModel.kt`

```kotlin
error?.printStackTrace()
```

## Root Cause

`printStackTrace()` writes to `System.err` which:
- Is not filtered by `Log.isLoggable()` level
- Can appear in crash reports shared by users
- Bypasses the app's logging conventions

## Observability

### Current Signals
- Local console logs: System.err output
- Remote logs: None
- Sentry: Not captured unless explicitly sent
- Existing metrics/watchdogs: None

### Gaps
- No structured error reporting for auth failures
- Stack traces don't reach remote logging

### Proposed Instrumentation
- Replace with `Log.e(TAG, message, error)`
- Add remote log for auth failures with error type (not full stack)