**Bug ID(s):** RP-BUG-031
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-031_room_update_success_on_failure.md) · [Plan](./plan_rp_bug_031_room_push_success_on_failure_2026-06-03.md) · Review: pending

# Fix Plan: [RP-BUG-031] Stop reporting SUCCESS when a room-update retry fails

**Bug ID(s):** RP-BUG-031
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-03
**State:** draft

---

## Summary

In `RoomPushHandler.handleUpdate()`, the 409-conflict retry path ends with:

```kotlin
responseDto = retryResult.getOrNull() ?: return OperationOutcome.SUCCESS
```

When the retry fails with a non-409/422 error (network timeout, 500, etc.), the `onFailure` block only rethrows `CancellationException`; all other errors fall through to this line. `getOrNull()` is then `null`, and the handler returns **SUCCESS**. `SyncQueueProcessor` removes the operation from the queue and the room is marked `SYNCED`/`isDirty=false` — but the server was never updated. The room stays stale with no pending op to retry.

The fix: return `OperationOutcome.RETRY` (which `SyncQueueProcessor` already keeps in the queue, see `SyncQueueProcessor.kt:281 RETRY -> Unit`) when the retry produces no response, and do **not** mark the room synced.

## Affected Code

| File | Change |
|------|--------|
| `data/repository/sync/handlers/RoomPushHandler.kt` (≈402–447) | In the retry `onFailure`, rethrow `CancellationException`, handle 409 → `CONFLICT_PENDING` and 422 → `DROP` as today, and for any **other** error return `OperationOutcome.RETRY` instead of falling through. |
| `data/repository/sync/handlers/RoomPushHandler.kt` (≈449–450) | Replace `?: return OperationOutcome.SUCCESS` with `?: return OperationOutcome.RETRY`. Do not run the local "mark synced" save when `responseDto` is null. |

## Implementation Notes

### Step 1: Make the retry failure branch explicit

```kotlin
retryResult.onFailure { retryError ->
    if (retryError is CancellationException) throw retryError
    if (retryError.isConflict()) { /* …record conflict… */ return OperationOutcome.CONFLICT_PENDING }
    if (retryError.isValidationError()) { /* …log… */ return OperationOutcome.DROP }
    Log.w(TAG, "room update retry failed (non-409/422); keeping op for retry", retryError)
    return OperationOutcome.RETRY            // was: fall through → SUCCESS
}
```

### Step 2: Guard the success-only local save

The local save that sets `serverUpdatedAt`, `syncStatus = SYNCED`, `isDirty = false` must run **only** on a real `responseDto`. With Step 1 the `?: return` below becomes defensive, but keep it as `RETRY`, not `SUCCESS`:

```kotlin
responseDto = retryResult.getOrNull() ?: return OperationOutcome.RETRY
// only reached with a valid DTO → safe to mark room synced
```

### Step 3: Confirm processor backoff applies

`RETRY` keeps the operation (`SyncQueueProcessor.kt:281`). Verify the operation's `retryCount`/backoff still advances so a permanently-failing room update doesn't hot-loop — the existing skip/backoff machinery (`SyncQueueProcessor.kt:1061`) should cover it; add a cap if not.

## Observability

- Add console log `room_update_retry_exhausted` (`room_id`, `retry_count`, `error_type`) on the new RETRY branch.
- Optional remote error `room_update_failure_misreported` only if QA confirms this path occurs in production — it indicates server reachability problems, not just app logic.

## Test Plan

- [ ] Unit: retry throws a 500 → handler returns `RETRY`, room is **not** marked SYNCED, operation remains in queue.
- [ ] Unit: retry succeeds → handler returns `SUCCESS`, room marked SYNCED with fresh `serverUpdatedAt`.
- [ ] Unit: retry throws 409 → `CONFLICT_PENDING`; retry throws 422 → `DROP` (unchanged).
- [ ] Manual QA:
  1. Prereq: edit a room name while server returns a transient 5xx (or mock).
  2. Action: trigger sync.
  3. Expected: room stays dirty/pending; on next successful sync the edit reaches the server.

## Rollback Plan

Revert the two edits in `RoomPushHandler.kt`. No schema changes. Reverting restores the prior false-SUCCESS behavior.

## Dependencies

- Requires: none.
- Blocking: none. Same class of bug as RP-FR-004 (handlers using `else throw e`); fix is independent.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-031] A failed room-update retry no longer reports success; the edit stays queued and retries instead of being silently dropped while the server stays stale.
```
