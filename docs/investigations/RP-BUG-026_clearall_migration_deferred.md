---
bug_id: RP-BUG-026
aliases: []
title: SecureStorage.clearAll() Doesn't Clear migrationDeferred
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
related_plan: docs/plans/plan_rp_bug_026_clearall_migration_deferred_2026-05-18.md
related_review: docs/reviews/code_review_rp_bug_024_027_2026-05-18.md
related_test: null
priority: P2
---

# Investigation: SecureStorage.clearAll() Doesn't Clear migrationDeferred

## Symptom

After logout and rapid re-login, old migration task could overwrite new auth token.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SecureStorage.kt:310-325`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt`

```kotlin
suspend fun clearAll() {
    context.dataStore.edit { preferences -> ... }
    encryptedPrefs.edit().clear().apply()
    authTokenState.value = null
}
```

After logout, `clearAll()` is called but `migrationDeferred` (created in init) still holds reference to the old migration task. If user logs in again quickly, old migration could complete AFTER new login, potentially overwriting new token.

## Root Cause

The `migrationDeferred` is created in `init` and references the old migration coroutine. When `clearAll()` is called on logout, the deferred is not reset. If re-login happens quickly, `saveAuthToken()` may be called, then the old migration completes and `compareAndSet(null, legacyToken)` could overwrite the new token.

**Mitigation:** `compareAndSet(null, legacyToken)` at line 345 only sets if currently null, so a concurrent `saveAuthToken()` that set a non-null value would "win". This mitigates the risk.

**Risk:** Low

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into migrationDeferred state after logout
- No protection against old migration overwriting new token

### Proposed Instrumentation
- Add debug log when old migration completes after clearAll()
- Track migrationDeferred lifecycle

### Success Criteria
- No token corruption after rapid logout/login cycle
