**Bug ID(s):** RP-BUG-008
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation RP-BUG-008](../investigations/RP-BUG-008_sync_checkpoint_cleartext.md) · [Plan](./plan_rp_bug_008_sync_checkpoint_cleartext_2026-06-01.md) · [SecureStorage hardening plan (RP-HD-002/003)](./plan_rp_hd_002_003_secure_storage_2026-05-18.md) · [RP-BUG-005 auth-token commit plan](./plan_rp_bug_005_auth_token_apply_2026-06-01.md) · Review: TBD

# Fix Plan: Sync Checkpoints Stored in Cleartext SharedPreferences (RP-BUG-008)

**Bug ID(s):** RP-BUG-008
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

`SyncCheckpointStore` persists incremental-sync checkpoints (the `updated_since` timestamps used to fetch only changed records) in a plain, world-readable-within-app `SharedPreferences` file (`sync_checkpoints`, `MODE_PRIVATE`, cleartext on disk). On a rooted or backed-up device these values can be inspected or tampered with. Manipulating a checkpoint forward skips records (silent data loss); manipulating it backward forces redundant full re-syncs (wasted bandwidth / battery).

The fix is to back the store with `EncryptedSharedPreferences` using the same `MasterKey` scheme already proven in `SecureStorage`, and to fail safe when the encrypted store cannot be opened (treat checkpoints as absent → next sync is a full sync, which is correct-but-slower rather than wrong). The public surface of `SyncCheckpointStore` (`getCheckpoint` / `updateCheckpoint` / `clearCheckpoint` / `clearAll`) stays identical, so the six call sites need no changes.

## Why this is its own plan (not bundled with RP-BUG-005)

Both bugs sit in the "secure storage" cluster, but RP-BUG-005 is an auth-token *write durability* fix inside `SecureStorage.kt`, whereas RP-BUG-008 is a *confidentiality / tamper-resistance* fix inside `SyncCheckpointStore.kt`. Different files, different failure modes, no shared code path. (Contrast with the RP-HD-002/003 bundle, which combined only because both edits landed in the same `SecureStorage.kt` body.) Two plans.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/storage/SyncCheckpointStore.kt` | Replace plain `getSharedPreferences(...)` with `EncryptedSharedPreferences.create(...)` behind a lazy, fail-safe initializer. Public methods unchanged. |
| `app/src/test/java/com/example/rocketplan_android/data/storage/SyncCheckpointStoreTest.kt` (new) | Round-trip get/update/clear; corrupt/unopenable store degrades to `null` checkpoints (full sync) without throwing. |

No `build.gradle` change — `androidx.security:security-crypto` is already a dependency (used by `SecureStorage`). No changes to the six callers (`RocketPlanApplication`, `OfflineSyncRepository`, `SyncEntityMappers`, `ProjectMetadataSyncService`, `PhotoSyncService`, `UpdatedRecordsSyncService`, `DeletedRecordsSyncService`, `ProjectSyncService`).

## Migration note (cleartext → encrypted)

Legacy cleartext prefs are deleted even if encrypted-prefs initialization later fails; that is intentional because checkpoints are disposable derived sync state and confidentiality is preferred over preserving stale cleartext values.

Checkpoints are **derived, disposable state** — they are re-established by the server on the next sync. We therefore do **not** migrate the old cleartext `sync_checkpoints` file. On first launch of the fixed build the encrypted store is empty, every `getCheckpoint` returns `null`, and the first sync per key runs as a full sync. This is the same behavior as a fresh install and is safe by design. We also delete the stale cleartext file so the old values don't linger on disk (defense-in-depth, since leaving them readable is the very thing we're fixing).

## Implementation Notes

### Step 1 — back the store with EncryptedSharedPreferences (fail-safe)

Current (`SyncCheckpointStore.kt`):

```kotlin
package com.example.rocketplan_android.data.storage

import android.content.Context
import java.util.Date

class SyncCheckpointStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCheckpoint(key: String): Date? {
        val millis = prefs.getLong(key, 0L)
        return if (millis == 0L) null else Date(millis)
    }

    fun updateCheckpoint(key: String, timestamp: Date = Date()) {
        prefs.edit().putLong(key, timestamp.time).apply()
    }

    fun clearCheckpoint(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        private const val PREFS_NAME = "sync_checkpoints"
    }
}
```

After:

```kotlin
package com.example.rocketplan_android.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.sentry.Sentry
import java.util.Date

class SyncCheckpointStore(private val context: Context) {

    // Lazy so a Keystore/crypto failure at app start doesn't crash construction.
    // Falls back to null: every getCheckpoint() then returns null and the next
    // sync runs as a (safe, slower) full sync rather than reading/writing
    // tamperable cleartext.
    private val prefs: SharedPreferences? by lazy { createEncryptedPrefs() }

