**Bug ID(s):** RP-BUG-005
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation RP-BUG-005](../investigations/RP-BUG-005_auth_token_apply.md) · [RP-BUG-006 auth-token race](../investigations/RP-BUG-006_auth_token_race.md) · [Plan](./plan_rp_bug_005_auth_token_apply_2026-06-01.md) · [SecureStorage hardening plan (RP-HD-002/003)](./plan_rp_hd_002_003_secure_storage_2026-05-18.md) · Review: TBD

# Fix Plan: Non-Blocking Write (apply) to EncryptedSharedPreferences for Auth Token (RP-BUG-005)

**Bug ID(s):** RP-BUG-005
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

`saveAuthTokenInternal()` persists the auth token to `EncryptedSharedPreferences` with `.apply()`, which schedules the write asynchronously and returns immediately. If the process is killed after `.apply()` returns but before the disk write completes, the token is lost and the user is silently signed out on the next cold start.

The fix is to make the authoritative write durable with `.commit()` (synchronous, returns a success boolean) for the auth-token path, and to surface a failed commit to Sentry so a silent loss becomes visible. The same single-key durability concern applies to the encrypted-prefs writes that back `saveAuthTokenInternal()` only; the other encrypted-prefs writes (`saved_password`, `oauth_state`) are non-authoritative and can stay on `.apply()`.

This is the natural completion of the RP-BUG-006 / RP-HD-002 work. RP-BUG-006 fixed the *read* race (a `Deferred` migration that `getAuthTokenSync()` awaits) and RP-HD-002 made the migration body fail soft to `null` and report to Sentry. RP-BUG-005 closes the remaining *write* durability gap on the same `saveAuthTokenInternal()` call those fixes rely on.

## Why this is its own plan (not bundled with RP-BUG-008)

RP-BUG-008 (`SyncCheckpointStore` cleartext) is thematically in the same "secure storage" cluster but is a different file (`SyncCheckpointStore.kt`), a different failure mode (confidentiality / tamper-resistance, not write durability), and shares no code path with the auth-token write. Bundling would put two unrelated diffs on one PR. They are tracked as two plans.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt` | Change `saveAuthTokenInternal()` from `.apply()` to `.commit()`; capture a failed commit to Sentry with tag `event=auth_token_commit_failed`. No signature change (stays a private `fun`). |
| `app/src/test/java/com/example/rocketplan_android/data/storage/SecureStorageTest.kt` | Add tests: verify `saveAuthTokenInternal()` uses `commit()` via a fake/spied editor seam; a failed commit reports to Sentry. Optional integration coverage can still read the token back after `saveAuthToken`, but that is supporting evidence rather than the primary proof. |

No `build.gradle`, no public-API, no other-file changes.

## Implementation Notes

### Step 1 — switch the authoritative write to `commit()`

Current (`SecureStorage.kt:349-354`):

```kotlin
/**
 * Persist token in encrypted prefs and migrate away from legacy DataStore storage.
 */
private fun saveAuthTokenInternal(token: String) {
    encryptedPrefs.edit().putString(AUTH_TOKEN_PREF_KEY, token).apply()
}
```

After:

```kotlin
/**
 * Persist token in encrypted prefs synchronously.
 *
 * Uses commit() (not apply()) so the write is durable before we return:
 * apply() schedules the write off-thread, so a process kill between
 * saveAuthToken() returning and the write flushing would silently lose the
 * token and sign the user out on next cold start (RP-BUG-005). saveAuthToken()
 * and migrateLegacyAuthToken() already run on Dispatchers.IO, so the
 * synchronous disk write does not block the main thread.
 */
