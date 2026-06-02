**Bug ID(s):** RP-BUG-004
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-004_connectivity_silent_fallback.md) · [Plan](./plan_rp_bug_004_connectivity_silent_fallback_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-004] Silent fallback when ConnectivityManager is unavailable

**Bug ID(s):** RP-BUG-004
**Author:** Claude (Opus 4.8)
**Date:** 2026-06-01
**State:** draft

---

## Summary

`SyncQueueManager.isNetworkAvailable()` returns `true` when `connectivityManager` is `null`, and also returns `true` (`getOrDefault(true)`) when the capability lookup throws. The `connectivityManager` is obtained in `RocketPlanApplication` via `getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager` (line 181), which can legitimately be `null`. When it is, every connectivity gate in `SyncQueueManager` (lines 198, 548, 553, 567, 585, 764, 777, 801, 1066) treats the device as online, so sync runs while offline, fails, and there is zero visibility into why.

The fix is two-fold: (1) keep the optimistic default so we never *block* sync when connectivity state is genuinely unknown (avoiding a worse failure mode where sync silently stalls forever), but (2) surface the condition the first time it happens via a remote log so the silent fallback is observable. We deliberately do not flip the default to `false`, because that would regress devices where `ConnectivityManager` is briefly unavailable into a permanently-offline state.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/sync/SyncQueueManager.kt` | Log once (debug + remote WARN) when `connectivityManager` is null or capability lookup throws; keep optimistic `true` default; clarify the rationale in a comment. |

## Implementation Notes

### Step 1: Add a one-shot guard flag

In `SyncQueueManager` (near the other private fields around line 51), add a flag so we do not spam the remote logger on every gate check:

```kotlin
private val connectivityFallbackLogged = AtomicBoolean(false)
```

### Step 2: Make the fallback observable

Replace the current `isNetworkAvailable()` (lines 234-241):

```kotlin
private fun isNetworkAvailable(): Boolean {
    val cm = connectivityManager ?: return true // Assume available if no manager provided
    return runCatching {
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
```

with:

```kotlin
private fun isNetworkAvailable(): Boolean {
    val cm = connectivityManager
    if (cm == null) {
        // Optimistic default: we let sync proceed rather than stall forever when
        // connectivity state is unknown, but we surface it so it is not silent.
        logConnectivityFallback("connectivity_manager_unavailable")
        return true
    }
    return runCatching {
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrElse { error ->
        logConnectivityFallback("connectivity_lookup_failed", error)
        true
    }
}

private fun logConnectivityFallback(reason: String, error: Throwable? = null) {
    if (!connectivityFallbackLogged.compareAndSet(false, true)) return
    Log.w(TAG, "Network availability defaulting to true: $reason", error)
    scope.launch {
        runCatching {
            remoteLogger.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Connectivity check fell back to optimistic default",
                metadata = mapOf("reason" to reason)
            )
        }
    }
}
```

Confirm `TAG`, `LogLevel`, and the `scope` (`CoroutineScope(SupervisorJob() + Dispatchers.IO)` at line 54) are already present in the file; `remoteLogger` is a constructor param (line 47). Add the `LogLevel` / `Log` imports only if not already imported.

## Observability

### Current Signals
- Today the optimistic fallback is silent.
- Sync failures caused by a missing or broken connectivity lookup are indistinguishable from ordinary network errors.

### Proposed Signals
- One-shot local warning log under the existing `SyncQueueManager` tag.
- One-shot remote WARN log with `reason=connectivity_manager_unavailable|connectivity_lookup_failed`.

### Noise Control
- Log at most once per process lifetime via the atomic guard.
- Do not log network identifiers, URLs, or user data.

### QA Verification Signal
- Repeated calls while the manager is missing or throwing still emit only one remote warning.

## Test Plan

- [ ] Unit test: exercise the fallback through an internal-visible helper or through public queue behavior that depends on `isNetworkAvailable()`, rather than relying on direct access to a private method. With `connectivityManager = null`, assert the result stays optimistic (`true`) and that `remoteLogger.log` is invoked exactly once across multiple calls.
- [ ] Unit test: mock a `ConnectivityManager` whose `getNetworkCapabilities` throws; assert the optimistic fallback still returns `true` and logs once with reason `connectivity_lookup_failed`.
- [ ] Manual QA:
  1. Prereq: dev build on a device.
  2. Action: confirm normal online/offline gating still behaves (toggle airplane mode, observe sync pauses when offline with a real manager).
  3. Expected: with a real `ConnectivityManager`, offline correctly returns `false`; no fallback log is emitted.

## Rollback Plan

Revert the single-file change. No schema, persisted-data, or API surface changes are involved.

## Dependencies

- Requires: none
- Blocking: none

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-004] Made the "ConnectivityManager unavailable" sync fallback observable via a one-shot warning log instead of silently assuming the network is up.
```
