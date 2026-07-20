**Bug ID:** RP-FR-025
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-025_legacy_equipment_pushhandler_throws_2026-07-17.md) · Plan (this doc)

# Plan — Return OperationOutcome.RETRY instead of throwing (RP-CD-004)

## Scope
`data/repository/sync/handlers/EquipmentPushHandler.kt` — the **legacy count-based** handler only. Do NOT
touch the serialized `EquipmentAssetPushHandler` (already conforms).

> Note: this handler is superseded by the serialized rebuild (RP-FR-019). If legacy count-based equipment
> is being retired imminently, this ticket can be deprioritized/closed as won't-fix. Otherwise apply the
> one-line fix below for contract hygiene.

## Fix
`EquipmentPushHandler.handle409Conflict` (~line 180): the terminal `else` after the CONFLICT_PENDING and
422/DROP branches currently does `throw retryError`. Replace it so the handler never throws:

```kotlin
            if (retryError.isValidationError()) {
                Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid}: server validation error (422)")
                return OperationOutcome.DROP
            }
            // RP-CD-004: never throw — a transient failure of the post-409 retry is retryable.
            Log.w(SYNC_TAG, "Equipment 409-retry hit a transient error; retrying", retryError)
            return OperationOutcome.RETRY
```

Behavior is unchanged in practice (the processor's `runCatching` already treated the throw as a failed op),
but the outcome is now explicit and the "never throw" contract holds. Preserve the `CancellationException`
rethrow path if one is reachable above this point (do not swallow cancellation).

## Verification
- Both compile gates clean.
- Regression test (add, if the handler has a test harness): stub the post-409 retry to raise a transient
  (non-4xx) error; assert `handleUpsert` returns `OperationOutcome.RETRY` and does not throw.
- Confirm the 409→CONFLICT_PENDING and 422→DROP paths are unchanged.

## Observability
Adds a local `Log.w` on the retry path (console-only; low-value for remote per the tracker's remote-logging
guidance — keep it `Log.w`, do not add a remote log here).
