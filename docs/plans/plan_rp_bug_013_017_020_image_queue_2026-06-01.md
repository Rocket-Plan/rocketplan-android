**Bug ID(s):** RP-BUG-013, RP-BUG-017, RP-BUG-020
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [RP-BUG-013](../investigations/RP-BUG-013_retry_worker_cancellation.md) · [RP-BUG-017](../investigations/RP-BUG-017_shutdown_inflight_requests.md) · [RP-BUG-020](../investigations/RP-BUG-020_queue_error_swallowed.md) · [Plan](./plan_rp_bug_013_017_020_image_queue_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-013/017/020] ImageProcessor queue & retry error handling

**Bug ID(s):** RP-BUG-013, RP-BUG-017, RP-BUG-020
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

Three related defects in the ImageProcessor upload/retry path share the same two
files and the same root theme — error handling that either swallows the wrong
exceptions or releases resources too aggressively:

- **RP-BUG-013 (P1):** `ImageProcessorRetryWorker.doWork()` catches `Exception`
  and returns `Result.retry()`. Because `CancellationException` is an
  `Exception`, a worker that is cancelled (e.g. on app shutdown / WorkManager
  cancellation) is caught and rescheduled, defeating cooperative cancellation
  and risking a retry loop. `CancellationException` must be rethrown.
- **RP-BUG-017 (P2):** `ImageProcessorQueueManager.shutdown()` cancels the scope
  and immediately calls `executorService.shutdown()` + `connectionPool.evictAll()`
  without awaiting in-flight HTTP uploads, so active uploads are killed mid-flight
  instead of being allowed a short grace period to finish.
- **RP-BUG-020 (P2):** The `catch (e: Exception)` in `processNextQueuedAssembly()`
  logs the error and clears the `isProcessingQueue` lock but does **not** update
  the assembly status or schedule a retry, so the assembly that was being
  processed can be left stuck in `UPLOADING`/`CREATED` limbo with no recovery.

These are deliberately kept distinct: **013 is specifically about not swallowing
`CancellationException`** (a correctness/cancellation bug), whereas **020 is about
recovering an in-flight assembly when a genuine processing failure is caught**
(a state-recovery bug). The fixes must not be conflated — 020 should still catch
and recover real failures, but like 013 it must rethrow `CancellationException`.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/worker/ImageProcessorRetryWorker.kt` | Rethrow `CancellationException`; keep `Result.retry()` only for genuine failures (RP-BUG-013). |
| `app/src/main/java/com/example/rocketplan_android/data/queue/ImageProcessorQueueManager.kt` | `shutdown()`: await termination of the OkHttp dispatcher with a bounded timeout before evicting connections (RP-BUG-017). `processNextQueuedAssembly()`: rethrow `CancellationException`, and on real failures mark the in-flight assembly `FAILED` so the existing retry worker can recover it (RP-BUG-020). |
| `app/src/test/java/com/example/rocketplan_android/data/queue/ImageProcessorQueueManagerTest.kt` | Add/extend tests for shutdown timeout and queue-failure recovery (new file if absent). |

## Implementation Notes

### Step 1: RP-BUG-013 — rethrow CancellationException in the retry worker

`ImageProcessorRetryWorker.kt:35-38` currently catches everything:

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "❌ ImageProcessorRetryWorker failed", e)
    Result.retry()
}
```

After — rethrow cancellation so WorkManager's cooperative cancellation is
honored, and only retry on genuine failures:

```kotlin
} catch (e: CancellationException) {
    // Cooperative cancellation (app shutdown / WorkManager stop) — do not
    // swallow it into a retry, or the worker will reschedule itself.
    throw e
} catch (e: Exception) {
    Log.e(TAG, "❌ ImageProcessorRetryWorker failed", e)
    Result.retry()
}
```

Add the import `import kotlinx.coroutines.CancellationException` (which is
typealiased to `kotlin.coroutines.cancellation.CancellationException`). The
`CancellationException` clause must come **before** the `Exception` clause.

### Step 2: RP-BUG-017 — await in-flight uploads on shutdown

If `shutdown()` can run on the main thread, do not block there for the full grace period; either move shutdown to a background-owned path or cap the blocking window accordingly. The goal is graceful draining, not introducing a new UI freeze risk.

`ImageProcessorQueueManager.kt:128-132` currently tears down resources without
waiting:

```kotlin
fun shutdown() {
    scope.cancel()
    okHttpClient.dispatcher.executorService.shutdown()
    okHttpClient.connectionPool.evictAll()
}
```

After — give in-flight requests a bounded grace period before forcing eviction:

```kotlin
fun shutdown() {
    scope.cancel()
    val executor = okHttpClient.dispatcher.executorService
    executor.shutdown() // stop accepting new tasks, let in-flight ones finish
    try {
        if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "⏱️ Shutdown grace period elapsed with in-flight uploads; forcing stop")
            executor.shutdownNow()
        }
    } catch (e: InterruptedException) {
        executor.shutdownNow()
        Thread.currentThread().interrupt()
    }
    okHttpClient.connectionPool.evictAll()
}
```

Add a companion constant alongside the existing ones (line 76-85):

```kotlin
private const val SHUTDOWN_GRACE_SECONDS = 10L
```

