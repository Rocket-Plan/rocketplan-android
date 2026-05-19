---
bug_id: RP-BUG-002
aliases: []
title: printStackTrace() Leaks Sensitive Data in Production
type: functional
classification: new_code_bug
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
priority: P0
last_updated: 2026-05-13
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