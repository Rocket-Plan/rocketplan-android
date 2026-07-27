# RP-FR-027 — Per-unit serialized asset detail screen (Android)

**Bug ID:** RP-FR-027
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-027_no_asset_detail_screen_2026-07-18.md) · Plan (this doc) · parent [RP-FR-019 plan](plan_rp_fr_019_serialized_equipment_2026-07-13.md) · iOS `SerializedAssetDetailView`

**Status:** planned · **Branch:** stack on `feat/RP-FR-019-serialized-equipment`. Best sequenced **after** [RP-FR-026] (pool) navigates into it, and pairs with [RP-FR-029] (edit) + [RP-FR-028] (history) as the actions it hosts.

## Objective

A dedicated per-unit screen showing full metadata + current placement + all lifecycle actions in one place, with an optimistic-lock (409) refresh on conflict. Today lifecycle actions are only inline on room-list rows; the rich DTO metadata is fetched/stored but never shown.

## What already exists (verified 2026-07-18)

- **Read endpoint:** `OfflineSyncApi.getEquipmentAsset(assetId)` → `GET /api/equipment-assets/{assetId}` (`OfflineSyncApi.kt:550`).
- **Local single-row read:** `LocalDataService.getEquipmentAsset(assetId)` (`:1259`); open placement via `getOpenPlacementForAsset(assetId)` (`:1330`). **No single-asset Flow yet** — see step 2.
- **All DTO fields present** on `OfflineEquipmentAssetEntity`: manufacturer/model/serialNumber/assetTag/vendor/note/purchaseDate/purchasePrice/warrantyExpiresAt/rentalDayRate/isStandard/status.
- **Lifecycle actions** (repo, `OfflineSyncRepository.kt:1219-1234`): `deployEquipmentAssetOffline`, `moveEquipmentAssetOffline`, `checkOutEquipmentAssetOffline`, `retireEquipmentAssetOffline`. Edit is [RP-FR-029].
- **Room-fragment action dialogs** (move/retire/register) to mirror: `SerializedRoomEquipmentFragment.kt:119-166`.

## Agent kickoff (paste to a coding agent opened in this repo)

```
Implement RP-FR-027 — "Serialized asset detail screen" — from this repo's tracker.

Read first, in full:
  - docs/plans/plan_rp_fr_027_asset_detail_screen_2026-07-18.md  (this plan)
  - docs/investigations/RP-FR-027_no_asset_detail_screen_2026-07-18.md
  - app/.../ui/rocketdry/SerializedRoomEquipmentFragment.kt + ViewModel  (MIRROR structure + action dialogs)
  - app/.../data/repository/OfflineSyncRepository.kt lines ~1206-1238  (the offline action methods)

This is a UI build. The one non-UI addition allowed is a single-asset observe Flow (step 2).
Do NOT add DTOs, API methods, sync handlers, or migrations.

Gates (run gradle in background per CLAUDE.md):
  ./gradlew compileDevStandardDebugKotlin && ./gradlew testDevStandardDebugUnitTest && ./gradlew assembleDevStandardDebug
```

## Implementation steps

1. **Layout** `res/layout/fragment_serialized_asset_detail.xml` — header (name, status chip), a metadata section (manufacturer, model, serial, asset tag, vendor, purchase date/price, warranty, rental rate, note — hide blank rows), a "current placement" row (room/project/date_in via `getOpenPlacementForAsset`), and an actions area (Deploy/Check-in, Move, Check-out, Retire, Edit, View history). Loading + Unavailable states as in the room fragment.
2. **Single-asset Flow (small DAO/LDS addition):** add `@Query("SELECT * FROM offline_equipment_assets WHERE assetId = :assetId LIMIT 1") fun observeEquipmentAsset(assetId: Long): Flow<OfflineEquipmentAssetEntity?>` to `OfflineDao.kt` (mirror `observeEquipmentAssetsForCompany`) + a `LocalDataService.observeEquipmentAsset(assetId)` passthrough. (Alternatively derive from `observeEquipmentAssetsForCompany(companyId).map { it.firstOrNull { a -> a.assetId == id } }` — but a keyed query is cleaner.)
3. **ViewModel** `ui/rocketdry/SerializedAssetDetailViewModel.kt` — `provideFactory(application, assetLocalId)`; combine `observeEquipmentAsset(assetLocalId)` + `observeOpenPlacementForAsset` (add an observe variant of `getOpenPlacementForAsset`, or derive from `observePlacementsForAsset(assetId)` filtering `isOpen`). Expose Ready(asset, currentPlacement) / Loading / NotFound. Actions delegate to the repo offline methods and emit `events` (toasts) exactly like the room VM's `runAction`.
4. **Fragment** `ui/rocketdry/SerializedAssetDetailFragment.kt` — mirror the room fragment: render, action buttons → dialogs (reuse the move/retire dialog code; deploy/check-in needs a room picker — reuse `roomChoices()`), 409/refresh handled by the sync layer (the row updates reactively as the pull/handler writes back).
5. **Navigation** — add `<fragment android:id="@+id/serializedAssetDetailFragment">` with an `assetLocalId` long arg to `mobile_navigation.xml`, and an action into it from the pool ([RP-FR-026]) and from the room list rows (add a "Details" affordance/primary tap on `SerializedEquipmentAdapter`). Rebuild for safe-args.
6. **Strings** — add detail labels + action labels (reuse existing `serialized_equipment_*` where possible).

## RP-CD rules touched

- No sync/pull changes → no new RP-CD-002/014/016 surface. Keep reads offline-first (Room Flow), not direct API.

## Tests

- ViewModel unit test: Ready renders all populated fields + current placement; NotFound when the row is absent/deleted; each action calls the right repo method (fake repo) and surfaces failure via `events`.

## Observability

- **Add:** remote log (WARN) on any action failure surfaced to the user (reuse RP-HD-008 category). No new pull failure surface.
- **Success:** opening a unit shows all metadata + current placement; every action works; a concurrent server edit reflects as a reactive row update (via the existing 409-recovery in the handlers), not a stale screen.

## Out of scope

Edit form ([RP-FR-029]) — this screen only links to it. History screen ([RP-FR-028]) — linked, built separately. Placement correction/delete ([RP-FR-030]/[RP-FR-031]).
