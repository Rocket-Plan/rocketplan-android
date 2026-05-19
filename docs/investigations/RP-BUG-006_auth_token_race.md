---
bug_id: RP-BUG-006
aliases: []
title: Race Condition in Auth Token Migration on Startup
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

# Investigation: Race Condition in Auth Token Migration on Startup

## Symptom

`getAuthTokenSync()` may return null or stale token during the migration window.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SecureStorage.kt:76-85, 109-115`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt`

```kotlin
private val authTokenState = MutableStateFlow<String?>(
    encryptedPrefs.getString(AUTH_TOKEN_PREF_KEY, null)
)

init {
    scope.launch {
        migrateLegacyAuthToken()
    }
}

suspend fun getAuthTokenSync(): String? {
    val current = authTokenState.value  // Race with init block's migration!
    if (current != null) return current
    return migrateLegacyAuthToken()
}
```

## Root Cause

`init` block launches migration asynchronously. `getAuthTokenSync()` reads `authTokenState.value` without synchronization, racing with the migration.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: Auth failures during startup
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into race condition occurrences
- Auth failures may be unexplained

### Proposed Instrumentation
- Add debug logs for migration timing
- Add remote log: `auth_migration_race_detected`