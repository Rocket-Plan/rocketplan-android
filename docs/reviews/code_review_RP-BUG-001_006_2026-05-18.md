**Bug ID:** RP-BUG-001, RP-BUG-006
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation RP-BUG-001](../investigations/RP-BUG-001_destructive_migration.md) · [Investigation RP-BUG-006](../investigations/RP-BUG-006_auth_token_race.md) · [Plan](../plans/plan_critical_p0_001_006_2026-05-13.md)

---

# Code Review — Critical P0 Bundle (RP-BUG-001, RP-BUG-006)

**Reviewer:** Claude (Opus 4.7)
**Date:** 2026-05-18 11:36:54
**Branch:** master
**Commit base:** `88d8fa29` (uncommitted changes on top)
**Uncommitted at review start:**
```
M AGENTS.md
M CLAUDE.md
M app/build.gradle.kts
M app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt
M app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt
?? app/src/test/java/com/example/rocketplan_android/data/storage/SecureStorageTest.kt
?? app/src/test/resources/
```
**Scope:** the working-tree changes that implement [plan_critical_p0_001_006_2026-05-13.md](../plans/plan_critical_p0_001_006_2026-05-13.md). Excludes the docs reorganization and CLAUDE.md/AGENTS.md edits in the same working tree.
**State:** final

## Summary

Implementation is faithful to the plan. Both fixes are in, the new `SecureStorageTest` covers the four critical race conditions (migration success, no-legacy path, save-vs-migrate race, timeout), and the `BuildConfig.ALLOW_DESTRUCTIVE_MIGRATION` flag is wired correctly across the three environment flavors.

## Verification

| Plan item | Status | Notes |
|---|---|---|
| `SecureStorage.init { launch }` → `Deferred<String?>` via `scope.async` | ✅ | `SecureStorage.kt:107-111` |
| `withTimeoutOrNull(MIGRATION_TIMEOUT_MS)` wrapping migration | ✅ | `MIGRATION_TIMEOUT_MS = 5_000L` matches plan |
| `getAuthTokenSync()` fast-path → `migrationDeferred.await()` → return state | ✅ | `SecureStorage.kt:128-131` |
| `compareAndSet(null, legacyToken)` guard against save-vs-migrate race | ✅ | `SecureStorage.kt:345` |
| Debug `Log.d` start / end / no-legacy | ✅ | All three branches log |
| `buildConfigField ALLOW_DESTRUCTIVE_MIGRATION` on **env** dimension | ✅ | `dev=true`, `staging=false`, `prod=false`; not set on device dimension |
| `BuildConfig.DEBUG` guard replaced in `OfflineDatabase` | ✅ | `OfflineDatabase.kt:554` |
| `getInstance` wraps `IllegalStateException` → Sentry + rethrow with `event=missing_room_migration` tag | ✅ | SDK shape matches `MainActivity.kt:589` / `ProjectsFragment.kt:312` precedent |
| Unit test: legacy migration success | ✅ | `legacy token`, no encrypted token |
| Unit test: no legacy + no encrypted token | ✅ | Returns null |
| Unit test: save-vs-migrate race (`compareAndSet` guard) | ✅ | Fresh `saveAuthToken` during pending migration is preserved |
| Unit test (bonus): timeout doesn't hang `getAuthTokenSync()` | ✅ | `awaitCancellation` + virtual-time advance |

## Deviations from plan (acceptable)

1. **`SecureStorage` constructor was widened.** The class now has an `internal constructor(context, scope, legacyTokenReader, legacyTokenClearer)` plus the original `constructor(context)`. The plan did not specify dependency injection, but this is what makes the unit tests deterministic without touching Android Keystore. Public API for production callers is unchanged. **No action.**
2. **`MIGRATION_TIMEOUT_MS` log on timeout is silent.** When `withTimeoutOrNull` returns null, there is no log line; only the start log fires. The plan didn't require this, but if the auth read path ever stalls in the wild, the absence of a "migration timed out" line means we'll have to infer it from the missing end log. **Optional follow-up** — log a `Log.w(TAG, "legacy auth token migration: timed out after $MIGRATION_TIMEOUT_MS ms")` when the async returns null.

## Findings

### F1 (minor) — Migration exception path is unguarded

`migrationDeferred.await()` in `getAuthTokenSync()` will rethrow any non-Cancellation exception that escapes `migrateLegacyAuthToken()` (e.g. `EncryptedSharedPreferences` failure inside `saveAuthTokenInternal`). Today the only call sites for `getAuthTokenSync()` (`AuthRepository.getAuthToken()` and friends) treat `null` as "not signed in"; an unexpected exception bubbling up would surface as a different failure mode than the plan envisaged ("spurious sign-out" vs "crash on first auth read").

**Severity:** minor. The migration is wrapped in `withTimeoutOrNull` so a hang is contained, but the body throws are not.

**Suggested fix:** wrap the body in a `try`/`catch` returning `null` and log to Sentry with `event=auth_migration_failed`. Out of scope for this PR; file as `RP-HD-###` if you want to chase it later.

### F2 (minor) — `legacyTokenClearer` runs even if the encrypted save fails

In `migrateLegacyAuthToken()`:

```kotlin
saveAuthTokenInternal(legacyToken)
clearLegacyToken()
authTokenState.compareAndSet(null, legacyToken)
```

If `saveAuthTokenInternal` throws (encrypted-prefs write fails), `clearLegacyToken()` never runs — which is correct. But if `saveAuthTokenInternal` *succeeds writing to encrypted prefs* and then a later step throws before `compareAndSet`, the legacy token has been moved (cleared from DataStore, written to encrypted prefs) but the in-memory `authTokenState` is stale until the next process restart loads from `encryptedPrefs`. This is recoverable on next launch and not a real bug, but the ordering is worth a comment.

**Severity:** minor. **No required change.**

### F3 (informational) — Build matrix not yet spot-checked

The plan's test checklist includes a build-matrix verification (`stagingStandardDebug`, `prodStandardRelease`, `devStandardDebug`, `devFlirDebug`). Recommend running:

```bash
./gradlew assembleStagingStandardDebug assembleProdStandardRelease assembleDevStandardDebug assembleDevFlirDebug
```

…and grepping the generated `BuildConfig.java` for `ALLOW_DESTRUCTIVE_MIGRATION` in each variant's output. This is QA-side, not a code-review blocker.

## Risk Assessment

- **Runtime regression risk:** low. Both diffs are localized; the SecureStorage public API is unchanged, and the OfflineDatabase change is gated on a `BuildConfig` flag that defaults to the existing behavior in dev.
- **Operational follow-up after merge:** the **next** Room schema bump that lands in `staging`/`prod` without a registered migration will hard-crash on first launch. That is the *intended* behavior per the plan, but the next schema-bump PR must validate against a prod-shape DB before tagging.

## Recommendation

**Approve for merge** once:
1. The `SecureStorageTest` run reports green on a clean rerun (already passing on this machine).
2. The build-matrix spot-check in F3 is performed (5-minute check).

Findings F1 and F2 are not merge blockers; either fold them into a follow-up `RP-HD-###` or close as won't-do.
