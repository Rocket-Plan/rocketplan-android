---
bug_id: RP-BUG-011
aliases: []
title: Certificate Pinning Not Implemented
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: planned
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_007_011_network_2026-06-01.md
last_updated: 2026-06-01
related_review: null
related_test: null
priority: P2
---

# Investigation: Certificate Pinning Not Implemented

## Symptom

MITM attacks possible on untrusted networks.

## Discovery

- **Reported by:** Code review
- **Evidence:** `RetrofitClient.kt:111-118`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/api/RetrofitClient.kt`

```kotlin
// TODO: Add real certificate pins before production release.
```

## Root Cause

Certificate pinning is commented out with a TODO. MITM attacks possible on untrusted networks.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No detection of MITM attacks
- Security vulnerability unmonitored

### Proposed Instrumentation
- Implement certificate pinning before production
- Add logging for pinning failures