# RP-FR-028 — Placement-history UI (Android)

**Bug ID:** RP-FR-028
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-028_no_placement_history_ui_2026-07-18.md) · Plan (this doc) · parent [RP-FR-019 plan](plan_rp_fr_019_serialized_equipment_2026-07-13.md) · iOS `SerializedPlacementHistoryView`

**Status:** planned · **Branch:** stack on `feat/RP-FR-019-serialized-equipment`. Reached from the asset detail screen ([RP-FR-027]).

## Objective

Show a unit's placement timeline — the ordered deploy → move → check-out history (which rooms/projects, each `date_in`/`date_out`, open vs closed). The endpoint and local table are already wired and populated; this is a **UI build over an existing Room Flow**.

## What already exists (verified 2026-07-18)

- **Read endpoint:** `OfflineSyncApi.getEquipmentAssetPlacements(assetId)` → `GET /api/equipment-assets/{assetId}/placements` (`OfflineSyncApi.kt:566`).
- **Pull:** `EquipmentAssetPullService.reconcileAssetPlacements` populates + reconciles the local table (with the RP-BUG-334 completeness guard — an empty `{data:[]}` does NOT wipe the open placement).
- **Local reactive read:** `LocalDataService.observePlacementsForAsset(assetId): Flow<List<OfflineEquipmentPlacementEntity>>` (`:1350`, DAO `OfflineDao.kt:895`).
- **Entity fields:** `roomId`, `projectId`, `dateIn`, `dateOut`, `isOpen`, `serverId`, `note`. Room/project display names resolve via `LocalDataService.getRoom(roomId)` / `getProject(projectId)` (or add observe/join for reactive names).

## Agent kickoff (paste to a coding agent opened in this repo)

```
Implement RP-FR-028 — "Placement-history UI" — from this repo's tracker.

Read first, in full:
  - docs/plans/plan_rp_fr_028_placement_history_ui_2026-07-18.md  (this plan)
  - docs/investigations/RP-FR-028_no_placement_history_ui_2026-07-18.md
  - app/.../data/repository/sync/EquipmentAssetPullService.kt  (reconcileAssetPlacements — how history is populated/ordered)
  - app/.../ui/rocketdry/SerializedRoomEquipmentFragment.kt + Adapter  (MIRROR fragment/adapter structure)

Read-only history screen. Do NOT add DTOs, API methods, sync handlers, or migrations.
Editing/deleting placements is RP-FR-030 / RP-FR-031 — NOT this ticket.

Gates (gradle in background):
  ./gradlew compileDevStandardDebugKotlin && ./gradlew testDevStandardDebugUnitTest && ./gradlew assembleDevStandardDebug
```

## Implementation steps

1. **Layout** `res/layout/fragment_serialized_placement_history.xml` — a `RecyclerView` + empty state + Loading/Unavailable (mirror the room fragment). Each row: room name, project name, `date_in` → `date_out` (or "Currently deployed" when `isOpen`), optional note.
2. **Adapter** `ui/rocketdry/PlacementHistoryAdapter.kt` — `ListAdapter` with a `PlacementRowUi(placementId, roomName, projectName, dateRange, isOpen, note)` view-model (mirror `SerializedEquipmentAdapter`). No row actions in this ticket.
3. **ViewModel** `ui/rocketdry/PlacementHistoryViewModel.kt` — `provideFactory(application, assetLocalId)`; map `observePlacementsForAsset(assetLocalId)` → sorted **desc by `dateIn`** (match backend/iOS ordering), resolving room/project display names (batch `getRoom`/`getProject`, or add a reactive join). Expose Ready(rows)/Loading/Empty.
4. **Navigation** — add `<fragment android:id="@+id/serializedPlacementHistoryFragment">` with `assetLocalId` long arg + an action from the asset detail screen ([RP-FR-027], its "View history" button). Rebuild for safe-args.
5. **Strings** — history title, "Currently deployed", date-range formatting via existing `DateUtils`/formatters.
6. **Date display:** render `date_in`/`date_out` consistently with the rest of the app; treat them as date-only where the backend stores date-only (see RP-FR-030 — iOS hit an off-by-one here; format in the same zone the values were captured).

## RP-CD rules touched

- None new (read-only). Keep offline-first (Room Flow), never call the API directly from the VM.

## Tests

- ViewModel unit test: given a fake placement list (open + several closed) the rows come back ordered desc by `dateIn`, the open one renders "Currently deployed", and room/project names resolve; empty history → Empty state.

## Observability

- **Add:** remote log (WARN) only if you add a manual refresh that can fail; the pull path already logs (partial per RP-HD-008). Read-only screen otherwise needs none.
- **Success:** after deploy → move → check-out, the screen shows all three placements in correct order with correct dates and exactly one (or zero) open placement.

## Out of scope

Editing dates ([RP-FR-030]) and deleting a placement ([RP-FR-031]) — those add row affordances to this screen later.
