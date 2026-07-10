**Bug ID(s):** RP-FR-018
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Plan](./plan_rp_fr_018_equipment_move_and_tracking_2026-07-09.md) · Review: pending · **Depends on:** [RP-BUG-279](./plan_rp_bug_279_equipment_writesync_pivot_realignment_2026-07-09.md) · Backend spec: `mongoose:MONGOOSE-FR-014` · Siblings: iOS `RP-FR-023`, Webapp `WEBAPP-FR-004`

# Feature Plan: [RP-FR-018] Equipment Move & Tracking (Android client)

**Bug ID(s):** RP-FR-018
**Author:** Claude
**Date:** 2026-07-09 (registered 2026-07-09 15:12:04 PDT)
**State:** planned
**Priority:** P2
**Type:** feature · **Release:** unreleased

---

## Summary

Consume the backend **Equipment Move & Tracking** capability (canonical spec: `mongoose:MONGOOSE-FR-014_equipment_move_and_tracking_2026-07-09.md`) on Android: let a user **move equipment between rooms** (partial quantity supported), **transfer between projects**, and view a **movement history**. The design is **count-based** (quantity, not serialized assets), backed by a **full movement ledger**, and **strictly additive** — no existing endpoint, field, or offline op changes shape.

**Hard dependency:** this builds on the pivot (`equipment_room`) model. Android's equipment write-sync currently targets non-existent routes and must be fixed first as **RP-BUG-279**. Move cannot be layered onto the broken flat-row model. This plan assumes RP-BUG-279 has landed (catalog/pivot split in `OfflineEquipmentEntity`, `serverId` = pivot id, reads from `GET /api/rooms/{id}/equipment`).

## Backend endpoints to consume (new — all additive, exist after MONGOOSE-FR-014 ships)

| Intent | Call |
|--------|------|
| Move within project (room→room, partial qty) | `POST /api/equipment-rooms/{id}/move` — body `to_room_id`, `quantity?` (default full), `moved_at`, `note?`, `idempotency_key?`, `updated_at` (optimistic lock on source pivot) |
| Transfer across projects (project→project) | `POST /api/equipment-rooms/{id}/transfer` — body `to_room_id` (in destination project), `quantity`, `moved_at`, `idempotency_key?`, `updated_at`; server rebinds catalog by `catalog_uuid`/name |
| History for one placement | `GET /api/equipment-rooms/{id}/movements` |
| Project-wide movement log | `GET /api/projects/{project}/equipment-movements` (filters: `equipment_id`, `to_room_id`, `moved_at` range) |

`EquipmentDto`/`EquipmentRoomResource` gain additive nullable fields `location_id`, `last_moved_at`, `current_room_id`. **Gson is lenient** — new keys are safe on old builds; adding them to `EquipmentDto` (`data/model/offline/OfflineDtos.kt:653`) is backward-compatible.

## Affected Code

