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
state: closed
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P3
last_updated: 2026-06-02
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

## Resolution — closed 2026-06-02

Closed as **not a defect**. Per the tracker's `RP-BUG` boundary rule, you cannot complete
*"the user will see X when Y happens"* with a concrete failure here — edit-person and
delete-person are simply unbuilt features, not broken behavior. Both TODOs were re-verified as
still present (`PeopleFragment.kt:29` edit, `PeopleFragment.kt:85` delete) but represent missing
functionality, which belongs in the product/feature backlog, not the bug tracker.

**Backlog follow-up (tracked outside this tracker):**
- Implement edit-person functionality in `PeopleFragment`.
- Implement delete-person via API in `PeopleFragment`.

No code change is required to close this bug; the work is reclassified, not abandoned.