    private fun createEncryptedPrefs(): SharedPreferences? {
        // Drop the legacy cleartext file so old, readable checkpoints don't
        // linger on disk (RP-BUG-008). Checkpoints are disposable derived
        // state, so we intentionally do not migrate their values.
        context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Throwable) {
            Sentry.withScope { scope ->
                scope.setTag("event", "sync_checkpoint_store_init_failed")
                Sentry.captureException(e)
            }
            Log.w(TAG, "encrypted checkpoint store unavailable; checkpoints disabled", e)
            null
        }
    }

    fun getCheckpoint(key: String): Date? {
        val millis = prefs?.getLong(key, 0L) ?: 0L
        return if (millis == 0L) null else Date(millis)
    }

    fun updateCheckpoint(key: String, timestamp: Date = Date()) {
        prefs?.edit()?.putLong(key, timestamp.time)?.apply()
    }

    fun clearCheckpoint(key: String) {
        prefs?.edit()?.remove(key)?.apply()
    }

    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
    }

    private companion object {
        private const val TAG = "SyncCheckpointStore"
        private const val ENCRYPTED_PREFS_NAME = "sync_checkpoints_encrypted"
        private const val LEGACY_PREFS_NAME = "sync_checkpoints"
    }
}
```

Notes:
- The constructor now keeps `context` (was discarded) so the lazy initializer can build the `MasterKey`. The `SyncCheckpointStore(context)` call shape in `RocketPlanApplication.kt:150` is unchanged.
- New file name `sync_checkpoints_encrypted` avoids the "can't open a plaintext file as encrypted" failure that would occur if we reused `sync_checkpoints` (EncryptedSharedPreferences refuses to open a file it didn't create). We delete the legacy file explicitly.
- The crypto/`MasterKey` parameters are copied verbatim from `SecureStorage.kt:84-94` so both stores share one Keystore-backed master key scheme and the same proven configuration.
- `.apply()` (async) is correct here — checkpoints are recoverable derived state, so a lost deferred write at worst causes one extra full sync, never data loss. This is the opposite trade-off from RP-BUG-005, where the auth token is authoritative and must use `commit()`.
- Fail-safe rationale: returning `null` from `getCheckpoint` on init failure makes the caller pass `updatedSince = null` (see `SyncEntityMappers.updatedSinceParam` and `ProjectSyncService.kt:43`), which the services already treat as "full sync". No caller can NPE because the public return types are unchanged.
- `androidx.security:security-crypto`, `io.sentry.Sentry`, and `android.util.Log` are all already on the app classpath.

### Step 2 — verify callers need no change

No public API changes are expected here: constructor and method signatures stay the same, so no caller changes should be required in app wiring, mapper helpers, or sync services.

All six injection sites take `SyncCheckpointStore` by constructor and only call the four public methods, whose signatures and return types are unchanged:

- `OfflineSyncRepository.kt:86`, `ProjectMetadataSyncService.kt:36`, `PhotoSyncService.kt:60`, `UpdatedRecordsSyncService.kt:21`, `DeletedRecordsSyncService.kt:24`, `ProjectSyncService.kt:28`
- `SyncEntityMappers.updatedSinceParam(key)` (extension) calls `getCheckpoint` and formats the result — unaffected.

No DI/wiring changes (`RocketPlanApplication.syncCheckpointStore = SyncCheckpointStore(this)` is untouched).

## Test Plan

- [ ] Unit test (new `SyncCheckpointStoreTest`): round-trip — `updateCheckpoint("k", Date(123_000))` then `getCheckpoint("k")` returns `Date(123_000)`; `clearCheckpoint("k")` then `getCheckpoint("k")` returns `null`; `clearAll()` clears everything. (Robolectric or instrumented, since `EncryptedSharedPreferences` needs a real `Context` + Keystore; if the existing `SecureStorageTest` uses a fake-prefs seam, mirror that approach.)
- [ ] Unit test: init failure degrades safely — force `createEncryptedPrefs()` to return `null` (e.g. inject a context whose `MasterKey` build throws); assert `getCheckpoint` returns `null`, `updateCheckpoint` is a no-op (no throw), and Sentry is captured once with tag `event=sync_checkpoint_store_init_failed`.
- [ ] Test seam note: if direct fault injection into encrypted-prefs creation is awkward, extract that creation behind an injectable factory so init-failure behavior is unit-testable without keystore manipulation.
- [ ] Manual QA on tablet `30407ef` (`devStandardDebug`):
  1. Install fixed build over an existing signed-in build that has cleartext checkpoints.
  2. Confirm `adb -s 30407ef shell run-as com.rocketplantech.rocketplan ls shared_prefs` shows `sync_checkpoints_encrypted.xml` and **not** `sync_checkpoints.xml`.
  3. `adb ... cat shared_prefs/sync_checkpoints_encrypted.xml` shows encrypted (non-numeric/base64) values, not readable epoch millis.
  4. Trigger a sync; confirm the first sync per entity runs full (expected, since checkpoints reset) and subsequent syncs are incremental (checkpoints persist across app restarts).
- [ ] `./gradlew testDevStandardDebugUnitTest` passes (run in background per project convention).

## Rollback Plan

Single-file change. Revert `SyncCheckpointStore.kt` to the plain `SharedPreferences` body. Because checkpoints are disposable derived state, rolling back simply re-creates the cleartext `sync_checkpoints` file on next write and the next sync per key runs full once. No data migration or schema concern. The legacy-file deletion is idempotent (`deleteSharedPreferences` no-ops if absent), so re-rolling forward later is also clean.

## Dependencies

- Requires: none (`androidx.security:security-crypto` already present; no server or build-flag change).
- Blocking: none. Independent of the RP-BUG-005 plan (different file). Safe to land in either order.

## Changelog Entry

```markdown
## [1.0.XX] - 2026-06-XX

### Fixed
- [RP-BUG-008] Sync checkpoints are now stored in EncryptedSharedPreferences instead of cleartext, preventing on-device tampering that could cause skipped syncs (data loss) or redundant full re-syncs. The legacy cleartext checkpoint file is removed on upgrade; checkpoints reset to a one-time full sync per entity.
```

## Observability changes

Adds one Sentry event tag — `event=sync_checkpoint_store_init_failed` — using the same `Sentry.withScope { setTag … captureException }` shape as `auth_migration_failed` (RP-HD-002) and the proposed `auth_token_commit_failed` (RP-BUG-005). It fires only if the encrypted store cannot be opened, in which case checkpoints are disabled and all syncs become full syncs. No new metrics/dashboards; the new `SyncCheckpointStore` log tag hosts the fallback `Log.w` line. Expected production count: zero.