| File | Change |
|------|--------|
| `data/local/SyncEnums.kt:37` (`SyncOperationType`) | Add `MOVE` and `TRANSFER` op types (see Step 1 — the enum currently has only `CREATE/UPDATE/DELETE`). |
| `data/api/OfflineSyncApi.kt` | Add `moveEquipmentRoom` (`POST /api/equipment-rooms/{id}/move`), `transferEquipmentRoom` (`POST /api/equipment-rooms/{id}/transfer`), `getEquipmentRoomMovements`, `getProjectEquipmentMovements`. |
| `data/model/offline/OfflineDtos.kt` | New `EquipmentMoveRequest`, `EquipmentTransferRequest`, `EquipmentMovementDto`. Extend `EquipmentDto` (`:653`) with nullable `location_id`, `last_moved_at`, `current_room_id`. |
| `data/repository/sync/handlers/EquipmentPushHandler.kt` | New `handleMove` / `handleTransfer` branches (after RP-BUG-279's rewrite). |
| `data/repository/sync/SyncQueueProcessor.kt:397` | Extend the `"equipment"` dispatch `when (operation.operationType)` to route `MOVE`/`TRANSFER`. |
| `data/repository/sync/SyncQueueEnqueuer.kt:154` | Add `enqueueEquipmentMove(...)` / `enqueueEquipmentTransfer(...)`. |
| `data/repository/sync/IdRemapService.kt:173` + `data/local/dao/OfflineDao.kt:801` | **Remap coverage** for the new op payloads (see Step 4 — critical). |
| `ui/rocketdry/EquipmentRoomFragment.kt`, `ui/rocketdry/TotalEquipmentFragment.kt` | Move action (destination room picker; project picker for cross-project; partial-qty input); movement-history view. |

## Implementation Notes

### Step 1: New sync op types + payload

Movement is not an upsert of a pivot's fields — it is a distinct server action (`/move`, `/transfer`) that mutates two pivots and writes a ledger row. Model it as its own op type rather than overloading `UPDATE`:

```kotlin
enum class SyncOperationType {
    CREATE, UPDATE, DELETE,
    MOVE,       // POST /api/equipment-rooms/{id}/move
    TRANSFER;   // POST /api/equipment-rooms/{id}/transfer
    // fromName() default stays UPDATE
}
```

The op payload (stored on `OfflineSyncQueueEntity.payload`) carries: source pivot `serverId` (or local ref until synced), **destination `roomId`** (local until the destination room syncs), `quantity`, `movedAt`, optional `note`, `idempotencyKey`, and the source `updated_at` lock. For transfer it also carries the source `projectId` and destination `projectId` (server rebinds the catalog by `catalog_uuid`/name — Android just supplies `to_room_id`).

### Step 2: Handler branches

Add to `EquipmentPushHandler` (built on the RP-BUG-279 pivot rewrite):

- `handleMove(operation)`: resolve source pivot `serverId` and **destination** server `roomId`; `POST /api/equipment-rooms/{pivotId}/move`. On success, apply the local effect: if full-qty move, update the local row's `roomId`; if partial, decrement source `quantity` and upsert a destination placement (mirror server semantics from MONGOOSE-FR-014 §5.1) using the returned `EquipmentRoomResource`(s). Reuse the existing 409 recovery (`handle409Conflict`, `extractUpdatedAt` — `SyncHandlerUtils.kt:83`) since move honors the same optimistic lock.
- `handleTransfer(operation)`: `POST /api/equipment-rooms/{pivotId}/transfer` with destination `to_room_id`. The destination pivot is bound to the **destination project's** catalog server-side; locally, reconcile the returned pivot the same way RP-BUG-279 Step 5 reconciles pulled pivots (by `uuid`/`equipment_id`).
- Wire both into the processor dispatch at `SyncQueueProcessor.kt:397`:

```kotlin
"equipment" -> handleOperation(operation, "pending:equipment") {
    when (operation.operationType) {
        SyncOperationType.CREATE, SyncOperationType.UPDATE -> equipmentHandler.handleUpsert(operation).toLocal()
        SyncOperationType.DELETE   -> equipmentHandler.handleDelete(operation).toLocal()
        SyncOperationType.MOVE     -> equipmentHandler.handleMove(operation).toLocal()
        SyncOperationType.TRANSFER -> equipmentHandler.handleTransfer(operation).toLocal()
    }
}
```

### Step 3: Parent-gating on destination room / project

A move can be queued before the **destination** room (or, for transfer, destination project) has a server id. The handler must `SKIP` (defer, do not drop) until the destination `roomId`/`projectId` resolves to a server id — mirror the existing source-side waits (`EquipmentPushHandler.kt:28-56`). Do not send a move to a local (negative) destination room id.

### Step 4: Remap coverage for the new op type (critical — do not miss)

When a room or project is created offline and later gets its server id, `IdRemapService` rewrites pending child operations. Today the equipment remap is `migrateEquipmentRoomIds` (`OfflineDao.kt:801`, called from `IdRemapService.remapRoomId` at `:188`), which rewrites the **entity table** `roomId`, plus the roomId/projectId payload-rewrite paths for queued ops. The new `MOVE`/`TRANSFER` ops embed a **destination `to_room_id`** (and, for transfer, a destination `project_id`) **inside the op payload** — these will NOT be covered by the existing entity-column migration or the current per-entity-type payload remap unless explicitly added.

**Action:** extend the remap paths so that when the destination room/project syncs, any pending `MOVE`/`TRANSFER` op payload has its `to_room_id`/destination `project_id` rewritten from local → server id (same pattern as the location-remap payload rewrite at `IdRemapService.kt:150-157`). Add a test that a queued move to an offline-created destination room is remapped after that room syncs. Missing this is the classic "op sent to a negative id → 404/422 → dropped" failure this feature must avoid.

### Step 5: Optimistic locking & conflict machinery

Move/transfer send `updated_at` on the source pivot; the backend `assertNotStale` returns 409 on a stale lock. Reuse the existing 409 conflict recovery already in `EquipmentPushHandler` (`handle409Conflict` `:127`, `extractUpdatedAt` `SyncHandlerUtils.kt:83`) and the delete stale-retry helper (`resolveDeleteWithStaleRetry` `:28`). This machinery is shared with RP-FR-002 (409 body-drain ordering — `RP-CD-005`), RP-BUG-037/038 (serverId reconcile on pull), and RP-BUG-040 (delete stale-timestamp 409). **Confirm all three are released** (currently `unreleased` in the tracker) before RP-FR-018 ships, since Move relies on their conflict/reconcile behavior.

### Step 6: UI

- **Move action** in `EquipmentRoomFragment.kt` (per-placement) and `TotalEquipmentFragment.kt` (project roll-up): a destination **room picker** (rooms in the current project), a **project picker** shown only for cross-project transfer, and a **partial-quantity** input defaulting to the placement's full quantity. Enqueue via the new `enqueueEquipmentMove`/`enqueueEquipmentTransfer` (offline-first — the UI updates optimistically from the local effect in Step 2).
- **Movement history view**: read `GET /api/equipment-rooms/{id}/movements` (per placement) and/or `GET /api/projects/{project}/equipment-movements` (project log), rendered as a chronological list (from/to room, quantity, `moved_at`, note, user). History is a **read** — no offline write path needed for v1; show cached/last-fetched with a refresh.

### Step 7: Backward compatibility

- Gson lenient → additive `EquipmentDto` fields (`location_id`, `last_moved_at`, `current_room_id`) are safe; unknown keys never break decoding.
- **Never stop sending** the existing `id`/`project_id`/`room_id` — `room_id` stays the current-placement value (the pivot's authoritative current room). The ledger is additive provenance, not a replacement for `room_id`.
- Old clients (pre-RP-FR-018) keep working: they never call `/move` or `/transfer`; their delete-then-re-add "move" still functions (just writes no ledger row). This mobile client must interoperate with those.

## Observability

- Remote WARN on a move/transfer deferred past a bounded number of parent-gating skips (surfaces a destination room/project that never syncs): category `equipment_move_parent_unresolved`, fields `equipmentUuid`, `toRoomId`, `attempts`.
- Remote WARN on a move 409 that exhausts conflict recovery: `equipment_move_conflict_unresolved`.
- Local `Log.debug` for the per-move happy path. Follow the tracker's remote-logging guidance (terminal/actionable states only, no raw user text).

## Test Plan

- [ ] Unit: `handleMove` full-qty → local `roomId` updated; partial-qty → source decremented + destination placement upserted (matches server semantics).
- [ ] Unit: `handleTransfer` reconciles the returned destination pivot by `uuid`/`equipment_id` (no duplicate local rows).
- [ ] Unit: move/transfer to an **offline-created** destination room `SKIP`s until the room syncs, then sends the remapped server `to_room_id` (Step 4 remap coverage).
- [ ] Unit: move 409 stale-lock → conflict recovery path (parity with the existing equipment upsert 409 test).
- [ ] Unit: `SyncQueueProcessor` routes `MOVE`/`TRANSFER` op types (dispatch at `:397`); `SyncOperationType.fromName` still defaults unknown → `UPDATE`.
- [ ] Integration: enqueue move offline → go online → placement moves on server + ledger row written + reflected on iOS/webapp.
- [ ] Manual QA: partial move (e.g. 2 of 3 dehumidifiers) splits the placement; cross-project transfer rebinds the catalog and shows in the destination project; movement history lists the move.

## Rollback Plan

Feature is strictly additive. Rollback = stop enqueuing MOVE/TRANSFER ops (feature-flag the UI action off) and/or revert the handler branches; queued move ops can be drained as no-ops or converted. No schema destruction beyond the additive `EquipmentDto` fields (safe to leave). Because move/transfer are new server actions, disabling them leaves the delete-then-re-add fallback intact.

## Dependencies

- **Depends on:** **RP-BUG-279** (pivot/catalog write-sync realignment) — hard prerequisite; move is layered on the pivot model. RP-BUG-279 in turn is independent of the backend move work (the pivot endpoints already exist).
- **Requires:** backend **MONGOOSE-FR-014** `/move`, `/transfer`, `/movements` endpoints + additive resource fields deployed (backend ships first per §7 rollout).
- **Confirm released before ship:** RP-FR-002, RP-BUG-037, RP-BUG-040 (shared conflict/reconcile machinery — currently `unreleased`).
- **Siblings:** iOS `RP-FR-023`, Webapp `WEBAPP-FR-004`.

## Changelog Entry

```markdown
### Added
- [RP-FR-018] Move equipment between rooms (including partial quantities) and transfer it between projects from Android, with a full movement history. Requires the equipment sync fix (RP-BUG-279).
```
