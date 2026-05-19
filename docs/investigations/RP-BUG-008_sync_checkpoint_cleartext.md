---
bug_id: RP-BUG-008
aliases: []
title: Sync Checkpoints Stored in Cleartext SharedPreferences
type: functional
classification: pre_existing_latent
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

# Investigation: Sync Checkpoints Stored in Cleartext SharedPreferences

## Symptom

Sync checkpoint data can be manipulated to cause data loss or duplicate sync operations.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SyncCheckpointStore.kt:8`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/storage/SyncCheckpointStore.kt`

```kotlin
private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
```

## Root Cause

Uses plain `SharedPreferences` for sync checkpoint data. Checkpoints track what data has been synced - manipulation could cause data loss or duplicate sync operations.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into checkpoint manipulation
- No detection of sync anomalies

### Proposed Instrumentation
- Encrypt checkpoint data using EncryptedSharedPreferences
- Add checksum validation for checkpoints