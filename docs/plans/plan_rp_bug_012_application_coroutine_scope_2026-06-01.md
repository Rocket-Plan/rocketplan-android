**Bug ID(s):** RP-BUG-012
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-012_application_coroutine_scope.md) · [Plan](./plan_rp_bug_012_application_coroutine_scope_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-012] Untracked coroutine scopes in application startup

**Bug ID(s):** RP-BUG-012
**Author:** Claude (Opus 4.8)
**Date:** 2026-06-01
**State:** draft

---

## Summary

`RocketPlanApplication.onCreate()` launches several startup tasks with ad-hoc `CoroutineScope(Dispatchers.IO).launch { ... }` blocks (lines 307, 317, 330). These scopes are never retained, cancelled, or surfaced. The cold-start recovery block (307-314) calls `recoverStrandedAssemblies()` and `processNextQueuedAssembly()` with **no** `runCatching` around the first two calls, so an exception there crashes the coroutine silently with only a default uncaught-exception log — and there is no remote visibility. (Only the third call, `cleanupOldAssemblies()`, is wrapped.)

The fix is to introduce a single named, supervised application-level scope and route all startup tasks through it, wrapping each task so failures are logged remotely. This gives consistent error handling and a single owned scope for startup work plus consistent failure reporting.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/RocketPlanApplication.kt` | Add a single `applicationScope` (SupervisorJob + Dispatchers.IO); replace the three ad-hoc `CoroutineScope(...).launch` blocks with launches on it; wrap each startup task so exceptions are logged to `remoteLogger`. |

## Implementation Notes

### Step 1: Introduce a named, supervised application scope

Add a field on the application class (the file already imports `CoroutineScope` at line 42):

```kotlin
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

A `SupervisorJob` ensures one failing startup task does not cancel the siblings.

This does **not** provide deterministic app-exit cancellation on Android; its purpose is structured ownership and sibling-failure isolation during process lifetime, not a guaranteed shutdown hook.

### Step 2: Add a helper that never lets a startup task fail silently

```kotlin
private fun launchStartupTask(name: String, block: suspend () -> Unit) {
    applicationScope.launch {
        runCatching { block() }
            .onFailure { error ->
                Log.w(TAG, "Startup task failed: $name", error)
                runCatching {
                    remoteLogger?.log(
                        level = LogLevel.ERROR,
                        tag = TAG,
                        message = "Startup task failed",
                        metadata = mapOf("task" to name)
                    )
                }
            }
    }
}
```

### Step 3: Route the three startup blocks through it

Replace the block at lines 307-314:

```kotlin
CoroutineScope(Dispatchers.IO).launch {
    imageProcessorQueueManager.recoverStrandedAssemblies()
    imageProcessorQueueManager.processNextQueuedAssembly()
    runCatching { imageProcessorRepository.cleanupOldAssemblies() }
        .onFailure { error ->
            Log.w(TAG, "Failed to cleanup old image processor assemblies", error)
        }
}
```

with:

```kotlin
launchStartupTask("recover_stranded_assemblies") {
    imageProcessorQueueManager.recoverStrandedAssemblies()
    imageProcessorQueueManager.processNextQueuedAssembly()
}
launchStartupTask("cleanup_old_assemblies") {
    imageProcessorRepository.cleanupOldAssemblies()
}
```

Apply the same conversion to the `repairMismatchedPhotoRoomIds()` block (317-327) and the `cleanupOrphanedProperties()` block (330+), preserving their existing WARN remote logs for the success/count branches inside the lambda. These two already use `localDataService` calls that return counts; keep that logic, just move it inside `launchStartupTask(...)`.

### Step 4: Confirm `LogLevel` and `Log` imports

`remoteLogger`, `LogLevel`, `Log`, and `TAG` are already used in this file (lines 312, 320-325), so no new imports are needed beyond confirming they remain.

## Observability

- Add a single remote ERROR event/log for terminal startup task failure with `task=<name>`. Expected frequency is near-zero.
- Keep console `Log.w(TAG, ...)` for local debugging.
- Noise control: log only terminal task failure, not normal startup progress.


## Test Plan

- [ ] Unit test (if `RocketPlanApplication` startup logic is extractable): verify that a thrown exception in a startup task is caught and reported, and does not propagate. Prefer extracting `launchStartupTask` into a small helper or injectable runner so exception-capture behavior is unit testable without patching production startup methods.
- [ ] Manual QA:
  1. Prereq: dev build; temporarily make `recoverStrandedAssemblies()` throw in a local patch.
  2. Action: cold-start the app.
  3. Expected: app does not crash; a remote ERROR log "Startup task failed" with `task=recover_stranded_assemblies` appears; other startup tasks still run.
- [ ] Manual QA (happy path): cold-start with queued/stranded assemblies present and confirm recovery + queue processing still occur.

## Rollback Plan

Revert the single-file change. The behavior reverts to the prior ad-hoc scopes; no persisted-data or API changes.

## Dependencies

- Requires: none
- Blocking: none

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-012] Routed application startup tasks through a single supervised scope and reported failures remotely, so cold-start recovery can no longer fail silently.
```
