---
bug_id: RP-BUG-023
aliases: []
title: TODO Comments for Incomplete Features in PeopleFragment
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: open
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P3
---

# Investigation: TODO Comments for Incomplete Features in PeopleFragment

## Symptom

Incomplete features shipped to production.

## Discovery

- **Reported by:** Code review
- **Evidence:** `PeopleFragment.kt:29, 85`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/ui/people/PeopleFragment.kt`

```kotlin
// TODO: Implement edit person functionality
// TODO: Implement delete person via API
```

## Root Cause

TODO comments indicate incomplete features shipped to production.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No tracking of incomplete features
- User expectations vs reality gap

### Proposed Instrumentation
- Either implement or create tracked issues
- Document expected behavior vs actual