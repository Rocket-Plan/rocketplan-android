---
bug_id: RP-BUG-005
aliases: []
title: Non-Blocking Write to EncryptedSharedPreferences for Auth Token
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_005_auth_token_apply_2026-06-01.md
related_review: docs/reviews/code_review_bug_bundle_2_2026-06-01.md
related_test: null
priority: P0
last_updated: 2026-06-01
---

# Investigation: Non-Blocking Write to EncryptedSharedPreferences for Auth Token

## Symptom

Auth token may be lost if process crashes after `apply()` but before write completes.

## Discovery

- **Reported by:** Code review
- **Evidence:** `SecureStorage.kt:313-314`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt`

```kotlin
private fun saveAuthTokenInternal(token: String) {
    encryptedPrefs.edit().putString(AUTH_TOKEN_PREF_KEY, token).apply()
}
```

## Root Cause

`.apply()` is asynchronous - returns immediately before write completes. If process crashes after `apply()` but before completion, token is lost and user is logged out.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: Users unexpectedly logged out
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into token save failures
- Users may be silently logged out

### Proposed Instrumentation
- Add remote log: `auth_token_save_deferred`
- Track unexpected logout events