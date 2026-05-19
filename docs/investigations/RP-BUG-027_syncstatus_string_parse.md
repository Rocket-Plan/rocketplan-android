---
bug_id: RP-BUG-027
aliases: []
title: Room SyncStatus Enum Comparisons Use String Parsing
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
related_plan: docs/plans/plan_rp_bug_027_syncstatus_storage_mapping_2026-05-18.md
related_review: docs/reviews/code_review_rp_bug_024_027_2026-05-18.md
related_test: null
priority: P3
---

# Investigation: Room SyncStatus Enum Comparisons Use String Parsing

## Symptom

Silent failures could occur if database contains a SyncStatus value not defined in the enum.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SyncStatus.kt`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/SyncStatus.kt`

`SyncStatus.fromValue()` parses strings. If a new status value is added to database but enum doesn't have corresponding entry, silent failures could occur.

## Root Cause

Using string-based parsing for enum values instead of direct enum mapping. If schema evolves and new values are added to database but app code is not updated, parsing returns null or default value silently.

**Severity:** P3 - not currently causing issues but fragile during schema evolution.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility when unknown SyncStatus value encountered
- No fallback strategy

### Proposed Instrumentation
- Add logging when unknown SyncStatus value encountered
- Add analytics for unknown values

### Success Criteria
- Unknown values are logged and handled gracefully
