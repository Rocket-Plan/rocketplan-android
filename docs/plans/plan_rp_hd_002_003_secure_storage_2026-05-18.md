**Bug ID(s):** RP-HD-002, RP-HD-003
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation RP-HD-002](../investigations/RP-HD-002_secure_storage_migration_exception_guard.md) · [Investigation RP-HD-003](../investigations/RP-HD-003_secure_storage_clear_save_ordering.md) · [Plan](./plan_rp_hd_002_003_secure_storage_2026-05-18.md) · [Review](../reviews/code_review_RP-HD-002_003_2026-05-18.md)

---

# Fix Plan: SecureStorage Hardening (RP-HD-002, RP-HD-003)

**Bug ID(s):** RP-HD-002, RP-HD-003
**Author:** jeremie@rocketplantech.com
**Date:** 2026-05-18
**State:** draft

---

## Summary

Two preventive hardenings against the migration path introduced for RP-BUG-006. Both touch the same file (`SecureStorage.kt`) — bundling avoids two PRs on the same surface.

1. **RP-HD-002** — wrap `migrateLegacyAuthToken()` in a Throwable catch so non-cancellation failures (encrypted-prefs writes, DataStore IO, Keystore) degrade the cold-start auth read to `null` instead of throwing through `getAuthTokenSync()`. Capture the failure to Sentry with tag `event=auth_migration_failed` so the silence is broken.
2. **RP-HD-003** — add a block comment above the three-step migration body explaining why the order `save → clear → publish` is load-bearing for crash safety. Pure documentation.

Both are P2/P3 hardenings; neither has an observed failure today. Land together in a single PR.

## Order of work

Do **RP-HD-003 first** (comment-only, no risk) inside the same diff. Then **RP-HD-002**. This keeps the catch block adjacent to the documented invariant so a future reader sees both at once.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt` | Add catch block in `migrateLegacyAuthToken()`; add ordering comment above the three calls. |
| `app/src/test/java/com/example/rocketplan_android/data/storage/SecureStorageTest.kt` | Add 2 new tests: migration body throws → `getAuthTokenSync()` returns null and Sentry is captured; encrypted-save throws → same. |

No build.gradle or other-file changes.

## Implementation Notes

### Step 1 — RP-HD-003: ordering comment

Inside `migrateLegacyAuthToken()`, immediately above the three operations:

```kotlin
// Order matters for crash safety:
//   1. saveAuthTokenInternal — encrypted prefs holds the token first.
//   2. clearLegacyToken — DataStore copy is removed only after the
//      authoritative store accepted it. Reversing this loses the token
//      if step (2) crashes mid-flight.
//   3. compareAndSet — publish to in-memory state last so flow consumers
//      can't observe a token that isn't yet persisted.
saveAuthTokenInternal(legacyToken)
clearLegacyToken()
authTokenState.compareAndSet(null, legacyToken)
```

No behavior change.

### Step 2 — RP-HD-002: catch + Sentry tag

Wrap the body so non-cancellation throws return `null`:

```kotlin
private suspend fun migrateLegacyAuthToken(): String? {
    Log.d(TAG, "legacy auth token migration: start")
    return try {
        val legacyToken = readLegacyToken() ?: run {
            Log.d(TAG, "legacy auth token migration: no legacy token")
            return null
        }
        // Order matters for crash safety: see comment block.
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
- `CancellationException` is re-thrown so structured concurrency is preserved. Without that re-throw, cancelling `migrationDeferred` would be misclassified as a migration failure.
- Sentry call shape matches `OfflineDatabase.getInstance` (introduced by RP-BUG-001) and `MainActivity.kt:589` / `ProjectsFragment.kt:312`. The trailing-lambda configurator form (`Sentry.captureException(e) { scope -> … }`) is **not** used in this codebase.
- `import io.sentry.Sentry` already exists in `OfflineDatabase.kt`; add the same import here.
- The catch block is unreachable in current unit tests (existing 4 pass through the happy path / timeout). New tests below exercise it.
- The full inline comment from Step 1 stays above the three calls inside the `try`; the body comment is the readable one. The redundant comment inside the try ("see comment block") is intentional — keeps the reasoning attached to the calls even after future edits.

### Why we do not change `getAuthTokenSync()` itself

`getAuthTokenSync()` already has the correct shape:

```kotlin
authTokenState.value?.let { return it }
migrationDeferred.await()
return authTokenState.value
```

If `migrationDeferred` resolves to `null` (the new failure path), `authTokenState.value` is whatever encrypted prefs loaded at construction — typically `null`, which is the existing "not signed in" signal the call sites already handle. No caller changes required.

## Test Plan

- [ ] Unit test: `migrateLegacyAuthToken throws — getAuthTokenSync returns null and reports to Sentry`.
  - Stub `legacyTokenReader = { error("boom") }`.
  - Mock `Sentry.captureException` via `mockkStatic(Sentry::class)`.
  - Assert `getAuthTokenSync()` returns `null`.
  - Verify `Sentry.captureException` called exactly once with a `Throwable` whose message contains `"boom"`.
  - Verify the scope was tagged with `event=auth_migration_failed`.
- [ ] Unit test: `saveAuthTokenInternal throws — getAuthTokenSync returns null`.
  - Make `encryptedEditor.commit()` / `putString` throw a `SecurityException`.
  - Same assertion shape: `null` return, one Sentry capture.
- [ ] Existing 4 `SecureStorageTest` cases continue to pass (`./gradlew testDevStandardDebugUnitTest --tests SecureStorageTest`).
- [ ] Manual QA on tablet `30407ef` (`devStandardDebug`):
  1. Install build, sign in, force a cold start.
  2. Confirm no behavior regression vs the current build — same sign-in flow, no spurious sign-outs.
  3. (Optional dev-only repro) temporarily make `migrateLegacyAuthToken()` throw via debugger; verify the app continues to a re-auth flow and Sentry receives one event with the `auth_migration_failed` tag.

## Rollback Plan

Both changes are localized to `SecureStorage.kt`:

- RP-HD-002: revert the `try`/`catch` wrapping. The pre-patch body throws on failure exactly as it does today.
- RP-HD-003: revert the comment block. No behavior change.

Either revert is safe at any point — no persisted state or build flag depends on these changes.

## Dependencies

- Requires: none (no server change, no build-flag change, no new dependencies).
- Blocking: none. The fixes are net additions on top of the RP-BUG-001/006 bundle, which is already in the working tree.

## Changelog Entry

```markdown
## [1.0.XX] - 2026-05-XX

### Hardening
- [RP-HD-002] Auth-token migration failures are now reported to Sentry (`event=auth_migration_failed`) and degrade to a null token instead of throwing through `getAuthTokenSync()`.
- [RP-HD-003] Documented the save → clear → publish ordering invariant in the legacy auth-token migration.
```

## Observability changes

This plan adds one Sentry event tag — `event=auth_migration_failed` — symmetric with the `event=missing_room_migration` tag from RP-BUG-001. Both share the `Sentry.withScope { setTag … captureException }` shape so they show up filterable in Sentry by tag.

No new metrics, no new dashboards, no new logcat categories. The existing `SecureStorage` tag continues to host the start/migrated/no-legacy debug lines.
