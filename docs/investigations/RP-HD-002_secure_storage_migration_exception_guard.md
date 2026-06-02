---
bug_id: RP-HD-002
aliases: []
title: Guard SecureStorage migration body against non-cancellation exceptions
type: hardening
classification: new_code_bug
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_hd_002_003_secure_storage_2026-05-18.md
related_review: docs/reviews/code_review_RP-BUG-001_006_2026-05-18.md
related_test: null
priority: P2
last_updated: 2026-06-02
---

# Hardening: SecureStorage migration body should not throw into `getAuthTokenSync()`

## Context

Surfaced during the code review for [RP-BUG-001 / RP-BUG-006](../reviews/code_review_RP-BUG-001_006_2026-05-18.md) (finding **F1**). The fix for RP-BUG-006 introduced a `Deferred<String?>` migration that `getAuthTokenSync()` awaits.

`SecureStorage.kt`:

```kotlin
private val migrationDeferred: Deferred<String?> = scope.async {
    withTimeoutOrNull(MIGRATION_TIMEOUT_MS) { migrateLegacyAuthToken() }
}

suspend fun getAuthTokenSync(): String? {
    authTokenState.value?.let { return it }
    migrationDeferred.await()
    return authTokenState.value
}
```

`withTimeoutOrNull` only swallows `TimeoutCancellationException`. Any other throwable that escapes `migrateLegacyAuthToken()` — for example a `SecurityException` from `EncryptedSharedPreferences` write, a `DataStore` `IOException` while reading the legacy key, or a `Keystore` failure inside `saveAuthTokenInternal` — propagates into `await()` and out of `getAuthTokenSync()`.

## Why this is `RP-HD` and not `RP-BUG`

We have **no observed failure** today. The plan for RP-BUG-006 explicitly framed the user-visible failure as "spurious 401 / sign-out on cold start" — i.e. `getAuthTokenSync()` returns `null`. A migration body throw produces a *different* failure mode (crash inside the auth read path) that wasn't part of the original symptom and isn't currently reproducible. The guard is preventive.

If a Sentry event from a real device shows the throw path firing, promote to `RP-BUG-###` and link this doc.

## Rule alignment

Touches `RP-CD-004` (push handlers return `OperationOutcome`, never throw) by analogy — auth reads should also have a defined failure mode (`null` or a structured error) rather than letting infrastructure exceptions escape. This is not a clean rule violation today because `getAuthTokenSync()` predates the rule and is not a push handler; consider whether `RP-CD-014` should generalise the rule to "long-lived async boundaries surface `null`/`Result`, never raw exceptions" once we have a second example.

## Proposed fix

Wrap the migration body so non-cancellation throws degrade to `null`:

```kotlin
private suspend fun migrateLegacyAuthToken(): String? {
    Log.d(TAG, "legacy auth token migration: start")
    return try {
        val legacyToken = readLegacyToken() ?: run {
            Log.d(TAG, "legacy auth token migration: no legacy token")
            return null
        }
        saveAuthTokenInternal(legacyToken)
        clearLegacyToken()
        authTokenState.compareAndSet(null, legacyToken)
        Log.d(TAG, "legacy auth token migration: migrated")
        legacyToken
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Sentry.withScope { scope ->
            scope.setTag("event", "auth_migration_failed")
            Sentry.captureException(e)
        }
        Log.w(TAG, "legacy auth token migration: failed", e)
        null
    }
}
```

Notes:
- `CancellationException` must be re-thrown so structured concurrency is preserved.
- Sentry tag `auth_migration_failed` is symmetric with the `missing_room_migration` tag added in RP-BUG-001 — both go through `Sentry.withScope { setTag … captureException }`.
- On failure the cold-start auth read returns `null`. The caller's existing "no token → re-auth" path takes over. This is strictly better than crashing.

## Test plan

- [ ] Unit test: stub `legacyTokenReader` to throw `RuntimeException("boom")`. `getAuthTokenSync()` returns `null` (not throws). Sentry is captured exactly once (mockk verify).
- [ ] Unit test: stub `legacyTokenReader` so `saveAuthTokenInternal` throws. Same expectation.
- [ ] Existing 4 tests continue to pass.

## Observability

### Current Signals
- Local console logs: `legacy auth token migration: start | migrated | no legacy token` (debug-level, dev only)
- Remote logs: none today
- Sentry: none for migration body throws
- Existing metrics/watchdogs: none

### Gaps
- A migration-body throw is currently invisible in Sentry and surfaces only as a crashed cold-start auth read.
- We have no signal distinguishing "no legacy token" from "migration crashed" — both leave `authTokenState` unchanged.

### Proposed Instrumentation
- Local debug logs to add: `Log.w` on the catch branch with the throwable.
- Remote logs to add: Sentry breadcrumb on success/no-op; Sentry event with tag `event=auth_migration_failed` on the catch branch.
- Log category names: `SecureStorage`.
- Key fields: throwable class, message (already in stack trace).
- Sampling / throttling: none (one-shot per app lifetime — `migrationDeferred` is constructed once).
- Build/env gating: Sentry only fires when `BuildConfig.SENTRY_ENABLED` is true, matching existing pattern.

### Success Criteria
- QA: force a throw in dev (e.g. by corrupting the legacy DataStore), confirm a single Sentry event with the tag, confirm the app continues to a re-auth flow instead of crashing.
- Production: zero events under normal operation. Any non-zero count points at a real device-side encryption/storage failure to investigate.