private fun saveAuthTokenInternal(token: String) {
    val committed = encryptedPrefs.edit().putString(AUTH_TOKEN_PREF_KEY, token).commit()
    if (!committed) {
        Sentry.withScope { scope ->
            scope.setTag("event", "auth_token_commit_failed")
            Sentry.captureMessage("Auth token commit() returned false")
        }
        Log.w(TAG, "auth token commit returned false")
    }
}
```

Notes:
- `commit()` returns `Boolean`; `apply()` returns `Unit`. `false` means the in-memory update was applied but the disk write failed — the only signal SharedPreferences gives us, so we surface it.
- The `Sentry.withScope { setTag … }` shape is identical to `migrateLegacyAuthToken()`'s `event=auth_migration_failed` (added in RP-HD-002) and to RP-BUG-001's `missing_room_migration`. The trailing-lambda configurator form is intentionally not used (matches the rest of the codebase). `import io.sentry.Sentry` and `import android.util.Log` already exist in this file.
- We use `captureMessage` (not `captureException`) because a `false` commit has no throwable. If `commit()` itself throws (e.g. `SecurityException` from the Keystore), that throwable already propagates into the existing RP-HD-002 catch in `migrateLegacyAuthToken()` for the cold-start path, and into `saveAuthToken()`'s `Dispatchers.IO` block for the live-login path.

### Step 2 — confirm the two call sites are off the main thread

`saveAuthTokenInternal()` has exactly two callers, both already on `Dispatchers.IO`, so the synchronous `commit()` does not introduce a main-thread disk write:

- `saveAuthToken()` (`SecureStorage.kt:128-133`) wraps the call in `withContext(Dispatchers.IO)`.
- `migrateLegacyAuthToken()` (`SecureStorage.kt:356-385`) runs inside the `scope.async { … }` migration deferred, whose `scope` defaults to `Dispatchers.IO`.

No caller changes required.

### Why we do not touch the other `.apply()` writes

`saveEncryptedPassword()`, `saveOAuthState()`, `clearOAuthState()`, `clearEncryptedPassword()`, `clearAuthToken()`, and `clearAll()` keep `.apply()`:

- `saved_password` / `oauth_state` are recoverable (re-prompt / re-issue) — losing a deferred write does not sign anyone out.
- `clearAuthToken()` / `clearAll()` removing the token with `.apply()` is the *safe* direction: a lost clear leaves the token present, which the sign-out flow already tolerates (the in-memory `authTokenState` is set to `null` regardless). Making the clear synchronous is out of scope and has the opposite risk profile.

Only the authoritative *save* needs durability. This keeps the diff minimal and matches the RP-HD-003 "persist authoritative state first" reasoning.

## Test Plan

- [ ] Unit test: verify `saveAuthTokenInternal()` uses `commit()` and not `apply()` via a fake/spied `SharedPreferences.Editor` seam. Immediate in-process readback alone is not sufficient proof because `apply()` updates the in-memory map synchronously too.
- [ ] Unit test: `commit failure reports to Sentry` — stub the encrypted editor so `commit()` returns `false`; `mockkStatic(Sentry::class)`; assert `Sentry.captureMessage` is called once and the scope is tagged `event=auth_token_commit_failed`.
- [ ] Optional integration-style test: with a real temp-backed prefs instance and a controllable editor seam, confirm the stored value is present after `saveAuthToken()` returns. Treat this as supporting coverage, not the primary proof of `commit()` vs `apply()`.
- [ ] Existing `SecureStorageTest` cases continue to pass (`./gradlew testDevStandardDebugUnitTest --tests SecureStorageTest`). Run in the background per project convention.
- [ ] Manual QA on tablet `30407ef` (`devStandardDebug`):
  1. Sign in.
  2. Immediately force-stop the app (`adb -s 30407ef shell am force-stop com.rocketplantech.rocketplan`) right after login completes.
  3. Cold-start the app; expect the session to be preserved (no spurious re-login). On the pre-fix build this is the window where the token could be lost.
  4. Confirm normal sign-in / sign-out still works and no main-thread jank or ANR appears around login.

Note: the force-stop reproducer is probabilistic and should be treated as supporting QA evidence, not the primary proof of correctness. The editor-seam unit test is the authoritative distinction between `commit()` and `apply()`.

## Rollback Plan

Single-file, localized change. Revert `saveAuthTokenInternal()` to the `.apply()` body to restore prior behavior. No persisted state, schema, or build flag depends on this change, so revert is safe at any point. A token written by `commit()` is byte-identical to one written by `apply()`, so rolling back does not strand any stored value.

## Dependencies

- Requires: none (no server change, no new dependency, no build-flag change).
- Blocking: none. Builds on the RP-BUG-006 / RP-HD-002 migration changes already in the tree; no conflict with the RP-BUG-008 plan (different file).

## Changelog Entry

```markdown
## [1.0.XX] - 2026-06-XX

### Fixed
- [RP-BUG-005] Auth token is now written to encrypted storage synchronously (commit) so it can no longer be lost if the process is killed immediately after sign-in. A failed commit is reported to Sentry (`event=auth_token_commit_failed`).
```

## Observability changes

Adds one Sentry event tag — `event=auth_token_commit_failed` — symmetric with `event=auth_migration_failed` (RP-HD-002) and `missing_room_migration` (RP-BUG-001), all using the `Sentry.withScope { setTag … }` shape so they are filterable by tag. No new metrics, dashboards, or logcat categories; the existing `SecureStorage` log tag hosts the new `Log.w` line. Expected production count under normal operation: zero. Any non-zero count indicates a real device-side encrypted-storage write failure to investigate.
