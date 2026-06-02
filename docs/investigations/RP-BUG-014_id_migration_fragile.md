---
bug_id: RP-BUG-014
aliases: []
title: Realm-like ID Pattern Fragile Migration Logic
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

# Investigation: Realm-like ID Pattern Fragile Migration Logic

## Symptom

Data may not migrate correctly if auto-generated local ID happens to match server ID.

## Discovery

- **Reported by:** Code review
- **Evidence:** `LocalDataService.kt:759-762`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/LocalDataService.kt`

```kotlin
if (localRoomId == serverId) {
    // Nothing to migrate if the auto PK already matches the server id
    return@forEach
}
```

## Root Cause

When auto-generated local ID happens to match server ID, migration is skipped. This works but is fragile - depends on ID collision which isn't guaranteed.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into migration skipping events
- Data integrity not verifiable

### Proposed Instrumentation
- Use a separate `needsMigration` flag or explicit mapping table
- Log migration decisions for audit