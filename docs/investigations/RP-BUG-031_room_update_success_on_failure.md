---
bug_id: RP-BUG-031
aliases: []
title: RoomPushHandler.handleUpdate returns SUCCESS when retry fails with non-409/422 error, removing operation from queue despite no server update
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_031_room_push_success_on_failure_2026-06-03.md
related_review: null
related_test: null
last_updated: 2026-06-03
---

# Investigation: RoomPushHandler returns SUCCESS when retry exhausts with non-409/422 error

## Symptom

When room update fails after retry with a non-409/422 error (e.g., network timeout, 500), `handleUpdate()` returns `OperationOutcome.SUCCESS` to the caller. The caller then removes the operation from the queue and marks the room as synced locally. However, the server was never actually updated - the room stays stale.

## Discovery

- **Source:** Parity review vs iOS RP-BUG-012
- **Evidence:** Code analysis confirmed bug
- **Found during:** iOS parity investigation 2026-06-03

## Root Cause (Confirmed)

### The Bug Location: `RoomPushHandler.kt` lines 449-471

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/sync/handlers/RoomPushHandler.kt`

```kotlin
// Lines 400-450
val retryResult = runCatching { ctx.api.updateRoom(serverId, retryRequest) }
    .onFailure { if (it is CancellationException) throw it }
retryResult.onFailure { retryError ->
    if (retryError.isConflict()) {
        // ... handle conflict, returns CONFLICT_PENDING at line 437
        return OperationOutcome.CONFLICT_PENDING
    }
    if (retryError.isValidationError()) {
        // ... handle validation error, returns DROP at line 445
        return OperationOutcome.DROP
    }
    throw retryError  // Line 447 - only CancellationException is rethrown here
}
// Line 449-450 - THE BUG:
responseDto = retryResult.getOrNull()
    ?: return OperationOutcome.SUCCESS  // Returns SUCCESS when retry failed!
```

### Why the Bug Fires

1. When `retryResult` is a failure (non- CancellationException), execution falls through the `onFailure` handler
2. The handler only rethrows `CancellationException` - all other exceptions continue to line 449
3. `retryResult.getOrNull()` returns `null` when the retry failed
4. The `?: return OperationOutcome.SUCCESS` incorrectly returns SUCCESS

### Caller Behavior: SyncQueueProcessor removes operation on SUCCESS

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/sync/SyncQueueProcessor.kt`
**Line:** 222

```kotlin
when (outcome) {
    OperationOutcome.SUCCESS -> localDataService.removeSyncOperation(operation.operationId)  // LINE 222
    ...
}
```

## The Complete Bug Path

### Bug Case (retry throws non-409/422 error):

1. `updateRoom()` throws 409 → line 364 branch
2. Fresh data fetched (line 380), retry request created (line 394)
3. `retryResult = runCatching { ctx.api.updateRoom(serverId, retryRequest) }` → **FAILURE**
4. `retryResult.onFailure { retryError ->` handler at line 402:
   - `retryError.isConflict()` → `false` (not a 409)
   - `retryError.isValidationError()` → `false` (not 422)
   - `throw retryError` at line 447 → only CancellationException is rethrown, others aren't
5. `responseDto = retryResult.getOrNull()` → `null`
6. `?: return OperationOutcome.SUCCESS` triggers → **RETURNING SUCCESS ON FAILURE!**
7. Lines 463-470: Room saved with stale `serverUpdatedAt`, marked `SYNCED`
8. `return OperationOutcome.SUCCESS`
9. **Caller removes operation from queue** - no retry will ever happen

## Impact

| Aspect | What Happens |
|--------|--------------|
| **Local state** | Room marked `isDirty = false`, `syncStatus = SYNCED` |
| **Server state** | Never updated - still has old data |
| **Next sync** | No pending operation exists to retry |
| **Data loss** | Room stays stale on server until manually re-edited |
| **User visible** | Changes appear saved but don't sync |

## Correct vs Incorrect Outcomes

| Scenario | Response DTO | Local Save | Return | Correct? |
|---------|-------------|------------|--------|----------|
| Retry succeeds | Valid RoomDto | Fresh serverUpdatedAt | SUCCESS | Yes |
| Retry fails (non-409/422) | `null` | Stale serverUpdatedAt | SUCCESS | **NO** |
| Retry fails (409 double-conflict) | N/A | Conflict recorded | CONFLICT_PENDING | Yes |
| Retry fails (422 validation) | N/A | None | DROP | Yes |

## Affected Code

| Location | Lines | Issue |
|----------|-------|-------|
| `RoomPushHandler.kt` | 402-447 | `onFailure` handler doesn't properly handle non-409/422 errors |
| `RoomPushHandler.kt` | 449-450 | Returns SUCCESS when retry failed (getOrNull() is null) |
| `SyncQueueProcessor.kt` | 222 | Removes operation from queue on SUCCESS |

## Proposed Fix Approach

1. **Return distinct failure outcome** when retry fails with non-409/422 error - use `OperationOutcome.RETRY` or keep operation in queue
2. **Don't remove operation on failure** - let the retry logic in SyncQueueProcessor handle backoff
3. **Preserve stale flag** - if returning SUCCESS is the desired behavior for some cases, at least don't mark the room as synced when the server wasn't updated

## Observability

### Current Signals
- Local console logs: `room_update_retry_failed`, `room_update_conflict_resolved`
- Remote logs: None currently
- Sentry: None yet observed

### Gaps
- No distinction between "success after retries" vs "gave up"
- Caller receives misleading success signal

### Proposed Instrumentation
- Local debug logs: `room_update_retry_exhausted`, `room_update_final_failure`
- Remote logs: Error category `room_update_failure_misreported`
- Key fields: `room_id`, `retry_count`, `final_outcome`, `error_type`

---

## Related

- iOS counterpart: `RP-BUG-012` (updateRoom failure reports .success to caller)