---
bug_id: RP-BUG-018
aliases: []
title: Session Object Printed in Debug Logging May Contain Sensitive Data
type: functional
classification: new_code_bug
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: fixed
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_002_018_logging_pii_2026-06-01.md
related_review: docs/reviews/code_review_bug_bundle_2_2026-06-01.md
related_test: null
priority: P2
last_updated: 2026-06-01
---

# Investigation: Session Object Printed in Debug Logging May Contain Sensitive Data

## Symptom

Session object with potential sensitive data logged via println.

## Discovery

- **Reported by:** Code review
- **Evidence:** `LoginViewModel.kt:167-169`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/ui/login/LoginViewModel.kt`

```kotlin
if (AppConfig.isLoggingEnabled) {
    println("Sign in successful")
    println("User ID: ${session?.user?.id}")
}
```

## Root Cause

`println` bypasses logcat and the session object may contain sensitive data depending on what `AuthSession` contains.

## Observability

### Current Signals
- Local console logs: Session data potentially printed
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into what session data is logged

### Proposed Instrumentation
- Use `android.util.Log.d()` with specific non-sensitive fields only
- Audit what AuthSession contains