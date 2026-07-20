# RP-FR-029 — Serialized asset edit/update (Android)

**Bug ID:** RP-FR-029
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-029_asset_edit_unreachable_2026-07-18.md) · Plan (this doc) · parent [RP-FR-019 plan](plan_rp_fr_019_serialized_equipment_2026-07-13.md) · iOS `SerializedEditView`

**Status:** planned · **Branch:** stack on `feat/RP-FR-019-serialized-equipment`. Hosted from the asset detail screen ([RP-FR-027]).

## Objective

Let a user correct a registered unit's editable metadata — `serial_number`, `asset_tag`, `status` (available ↔ maintenance), `note`, and the other `PUT`-updatable fields. Today edit is **completely unreachable**: the sync path exists but nothing invokes it.

## What already exists (verified 2026-07-18 — this is a thin wiring + UI job)

The whole sync spine for UPDATE is already built:

- **Write orchestrator:** `EquipmentAssetSyncService.updateAsset(asset)` (`EquipmentAssetSyncService.kt:76`) — stages the dirty row in a transaction, computes the optimistic-lock `updated_at` from `serverUpdatedAt ?: updatedAt`, and enqueues via `enqueueEquipmentAssetUpsert(updated, lockUpdatedAt)`.
- **Push handler:** `EquipmentAssetPushHandler` dispatches UPDATE → `OfflineSyncApi.updateEquipmentAsset(serverId, asset.toUpdateRequest(lock))` → `PUT /api/equipment-assets/{id}` (`OfflineSyncApi.kt:555`), with 409 recovery (`extractUpdatedAt`) and conflict recording.
- **Pull merge:** `EquipmentAssetPullService.adoptOrMerge` already preserves a dirty metadata edit against an authoritative pull, and only adopts server `status` when it is in `EDITABLE_STATUSES = {available, maintenance}` (`EquipmentAssetPullService.kt:36,227`).
- **Mapper:** `toUpdateRequest(lockUpdatedAt)` on the entity (in `EquipmentAssetMappers.kt`).

**The only two gaps:** (1) no repo entry point exposing `updateAsset`; (2) no edit UI.

## Agent kickoff (paste to a coding agent opened in this repo)

```
Implement RP-FR-029 — "Serialized asset edit/update" — from this repo's tracker.

Read first, in full:
  - docs/plans/plan_rp_fr_029_asset_edit_2026-07-18.md  (this plan)
  - docs/investigations/RP-FR-029_asset_edit_unreachable_2026-07-18.md
  - app/.../data/repository/sync/EquipmentAssetSyncService.kt  (updateAsset ALREADY EXISTS at :76)
  - app/.../data/repository/sync/handlers/EquipmentAssetPushHandler.kt  (UPDATE dispatch + 409)
  - app/.../data/repository/mapper/EquipmentAssetMappers.kt  (toUpdateRequest — confirm the fields it sends)

The UPDATE sync path is already built and tested. You are adding (1) a repo passthrough and
(2) an edit form. Do NOT add a new op type, handler, DTO, or migration.

Gates (gradle in background):
  ./gradlew compileDevStandardDebugKotlin && ./gradlew testDevStandardDebugUnitTest && ./gradlew assembleDevStandardDebug
```

## Implementation steps

1. **Repo entry point** — add to `OfflineSyncRepository.kt` (next to the other serialized writes, ~:1219):
   ```kotlin
   suspend fun updateEquipmentAssetOffline(
       assetLocalId: Long,
       serialNumber: String?,
       assetTag: String?,
       status: String?,          // "available" | "maintenance" only
       note: String?,
       // optional: manufacturer/model/vendor/purchaseDate/purchasePrice/warrantyExpiresAt/rentalDayRate
   ): OfflineEquipmentAssetEntity? {
       val current = localDataService.getEquipmentAsset(assetLocalId) ?: return null
       val edited = current.copy(serialNumber = serialNumber, assetTag = assetTag,
           status = status ?: current.status, note = note /*, …*/)
       return equipmentAssetSyncService.updateAsset(edited)
   }
   ```
   Guard the status arg to `{available, maintenance}` — a lifecycle status (`deployed`/`retired`) must never be set via edit.
2. **Status-vs-placement rule:** the backend returns **422 when changing status while an open placement exists**. Pre-check `getOpenPlacementForAsset(assetId)` in the VM and disable the maintenance toggle (with a "check the unit out first" hint) when a placement is open; the handler's 422 path is the backstop.
3. **Edit UI** `ui/rocketdry/SerializedAssetEditFragment.kt` (or a dialog if you prefer parity with the register dialog) — fields: serial_number, asset_tag, status (available/maintenance segmented control), note (+ optional metadata fields). Prefill from the current row. On save → `updateEquipmentAssetOffline(...)`, toast on reject/failure via an `events` channel.
4. **Navigation** — action into the edit screen from the asset detail screen's "Edit" button; pass `assetLocalId`. Rebuild for safe-args.
5. **Strings** — edit title, field labels, the "check out first" hint, status option labels.

## RP-CD rules touched

- **RP-CD-002** (don't clobber dirty rows) — already honoured by `adoptOrMerge`; do not weaken it.
- **RP-CD-004/005** (OperationOutcome + don't drain 409 body) — already honoured by the handler.
- **RP-CD-014/018** (identity) — unchanged (edit reuses the existing row).

## Tests

- Repo unit test: `updateEquipmentAssetOffline` stages the edit + enqueues an UPDATE op (fake `EquipmentAssetSyncService`/LDS); a `deployed`/`retired` status arg is rejected/ignored.
- ViewModel test: maintenance toggle disabled when an open placement exists; save surfaces handler failure via `events`.
- (Handler UPDATE path already has coverage under `EquipmentAssetPushHandler` tests — extend if the field set changes.)

## Observability

- **Add:** remote log (WARN) on update failure / 409 exhaustion / 422 status-change rejection in the handler if not already present (it logs retries; confirm 422 status-change is logged). Reuse the existing `SYNC_TAG` category.
- **Success:** editing serial/tag/note round-trips; available↔maintenance works when no placement is open and is blocked with a clear message when one is; a concurrent server edit yields a 409 refresh, not a silent clobber.

## Out of scope

Editing placement dates ([RP-FR-030]). Register already exists (register-and-deploy in the room fragment; register-only in [RP-FR-026]).
