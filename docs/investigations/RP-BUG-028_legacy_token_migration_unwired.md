---
bug_id: RP-BUG-028
aliases: []
title: Legacy auth-token migration never runs (dead migration wiring)
type: functional
classification: regression
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: RP-BUG-006
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: docs/reviews/code_review_bug_bundle_2_2026-06-01.md
related_test: app/src/test/java/com/example/rocketplan_android/data/storage/SecureStorageTest.kt
last_updated: 2026-06-01
---

# RP-BUG-028 — Legacy auth-token migration never runs

## Symptom

A user upgrading from a build that stored the auth token in the legacy DataStore
(`AUTH_TOKEN_KEY`) to a build using `EncryptedSharedPreferences` is **silently
signed out** on first launch. `getAuthTokenSync()` returns `null` even though a
valid token exists in the legacy store, because the migration that should copy it
into encrypted storage never executes.

## Root cause

In `SecureStorage` (introduced in commit `e261a77`, the RP-BUG-006 auth/migration
fix), the migration was refactored into:

- `migrateLegacyAuthToken()` — reads the legacy token, writes it to encrypted
  prefs, clears the legacy copy, publishes to in-memory state.
- `createMigrationDeferred(scope)` — wraps the above in a timeout-bounded `async`.
- `initMigrationDeferred(scope)` — assigns `migrationDeferred` under the mutex.

But **nothing ever called `initMigrationDeferred` or `createMigrationDeferred`** —
there is no `init {}` block, and `getInstance()` does not trigger it. Worse,
`getAuthTokenSync()`'s fallback substituted a no-op:

```kotlin
val deferred = migrationMutex.withLock { migrationDeferred }
    ?: scope.async { null }.also { migrationDeferred = it }   // never runs the migration
```

So `migrationDeferred` was always `null` at construction, the fallback produced a
deferred that resolves to `null`, and `migrateLegacyAuthToken()` was dead code.

## Fix

`getAuthTokenSync()` now lazily starts the real migration on first read (under the
mutex, so concurrent callers share one migration and `clearAll()` can still
cancel/replace it):

```kotlin
val deferred = migrationMutex.withLock {
    migrationDeferred ?: createMigrationDeferred(scope).also { migrationDeferred = it }
}
deferred.await()
```

The dead `initMigrationDeferred` was removed (its body is now inlined here). This
also closed a pre-existing race where the `?:` assignment happened outside the
mutex.

## Observability

### Current Signals
- Local console logs: `migrateLegacyAuthToken()` logs start / no-token / migrated / failed.
- Remote logs: none specific.
- Sentry: `auth_migration_failed` tag captured on migration exception.

### Gaps
- No signal distinguished "migration never started" from "no legacy token" —
  the dead wiring failed silently.

### Success Criteria
- QA: install a build with a legacy DataStore token, upgrade to a build with this
  fix, confirm the session is preserved (no re-login) and the token is present in
  encrypted prefs.
- Unit: `SecureStorageTest` "getAuthTokenSync returns migrated legacy token when
  no encrypted token exists" passes (it failed before the fix because the
  migration never ran).

## Tests

Covered by `SecureStorageTest` (migration + clearAll-cancellation cases now pass).
The flaky `File(null)` NPEs in that suite — caused by the relaxed `context` mock
returning a null `filesDir` for `context.dataStore` — were fixed by injecting a
per-test `TemporaryFolder`-backed `filesDir`.
