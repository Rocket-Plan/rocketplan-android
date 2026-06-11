# Fix Plan: [RP-BUG-046] Moisture-log 422 strands PENDING rows (data loss)

**Bug ID(s):** RP-BUG-046
**Author:** Claude
**Date:** 2026-06-11
**State:** draft

---

## Summary

Offline-created moisture logs that fail to sync with HTTP 422 are **silently dropped**: the queue op
is deleted (`OperationOutcome.DROP`) while the `offline_moisture_logs` row stays `PENDING`/dirty with
a **negative local `materialId`**, so the reading is orphaned and never retried — confirmed data loss
on device (room 6804, material 512922; 4 stranded readings).

Two distinct sub-problems:
1. **The DROP-vs-keep decision** (now the primary bug): a 422 that is actually a *recoverable*
   ordering condition (missing `damage_material_room` link, unreconciled offline material) must not
   be discarded. Plus existing already-stranded `PENDING` rows have no repair path.
2. **The exact backend rule** (still unconfirmed): most likely the route-derived `damage_type`
   drying-eligibility or `room_id`/`damage_material_room` existence check. The 422-body capture is
   already shipped (`MoistureLogPushHandler`), but the 4 stranded logs can't be replayed (no queue
   op) — the rule must be re-captured from a **fresh** reading on the same material/room.

This plan addresses sub-problem 1 (recoverability + repair). Sub-problem 2 stays `investigating`
until a fresh repro names the field; the fix for it forks on the answer.

## Affected Code

| File | Change |
|------|--------|
| `data/repository/sync/handlers/MoistureLogPushHandler.kt` | Replace blanket `OperationOutcome.DROP` on 422 with a **classified** outcome: SKIP/RETRY (bounded) when the row's parent material/room isn't reconciled yet (negative/unresolved `materialId` or `room_id`), DROP only for genuinely invalid input. Keep the body-capture logging already added. |
| `data/sync/SyncQueueProcessor.kt` | Confirm SKIP keeps the op enqueued (parity with other child handlers' SKIP-until-parent-ready). On terminal DROP, also mark the source row so it isn't left silently `PENDING`. |
| `data/local/...MoistureLog DAO/repair` | Add a one-shot **repair sweep**: re-enqueue (or mark error) `offline_moisture_logs` rows that are `PENDING` with no corresponding `offline_sync_queue` op. Run on sync start, like other reconcile sweeps. |
| `MoistureLogPushHandlerTest` (existing) | Extend with the new SKIP/RETRY/DROP classification + repair cases. |

## Implementation Notes

### Step 1: Classify the 422 instead of blind DROP
```kotlin
// MoistureLogPushHandler — on 422
val recoverable = log.materialId <= 0 ||            // offline material not yet reconciled
    parentDamageMaterialRoom(log) == null           // damage_material_room link not synced yet
return if (recoverable) {
    OperationOutcome.SKIP   // retried once parent is ready (matches sibling handlers)
} else {
    // genuinely invalid (e.g. damage_type not drying-eligible): surface, don't silently strand
    markRowError(log, detail)   // a state the UI can show
    OperationOutcome.DROP
}
```
Follows the memory note: **push-time re-resolution + SKIP-until-ready** is the established
parent-id pattern (NOT IdRemapService). Cite **RP-CD** parent-readiness rule in the commit.

### Step 2: Repair sweep for already-stranded rows
A migration-safe startup sweep that finds `syncStatus = PENDING` moisture logs with no queue op and
re-enqueues them (so the 4 current orphans, and any future ones, recover).

### Step 3 (separate, investigating): capture the rule
Author a fresh moisture reading on room 6804 / material 512922 with the diagnostic build; read the
`detail=…` from the 422 body (local + remote `log_entries`). Then:
- `damage_type` not drying-eligible → block the reading client-side on non-drying materials, or fix
  the material→damage-type mapping.
- `room_id` / `damage_material_room` missing → it's the ordering bug → Step 1's SKIP-until-ready is
  the correct fix.

## Test Plan

- [ ] Unit: 422 with unresolved parent → SKIP (op retained); 422 with valid parent + invalid input →
  DROP + row marked error (not silent PENDING).
- [ ] Unit: repair sweep re-enqueues a PENDING row that has no queue op.
- [ ] Manual QA (`scripts/check_sync_duplicates.sh` + DB pull): create offline moisture log on a
  freshly-created offline material → sync → reading reaches server (or defers cleanly), never
  stranded `PENDING` with no op.

## Rollback Plan

The classification is contained to `MoistureLogPushHandler`. Revert to the prior DROP behavior to
roll back (re-introduces the strand, but no new failure mode). The repair sweep is idempotent and
read-mostly; gate it behind a flag if risk is a concern.

## Dependencies

- Step 3 needs a device repro to confirm the backend rule; Steps 1–2 (recoverability + repair) can
  land independently and stop the data loss regardless of which rule fires.
- Related rules: parent-readiness / SKIP-until-ready (see project memory + `RP-CD_rules.md`).

## Changelog Entry

```markdown
## [1.30] - 2026-06-XX

### Fixed
- [RP-BUG-046] Offline moisture readings that 422 due to an unsynced parent material/room are now
  retried instead of being silently dropped; a repair sweep recovers previously-stranded readings.
```
