# RP-FR-030 — Placement date-correction (Android)

**Bug ID:** RP-FR-030
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-030_no_placement_date_correction_2026-07-18.md) · Plan (this doc) · parent [RP-FR-019 plan](plan_rp_fr_019_serialized_equipment_2026-07-13.md) · iOS `SerializedPlacementEditView` / `correctPlacement`
**Depends on:** placement-history UI ([RP-FR-028]) for the entry point.

**Status:** planned · **Branch:** stack on `feat/RP-FR-019-serialized-equipment`. **Endpoint-dependent** — includes a Retrofit + sync-handler change.

## Objective

Let a user correct a placement's `date_in`/`date_out` after the fact, so room-occupancy / drying-duration reporting can be fixed. Android has neither the endpoint nor UI today.

## ✅ Backend contract (VERIFIED 2026-07-18 against `mongoose` branch `dev`)

Route exists: `routes/api/equipment-assets.php:22` — `PATCH /api/equipment-asset-placements/{placement}` → `EquipmentAssetPlacementController@update`.

**Request** (`UpdateEquipmentAssetPlacementRequest`):
| field | rule |
|---|---|
| `date_in` | optional, `date` |
| `date_out` | optional, nullable, `date` (must be ≥ effective `date_in`) |
| `updated_at` | **required**, `date` — optimistic lock on the **placement's own** `updated_at` (NOT the asset's — differs from move/check-out) |
| `idempotency_key` | optional, string, ≤64 |

- At least one of `date_in`/`date_out` must be present (else 422).
- Does **not** reopen a closed placement and does **not** change its room.
- Invariants: `date_out >= date_in`, no overlap with the asset's other placements.

**Responses:** 200 `{data: EquipmentAssetPlacementResource}` · 403 (flag off / cross-company) · 404 · 409 `{message, current_updated_at, resource_id}` (stale) · 422 (invalid/overlapping dates).

## Agent kickoff (paste to a coding agent opened in this repo)

```
Implement RP-FR-030 — "Placement date-correction" — from this repo's tracker.

Read first, in full:
  - docs/plans/plan_rp_fr_030_placement_date_correction_2026-07-18.md  (this plan — contract is verified inside)
  - app/.../data/repository/sync/handlers/EquipmentAssetPlacementPushHandler.kt  (MIRROR handleMove: lock, 409 extractUpdatedAt, 422 resolve, idempotencyKeyOf)
  - app/.../data/repository/sync/SyncQueueProcessor.kt lines ~417-431, ~965-1030  (dispatch + enqueuers + isEntityDeleted)
  - app/.../data/model/offline/EquipmentAssetDtos.kt + EquipmentAssetMappers.kt  (request-DTO + mapper patterns)
  - docs/plans/plan_rp_fr_028_placement_history_ui_2026-07-18.md  (the screen this hangs off)

CONTRACT DISCIPLINE (CLAUDE.md): the PATCH route/body is verified in this plan against mongoose
`dev` as of 2026-07-18 — re-confirm it is still present before wiring, and add a golden-fixture
parse test from the backend Resource shape. Do NOT invent fields.

IMPORTANT wiring constraint: `equipment_asset_placement` already uses all three SyncOperationTypes
(CREATE=deploy, UPDATE=move, DELETE=check-out). Correction MUST use a NEW entityType
("equipment_asset_placement_correction") with op UPDATE — do NOT overload the existing one.

Gates (gradle in background):
  ./gradlew compileDevStandardDebugKotlin && ./gradlew testDevStandardDebugUnitTest && ./gradlew assembleDevStandardDebug
```

## Implementation steps

1. **API** — add to `OfflineSyncApi.kt` (serialized block ~:524):
   ```kotlin
   @PATCH("/api/equipment-asset-placements/{placementId}")
   suspend fun correctEquipmentPlacement(
       @Path("placementId") placementId: Long,
       @Body body: CorrectPlacementRequest
   ): SingleDataResponse<EquipmentAssetPlacementDto>
   ```
