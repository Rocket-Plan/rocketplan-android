**Bug ID(s):** RP-BUG-034
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-034_property_type_id_missing.md) · [Plan](./plan_rp_bug_034_property_type_id_2026-06-03.md) · Review: pending

# Fix Plan: [RP-BUG-034] Guard propertyTypeId=0 before pushing a property create

**Bug ID(s):** RP-BUG-034
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-03
**State:** draft

---

## Summary

`PendingPropertyCreationPayload.propertyTypeId` is a non-nullable `Int` that defaults to `0`. If a property is created offline without passing through `ProjectTypeSelectionViewModel.selectPropertyType()` or the `RoomSyncService` fallback (both of which supply a valid 1–5 id), the payload carries `0`. `PropertyPushHandler.handleCreate()` sends `propertyTypeId=0`, the server returns 422, and the handler's validation branch returns `DROP` — silently discarding the user's property with no notification.

The two safe paths (Path A: type selection; Path B: `RoomSyncService` fallback to 1) remain valid. The fix prevents the unsafe path from silently destroying data: detect `propertyTypeId == 0` before sending and either supply a sane default or keep the operation recoverable + surface it, rather than dropping on a 422.

## Affected Code

| File | Change |
|------|--------|
| `data/repository/sync/handlers/PropertyPushHandler.kt` (≈38–43) | Pre-send guard: if `payload.propertyTypeId <= 0`, resolve a default or return a recoverable outcome instead of sending `0`. |
| `data/repository/sync/handlers/PropertyPushHandler.kt` (≈62–67) | When a property create is dropped on 422, surface it (recorded error / user-visible signal) rather than a silent `DROP`. |
| `data/repository/mapper/SyncPayloads.kt` (≈20–27) | Consider making `propertyTypeId: Int?` so "unset" is distinguishable from "type 0". |

## Implementation Notes

This needs a decision on the desired behavior for an unset type. Three options, in order of preference:

### Option A — Default to SINGLE_UNIT (1) at push time (preferred; matches Path B)

`RoomSyncService` already falls back to `1` when the catalog is unavailable. Apply the same fallback in the push handler so the unsafe path behaves like the safe one:

```kotlin
val typeId = payload.propertyTypeId.takeIf { it > 0 } ?: 1   // SINGLE_UNIT fallback
val request = PropertyMutationRequest(
    uuid = payload.propertyUuid,
    propertyTypeId = typeId,
    projectUuid = project.uuid,
    idempotencyKey = payload.idempotencyKey
)
```

Pro: property syncs successfully. Con: may mislabel type — acceptable since the user can edit it, and it beats silent loss.

### Option B — Make the field nullable and block until set

Change `propertyTypeId: Int?`. If null/0 at push time, return `OperationOutcome.SKIP` (keep in queue) and prompt the user to choose a type. Pro: no wrong default. Con: needs UI to resolve the pending property.

### Option C — Surface the 422 instead of silent DROP

Keep current send behavior but on 422 record a user-visible failure (notification / sync-error list) so the user knows the property wasn't created and can retry.

Recommend **A** for immediate data-loss prevention, optionally combined with **C** so any *other* 422 cause is still surfaced rather than silently dropped.

## Observability

- Console log `property_type_id_zero_sync_blocked` (`property_id`, `property_uuid`) when the guard fires.
- Upgrade the existing `Property creation dropped - 422` from a quiet WARN to a surfaced error category `property_creation_validation_failure` so silent drops become visible.

## Test Plan

- [ ] Unit: payload with `propertyTypeId = 0` → with Option A, request sends `propertyTypeId = 1` and succeeds; never sends `0`.
- [ ] Unit: payload with a valid 1–5 id → unchanged pass-through.
- [ ] Unit (Option C): a genuine 422 (other cause) records a surfaced error rather than a silent DROP.
- [ ] Manual QA:
  1. Create a property offline without selecting a type (if such a path exists in the UI).
  2. Sync.
  3. Expected: property is created on the server (Option A) or the user is told it needs a type (Option B) — never silently lost.

## Rollback Plan

Revert the handler edits (and the `SyncPayloads` field change if Option B). No schema change.

## Dependencies

- Requires: Product decision on Option A vs B (does an unset-type property path actually exist in the current UI?). Confirm before implementing — if no UI path can produce `0`, this becomes a defensive guard only and Option A is the cheapest safety net.
- Blocking: none.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-034] Offline-created properties with no type set no longer vanish on sync; they default to a valid type (or surface an error) instead of being silently dropped on a 422.
```