`TimeUnit` is already imported (line 46). Note `shutdown()` itself is not a
`suspend` function and `awaitTermination` is a blocking JDK call — that is
acceptable here because the caller (app teardown / manager disposal) is already
off the main path, but the grace period is intentionally bounded (10s) so
shutdown cannot hang indefinitely.

### Step 3: RP-BUG-020 — recover the in-flight assembly on queue-processing failure

Wrap the recovery write itself in `runCatching` so a failure in `updateAssemblyStatus(...)` does not mask the original exception or skip the remote/logging path. The queue must still clear `isProcessingQueue` even if the recovery write fails.

`ImageProcessorQueueManager.kt:708-717` currently only logs and unlocks:

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "❌ Error processing queue", e)
    isProcessingQueue.set(false)
    remoteLogger?.log(
        level = LogLevel.ERROR,
        tag = TAG,
        message = "Queue processing failed: ${e.message}",
        metadata = mapOf("error" to (e.message ?: "unknown"))
    )
}
```

After — rethrow cancellation (consistent with Step 1), and for real failures
mark the assembly that was being processed as `FAILED` so the existing
`processRetryQueue` / `ImageProcessorRetryWorker` path can pick it up. The
assembly being processed is `nextAssembly`, which is in scope inside the `try`;
to make it reachable in `catch`, hoist a nullable reference declared before the
`try`:

```kotlin
fun processNextQueuedAssembly() {
    scope.launch {
        queueMutex.withLock {
            var processingAssembly: ImageProcessorAssemblyEntity? = null
            try {
                // ... existing checks ...
                val nextAssembly = createdAssemblies.firstOrNull()
                if (nextAssembly == null) {
                    Log.d(TAG, "📭 No created assemblies found")
                    return@launch
                }
                processingAssembly = nextAssembly

                isProcessingQueue.set(true)
                // ... existing remoteLogger + uploadAssembly(nextAssembly) ...
            } catch (e: CancellationException) {
                // Do not swallow cancellation into a "failed assembly" state.
                isProcessingQueue.set(false)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing queue", e)
                isProcessingQueue.set(false)
                // Recover the in-flight assembly instead of leaving it in limbo.
                processingAssembly?.let { assembly ->
                    updateAssemblyStatus(
                        assembly.assemblyId,
                        AssemblyStatus.FAILED,
                        "Queue processing failed: ${e.message}"
                    )
                }
                remoteLogger?.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Queue processing failed: ${e.message}",
                    metadata = mapOf(
                        "error" to (e.message ?: "unknown"),
                        "assembly_id" to (processingAssembly?.assemblyId ?: "unknown")
                    )
                )
            }
        }
    }
}
```

Rationale for using `FAILED` (rather than introducing new recovery code):
`FAILED` is already a retryable status that `processRetryQueue()` reconciles
(lines 759-810, where `FAILED` assemblies are promoted to `RETRYING` and
re-queued), and the periodic `ImageProcessorRetryWorker` already drives that
path. Marking the stuck assembly `FAILED` reuses the established recovery
machinery instead of adding a parallel mechanism. Add the import
`import kotlinx.coroutines.CancellationException`.

## Observability

- Console signal when a `CancellationException` is rethrown instead of being retried.
- One warning if shutdown hits its grace timeout.
- One error per failed assembly recovery path with `assembly_id` metadata.
- Noise control: no progress spam; only timeout and terminal failure logs.

## Test Plan

- [ ] Unit test (RP-BUG-013): worker `doWork()` rethrows `CancellationException`
      and does not return `Result.retry()`; a non-cancellation exception still
      yields `Result.retry()`.
- [ ] Unit test (RP-BUG-020): when `uploadAssembly` throws a non-cancellation
      exception, the processed assembly ends in `FAILED` and `isProcessingQueue`
      is cleared; when a `CancellationException` is thrown, status is **not**
      forced to `FAILED` and the exception propagates.
- [ ] Unit test (RP-BUG-020): if `updateAssemblyStatus(...)` itself throws during recovery, `isProcessingQueue` is still cleared and remote logging still occurs.
- [ ] Unit/instrumented test (RP-BUG-017): `shutdown()` returns within ~grace
      period even with a simulated long-running dispatcher task and calls
      `evictAll()` afterward.
- [ ] Manual QA:
  1. Prereq: device with a queued multi-photo assembly, throttled network.
  2. Action: start an upload, then force-stop / background the app to trigger
     `shutdown()`; observe logs for the grace-period message and confirm no
     mid-flight crash. Separately, induce a processing failure (e.g. revoke
     token) and confirm the assembly transitions to `FAILED` and is later
     retried by the worker rather than staying stuck.
  3. Expected: cancellation does not spawn retry loops (013); shutdown waits up
     to 10s then proceeds (017); failed in-flight assembly is recoverable (020).

## Rollback Plan

All three changes are localized and additive to existing error handling. Revert
the two source files (and the test file) to restore prior behavior. No schema,
migration, or persisted-data changes are involved.

## Dependencies

- Requires: none (server API unchanged).
- Blocking: none. The three fixes are independent edits within shared files and
  can ship together or individually.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-013] ImageProcessorRetryWorker no longer swallows CancellationException into a retry, preventing shutdown retry loops.
- [RP-BUG-017] ImageProcessorQueueManager.shutdown() now waits briefly for in-flight uploads before tearing down connections.
- [RP-BUG-020] Queue-processing failures now mark the in-flight assembly FAILED so it can be recovered instead of being stuck in limbo.
```
