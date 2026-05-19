---
bug_id: RP-BUG-001
aliases: []
title: Destructive Migration Enabled in Production
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_critical_p0_001_006_2026-05-13.md
related_review: null
related_test: null
priority: P0
last_updated: 2026-05-13
---

# Investigation: Destructive Migration Enabled in Production

## Symptom

Offline data is silently wiped when schema migration fails in production builds.

## Discovery

- **Reported by:** Code review
- **Evidence:** `OfflineDatabase.kt:546-549`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt`

```kotlin
.apply {
    if (BuildConfig.DEBUG) {
        fallbackToDestructiveMigration()
    }
}
```

## Root Cause

The destructive migration is gated behind `BuildConfig.DEBUG` but:
1. Release builds may ship with DEBUG=true
2. The guard could be disabled by build config manipulation
3. No user confirmation is shown before data wipe

## Observability

### Current Signals
- Local console logs: None when migration fails
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into destructive migration events
- No user notification when data is wiped

### Proposed Instrumentation
- Add remote log: `destructive_migration_triggered`
- Show user confirmation dialog before destructive migration
- Track in analytics when offline data is lost