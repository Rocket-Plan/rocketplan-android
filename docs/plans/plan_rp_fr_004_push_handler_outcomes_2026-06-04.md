**Bug ID(s):** RP-FR-004
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-004_push_handlers_throw_instead_of_outcome.md) · [Plan](./plan_rp_fr_004_push_handler_outcomes_2026-06-04.md) · Review: pending · Parent: [RP-HD-001](../investigations/RP-HD-001_rp_cd_rule_audit.md)

# Fix Plan: [RP-FR-004] Map unknown push-handler errors to OperationOutcome.RETRY

**Bug ID(s):** RP-FR-004 (violates RP-CD-004)
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-04
**State:** implemented

---

## Summary

Eight push-handler sites end an error branch with `else throw e` instead of returning an explicit `OperationOutcome`. This is **low severity**: `SyncQueueProcessor.handleOperation` wraps every handler in `runCatching { … }` (`SyncQueueProcessor.kt:202`) and routes throws to `markSyncOperationFailure(...)` (`:305–336`), so the queue never stalls and nothing crashes. The cost is purely **semantic granularity** — a throw becomes an opaque generic failure instead of an explicit `RETRY`/`SKIP`/`DROP`/`CONFLICT_PENDING`, so backoff and metrics can't see its true category.

This plan takes the "tighten the handlers" direction: map unknown errors to `OperationOutcome.RETRY` — **but only after fixing what `RETRY` does in the processor.**

### ⚠️ Prerequisite (corrects the first draft): `RETRY` is currently a no-op

Verified in `SyncQueueProcessor`:
- `OperationOutcome.RETRY -> Unit` (`:281`) — does **nothing**: no `retryCount` increment, no `scheduledAt`, no backoff. The op just stays pending.
- `OperationOutcome.RETRY -> SyncOperationOutcome.SKIP` (`:211`) in the status mapping.
- The current `else throw e` path goes through `runCatching {…}.onFailure { markSyncOperationFailure(...) }` (`:305–336`), which **does** increment `retryCount`, set `scheduledAt`, apply exponential backoff, and eventually mark failed.

So naively swapping `throw` → `RETRY` would **remove** retry/backoff accounting and risk a hot pending op / sync-loop. `RETRY` must be made to actually advance retry/backoff **first**.

> Note: this also retroactively affects **RP-BUG-031** (already shipped using `RETRY` for RoomPushHandler) — it currently keeps the op (good, fixes the data loss) but without backoff. Fixing `RETRY` semantics here improves that path too.

## Affected Code

| File:Line | Branch |
|-----------|--------|
| `handlers/EquipmentPushHandler.kt:75` | upsert |
| `handlers/EquipmentPushHandler.kt:95` | delete |
| `handlers/TimecardPushHandler.kt:55` | upsert |
| `handlers/TimecardPushHandler.kt:75` | delete |
| `handlers/MoistureLogPushHandler.kt:49` | upsert |
| `handlers/MoistureLogPushHandler.kt:69` | delete |
| `handlers/PropertyPushHandler.kt:69` | create |
| `handlers/SupportPushHandler.kt:85` | conversation create |

## Implementation Notes

### Step 0 (PREREQUISITE): Make `OperationOutcome.RETRY` advance retry/backoff

Before touching any handler, change the processor so `RETRY` is behavior-equivalent to the old throw path. Options:

- **(a) Route `RETRY` through the same accounting as failures** — in the `when (outcome)` block (`:281`), replace `RETRY -> Unit` with a call to the same `retryCount`/`scheduledAt`/backoff logic `markSyncOperationFailure` uses (or the `SKIP` backoff at `:271–276`). This is the smallest change and fixes RP-BUG-031's path too.
- **(b) Extend `RETRY` to carry a reason/error** (`RETRY(val reason: String?)`) and have the processor log + apply backoff. More work; only if metrics need the reason.

Recommend (a). Add a test that an op returning `RETRY` ends with an incremented `retryCount` and a future `scheduledAt`, matching a thrown-error op.

### Step 1: Preserve the CancellationException rethrow

`CancellationException` must still propagate (coroutine cancellation is not a sync failure). Each site becomes:

```kotlin
// before:
} else {
    throw e
}

// after:
} else {
    if (e is CancellationException) throw e
    Log.w(SYNC_TAG, "<handler> unknown error; retrying", e)
    return OperationOutcome.RETRY
}
```

Audit each of the 8 sites for an existing upstream `CancellationException` guard (as RoomPushHandler has at `:401`); only add the inline check where one isn't already guaranteed.

### Step 2: Keep 409/422 handling intact

These `else` branches are the *fallthrough* after the handler has already checked `isConflict()` / `isValidationError()`. Do not disturb those checks — only the final catch-all changes from `throw` to `RETRY`.

### Step 3: Verify backoff parity

After Step 0, confirm an op that returns `RETRY` lands in the same `retryCount`/`scheduledAt`/backoff state as one that threw. This is the gate that makes the handler change safe.

### Alternative (fallback if Step 0 is rejected)

If the team does not want to change processor `RETRY` semantics, then **do not** swap `throw` → `RETRY` (it would regress backoff). Instead relax `RP-CD-004` to accept "re-throw into the processor catch-all" as a compliant fallback, and reserve the rule for handlers throwing *without* such a net. That's a one-line wording change in `RP-CD_rules.md` and no handler edits. This is the safer option **if and only if** Step 0 isn't done.

## Observability

- The new WARN per site (`<handler> unknown error; retrying`) gives the explicit category the rule wants. No remote-log category needed unless QA shows these firing in volume.

## Test Plan

- [ ] Unit (per handler): an unknown (non-409/422, non-cancellation) error → handler returns `RETRY`, operation stays queued.
- [ ] Unit: `CancellationException` still propagates (not swallowed into RETRY).
- [ ] Unit: 409 → `CONFLICT_PENDING`, 422 → `DROP` unchanged.
- [ ] Regression: a handler that previously threw and a handler that now returns RETRY land the operation in the same retry/backoff state.

## Rollback Plan

Revert the 8 edits. Pure control-flow change, no schema or data impact. The processor safety net means even partial revert is non-breaking.

## Dependencies

- **Decided:** tighten handlers — but Step 0 (fix `RETRY` to advance backoff) is a hard prerequisite. If Step 0 is not done, fall back to relaxing `RP-CD-004` instead of returning a no-op `RETRY`.
- Step 0 touches `SyncQueueProcessor` and also improves the already-shipped RP-BUG-031 path; coordinate so that change is reviewed once.
- Blocking: closing **RP-HD-001** depends on this + RP-FR-003.

## Changelog Entry

```markdown
### Changed
- [RP-FR-004] Push handlers now return an explicit RETRY for unknown sync errors instead of throwing, improving retry/metric granularity (no user-visible behavior change).
```