2. **Request DTO** — add `CorrectPlacementRequest(dateIn: String?, dateOut: String?, updatedAt: String, idempotencyKey: String?)` to `EquipmentAssetDtos.kt` with explicit `@SerializedName("date_in"/"date_out"/"updated_at"/"idempotency_key")` (RP-CD-006 — the RP-FR-024 lesson; annotate all four). Dates as ISO strings via `toApiTimestamp()`.
3. **Mapper** — `OfflineEquipmentPlacementEntity.toCorrectPlacementRequest(dateIn, dateOut, lockUpdatedAt, idempotencyKey)` in `EquipmentAssetMappers.kt`.
4. **Op-type wiring** (new entityType — see constraint):
   - `SyncQueueProcessor` dispatch `when(entityType)`: add `"equipment_asset_placement_correction" -> handleOperation(...) { equipmentPlacementHandler.handleCorrect(operation).toLocal() }` (single op = UPDATE).
   - `isEntityDeleted` (~:162): treat like `equipment_asset_placement` (resolve the placement by uuid).
   - Add `enqueuePlacementCorrection(placement, lockUpdatedAt)` to the `SyncQueueEnqueuer` impl (~:1022 pattern) with `entityType = "equipment_asset_placement_correction"`, `operationType = UPDATE`, persisting a `PendingLockPayload`-style idempotency key.
5. **Handler** — add `handleCorrect(operation)` to `EquipmentAssetPlacementPushHandler`, MIRRORING `handleMove`, but:
   - lock on the **placement's own** `serverUpdatedAt ?: updatedAt` (NOT the asset's);
   - SKIP-until-ready if `placement.serverId == null` (can't correct a placement the server hasn't seen);
   - on 200, save the returned placement (adopt local identity via `toEntity(existing=placement, …)`), `preserveDirty=false` (server is authoritative for this write);
   - 409 → `extractUpdatedAt` + record conflict + `CONFLICT_PENDING` (reuse `recordAssetConflict` shape, but scope the conflict to the placement);
   - 422 → drain body + resolve local row → FAILED/clean (reuse `resolve422`).
6. **Service + repo** — add `EquipmentAssetSyncService.correctPlacement(placementLocalId, dateIn, dateOut)` (transaction: stage the placement dirty with the new dates + enqueue) and `OfflineSyncRepository.correctPlacementOffline(...)`.
7. **UI** — add an "edit dates" affordance to the placement-history rows ([RP-FR-028]) opening a date pickers dialog (date_in / date_out). Treat dates as **date-only in the capture zone** to avoid the off-by-one iOS fixed in RP-BUG-345.
8. **Golden fixture + parse test** — `app/src/test/resources/fixtures/equipment_assets/placement_correction_response.json` from the backend Resource shape + a parse test asserting `EquipmentAssetPlacementDto` deserializes it.

## RP-CD rules touched

- **API Contract Discipline** — verified route/body; golden fixture required.
- **RP-CD-005** — don't drain the 409 body before `extractUpdatedAt` (mirror `handleMove`).
- **RP-CD-004** — return `OperationOutcome`, never throw.
- **RP-CD-006** — explicit `@SerializedName` on the new request DTO.
- **RP-CD-019** — remote-log the terminal 422 with the drained body.

## Tests

- Handler unit test (mirror move tests): 200 adopts server dates; 409 records conflict + `CONFLICT_PENDING`; 422 → FAILED/clean + DROP; SKIP when `placement.serverId == null`.
- Mapper test: `toCorrectPlacementRequest` emits snake_case fields + the placement's lock token.
- Golden-fixture parse test (step 8).
- Idempotency: the key is stable across retries (same op) and distinct per op (reuse `idempotencyKeyOf`).

## Observability

- **Add:** WARN remote log on correction 409 / terminal 422 (with body) and on unexpected-error RETRY, mirroring the existing placement-handler logs (`SYNC_TAG`).
- **Success:** correcting date_in/date_out round-trips, survives offline replay, does not shift by a day across timezones, and an overlapping/backwards date is rejected with a clear message.

## Out of scope

Deleting a placement ([RP-FR-031]). Changing a placement's room (not supported by the endpoint — use move).
