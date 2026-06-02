---
bug_id: RP-BUG-016
aliases: []
title: Missing Migration 27_28 in Database Version Sequence
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
related_plan: docs/plans/plan_rp_bug_014_016_migration_2026-06-01.md
related_review: null
related_test: null
priority: P1
last_updated: 2026-06-01
---

# Investigation: Missing Migration 27_28 in Database Version Sequence

## Symptom

Database migration from version 27 to 28 is undefined, causing potential migration failure.

## Discovery

- **Reported by:** Code review
- **Evidence:** `OfflineDatabase.kt:481-535, 544`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt`

Migrations defined: 10_11, 11_12, 12_13, 13_14, 14_15, 15_16, 16_17, 17_18, 18_19, 19_20, 20_21, 21_22, 22_23, 23_24, 24_25, 25_26, 26_27

But database version is 28. Missing migration 27_28.

## Root Cause

Migration gap - 27_28 is not defined but version 28 is used.

## Observability

### Current Signals
- Local console logs: Migration failure
- Remote logs: None
- Sentry: Would capture migration crashes
- Existing metrics/watchdogs: None

### Gaps
- No visibility until migration actually fails

### Proposed Instrumentation
- Add migration 27_28
- Add logging for migration success/failure