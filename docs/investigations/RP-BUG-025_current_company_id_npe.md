---
bug_id: RP-BUG-025
aliases: []
title: LocalDataService.currentCompanyId Throws If Accessed Before Login
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_025_current_company_id_2026-05-18.md
related_review: docs/reviews/code_review_rp_bug_024_027_2026-05-18.md
related_test: null
priority: P2
---

# Investigation: LocalDataService.currentCompanyId Throws If Accessed Before Login

## Symptom

App could crash if sync operations are triggered before user login completes.

## Discovery

- **Reported by:** Code review
- **Evidence:** `LocalDataService.kt:81-83`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/LocalDataService.kt`

```kotlin
val currentCompanyId: Long
    get() = _currentCompanyId
        ?: throw IllegalStateException("currentCompanyId not set...")
```

Callers like `SyncQueueManager.processPendingOperations()` use `localDataService.currentCompanyId` without catching the exception. If called before login completes, this crashes.

## Root Cause

The property throws if `_currentCompanyId` is null. If sync operations are triggered before login completes, the app crashes instead of gracefully handling the missing company context.

Mitigated if `ProcessPendingOperations` doesn't actually need companyId for push operations - but the pattern is fragile.

## Observability

### Current Signals
- Local console logs: IllegalStateException stack trace
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No graceful handling when companyId not set
- Crash instead of early return with reason

### Proposed Instrumentation
- Return null or wrapped result instead of throwing
- Log warning when companyId accessed before login

### Success Criteria
- App doesn't crash when accessed before login
- Graceful handling with proper error message
