# RP-FR-031 — Placement delete (Android)

**Bug ID:** RP-FR-031
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-031_no_placement_delete_2026-07-18.md) · Plan (this doc) · parent [RP-FR-019 plan](plan_rp_fr_019_serialized_equipment_2026-07-13.md) · iOS `deletePlacement`
**Depends on:** placement-history UI ([RP-FR-028]) for the entry point. Pairs with [RP-FR-030] (same handler/DTO neighbourhood — do them together).

**Status:** planned · **Branch:** stack on `feat/RP-FR-019-serialized-equipment`. **Endpoint-dependent.**

## Objective

Let a user delete an erroneous **closed** placement record (e.g. a wrong-room deploy that was immediately corrected), so history/reporting isn't polluted. Android has neither the endpoint nor UI today.

## ✅ Backend contract (VERIFIED 2026-07-18 against `mongoose` branch `dev`)

Route exists: `routes/api/equipment-assets.php:24` — `DELETE /api/equipment-asset-placements/{placement}` → `EquipmentAssetPlacementController@destroy`.

- **Soft-delete** (history recoverable server-side). No request body.
- **Only CLOSED placements are deletable:** deleting the ACTIVE placement (where `date_out === null`) returns **422** — "Cannot delete an active placement. Check the unit out first." (End an active placement via the check-out flow, not delete.)
- **Responses:** 204 (success) · 403 (flag off / cross-company) · 404 · 422 (active placement).

## Agent kickoff (paste to a coding agent opened in this repo)

```
Implement RP-FR-031 — "Placement delete" — from this repo's tracker.

Read first, in full:
  - docs/plans/plan_rp_fr_031_placement_delete_2026-07-18.md  (this plan — contract verified inside)
  - docs/plans/plan_rp_fr_030_placement_date_correction_2026-07-18.md  (sibling — same wiring pattern; do together)
  - app/.../data/repository/sync/handlers/EquipmentAssetPlacementPushHandler.kt  (MIRROR resolve422 / logging / SKIP-until-ready)
  - app/.../data/repository/sync/SyncQueueProcessor.kt lines ~417-431, ~965-1030, ~162
  - app/.../data/repository/sync/EquipmentAssetPullService.kt  (reconcileAssetPlacements + markReconciledDeleted — don't let a delete get resurrected)

CONTRACT DISCIPLINE (CLAUDE.md): DELETE route verified against mongoose `dev` 2026-07-18 —
re-confirm before wiring.

WIRING CONSTRAINT: `equipment_asset_placement` already uses all three SyncOperationTypes
(CREATE/UPDATE/DELETE = deploy/move/check-out). Placement delete MUST use a NEW entityType
("equipment_asset_placement_delete") with op DELETE — do NOT overload check-out's DELETE.

Gates (gradle in background):
  ./gradlew compileDevStandardDebugKotlin && ./gradlew testDevStandardDebugUnitTest && ./gradlew assembleDevStandardDebug
```

## Implementation steps

1. **API** — add to `OfflineSyncApi.kt`:
   ```kotlin
   @DELETE("/api/equipment-asset-placements/{placementId}")
   suspend fun deleteEquipmentPlacement(@Path("placementId") placementId: Long): retrofit2.Response<Unit>
   ```
   (Mirror `retireEquipmentAsset`'s 204 handling.)
2. **Op-type wiring** (new entityType):
   - Dispatch: add `"equipment_asset_placement_delete" -> handleOperation(...) { equipmentPlacementHandler.handleDeletePlacement(operation).toLocal() }` (single op = DELETE).
   - `isEntityDeleted` (~:162): resolve the placement by uuid like `equipment_asset_placement`.
   - Enqueuer: `enqueuePlacementDelete(placement)` with `entityType = "equipment_asset_placement_delete"`, `operationType = DELETE`.
3. **Handler** — add `handleDeletePlacement(operation)` to `EquipmentAssetPlacementPushHandler`:
   - resolve placement by uuid; if already `isDeleted` and `serverId == null` → DROP; if `serverId == null` → SKIP (server never saw it — but also collapse locally if the create is still PENDING, mirroring check-out's compaction);
   - mode gate on the asset's company (`ctx.serializedModeFor(...) != ON` → SKIP);
   - **client-side guard:** refuse to send when the placement is still open (`isOpen`/`dateOut == null`) — that's a check-out, not a delete; resolve locally as a no-op/DROP with a WARN (the backend would 422 anyway);
   - on 204 → mark the local row `markReconciledDeleted()` (reuse the pull's helper shape: `isDeleted=true, isOpen=false, SYNCED`) → SUCCESS;
   - 404/410 → treat as already-deleted → mark deleted + SUCCESS;
   - 422 (active placement) → drain body, resolve local row, DROP + WARN;
   - else → RETRY with WARN.
4. **Pull safety** — confirm `reconcileAssetPlacements` won't resurrect a locally-deleted placement: a soft-deleted row must not be re-inserted by a subsequent pull. If the server still returns the (soft-deleted) row, keep it deleted locally; verify `toEntity(existing=…)` preserves `isDeleted` or add a guard.
5. **Service + repo** — `EquipmentAssetSyncService.deletePlacement(placementLocalId)` (transaction: mark the local placement deleted + enqueue) and `OfflineSyncRepository.deletePlacementOffline(placementLocalId)`.
6. **UI** — swipe-to-delete or an overflow "Delete" on **closed** rows only in the placement-history screen ([RP-FR-028]), with a confirmation dialog (mirror `confirmRetire`). Hide/disable delete on the open placement.

## RP-CD rules touched

- **API Contract Discipline** — verified route; no request body to fixture, but assert the 204/422 handling in a handler test.
- **RP-CD-004** — return `OperationOutcome`, never throw.
- **RP-CD-005/019** — drain the 422 body for diagnosis + remote-log it.
- **RP-CD-016/BUG-334** — don't let the delete get resurrected by an authoritative pull.

## Tests

- Handler unit test: 204 → local row marked deleted + SUCCESS; 404 → already-deleted + SUCCESS; 422 active → DROP + WARN; open-placement guard → no network call; SKIP when `serverId == null` (unsynced).
- Pull test: a soft-deleted placement is not resurrected when the server list still contains it (or confirms it's gone).

## Observability

- **Add:** WARN remote log on 422 (active) with body, and on unexpected-error RETRY (`SYNC_TAG`).
- **Success:** deleting a closed placement removes it and it does not reappear after a pull; deleting the open placement is blocked client-side with a clear message.

## Out of scope

Correcting dates ([RP-FR-030]). Hard-delete / un-delete (server soft-deletes; no client un-delete).
