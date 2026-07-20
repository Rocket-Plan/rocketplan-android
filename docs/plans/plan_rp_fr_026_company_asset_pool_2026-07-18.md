# RP-FR-026 — Company serialized-asset pool screen (Android)

**Bug ID:** RP-FR-026
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-026_no_company_asset_pool_screen_2026-07-18.md) · Plan (this doc) · Review (pending) · parent [RP-FR-019 plan](plan_rp_fr_019_serialized_equipment_2026-07-13.md) · iOS `SerializedEquipmentContentView`

**Status:** planned · **Branch:** stack on `feat/RP-FR-019-serialized-equipment` (or a child branch) · **Type:** UI-only build over an already-wired data layer.

## Objective

Add a standalone, company-wide serialized-equipment **pool** screen: browse/search every unit the company owns across all statuses (`available`/`deployed`/`maintenance`/`retired`), register a new unit, and open a unit's detail ([RP-FR-027]). Today serialized equipment is only reachable per-room via `SerializedRoomEquipmentFragment`, where the pool is scoped to "deployable into this room".

## What already exists (verified 2026-07-18 — do NOT rebuild)

- **Read endpoint:** `OfflineSyncApi.getCompanyEquipmentAssets(companyId, status?, catalogUuid?, search?, perPage?, page?)` → `GET /api/companies/{companyId}/equipment-assets` (`OfflineSyncApi.kt:527`), paginated DTO.
- **Pull:** `EquipmentAssetPullService.pullCompanyPool` already fetches + upserts the whole pool (bounded, RP-HD-009; completeness-guarded, RP-CD-016).
- **Local reactive read:** `LocalDataService.observeEquipmentAssetsForCompany(companyId): Flow<List<OfflineEquipmentAssetEntity>>` (`:1288`, DAO `OfflineDao.kt:848`).
- **Mode gate:** `com.example.rocketplan_android.data.feature.SerializedEquipmentMode` (ON/OFF/UNKNOWN). Mount rules from RP-FR-019: mount serialized UI only when **ON**; **UNKNOWN** → retryable placeholder; never fall back to legacy from an unknown state.
- **Register path:** `OfflineSyncRepository.registerEquipmentAssetOffline(companyId, name, catalogUuid, …)` (`:1206`) + catalog picker pattern already in `SerializedRoomEquipmentFragment.showRegisterDialog()`.
- **Row UI:** `SerializedEquipmentAdapter(onPrimary, onSecondary)` + `SerializedRowUi` (`ui/rocketdry/SerializedEquipmentAdapter.kt`).

## Agent kickoff (paste to a coding agent opened in this repo)

```
Implement RP-FR-026 — "Company serialized-asset pool screen" — from this repo's tracker.

Read first, in full:
  - docs/plans/plan_rp_fr_026_company_asset_pool_2026-07-18.md  (this plan)
  - docs/investigations/RP-FR-026_no_company_asset_pool_screen_2026-07-18.md
  - docs/plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md  (parent, for mode-gate + mount rules)
  - app/src/main/java/com/example/rocketplan_android/ui/rocketdry/SerializedRoomEquipmentFragment.kt
    and its ViewModel/Adapter — MIRROR this structure (viewModels + provideFactory,
    repeatOnLifecycle STARTED, render(state) over Loading/Ready/Unavailable, refreshMode()
    onResume + 60s poll, MaterialAlertDialogBuilder register dialog).

This is a UI/nav build only — the endpoint, pull, local Flow, mode gate, register path, and row
adapter all already exist (see "What already exists"). Do NOT add DTOs, API methods, DAO writes,
sync handlers, or migrations.

Gates before you call it done (run ALL gradle in the background per CLAUDE.md):
  ./gradlew compileDevStandardDebugKotlin   (fast check)
  ./gradlew testDevStandardDebugUnitTest
  ./gradlew assembleDevStandardDebug
```

## Implementation steps

1. **Layout** `res/layout/fragment_serialized_equipment_pool.xml` — copy `fragment_serialized_room_equipment.xml` as the template: loading spinner, retryable "unavailable" view, a search field (`TextInputEditText`), a status filter (chip group: **All/Available/Deployed/Maintenance** — NO "Retired", see note), one `RecyclerView`, an empty state, and a register FAB/button.

   > **No Retired filter (resolved 2026-07-19):** the backend company index (`CompanyEquipmentAssetController@index`) has no `withTrashed()`, so retired units — which are soft-deleted (`deleted_at`) on retire — are never returned by the pool endpoint (only the *timeline* uses `withTrashed`); locally-retired rows are `isDeleted=true` and excluded by `contentFlow`. A Retired chip could therefore never populate, so it is omitted. Surfacing retired history would need a backend index change (or the timeline), out of this ticket's scope.
2. **ViewModel** `ui/rocketdry/SerializedEquipmentPoolViewModel.kt` — mirror `SerializedRoomEquipmentViewModel`:
   - `provideFactory(application, companyId)` (resolve `companyId` from the active company, as the room VM does).
   - Expose `uiState: StateFlow<PoolUiState>` (Loading/Ready(items)/Unavailable) built from `observeEquipmentAssetsForCompany(companyId)`, with client-side `search` + `status` filter `StateFlow`s combined in.
   - `refreshMode()` + mount gate identical to the room VM (ON → content; UNKNOWN → Unavailable/retry; OFF → navigate to legacy).
   - `resolve()`/`retry()` call `offlineSyncRepository.refreshSerializedRoom`-equivalent pull. NOTE: the existing pull entry point is room-scoped (`refreshSerializedRoom(roomId, companyId)`); add a thin `OfflineSyncRepository.refreshSerializedPool(companyId)` that calls only `EquipmentAssetPullService.pullCompanyPool` (extract/expose it) — do not require a room.
   - `register(name, catalogUuid, serial?)` → `registerEquipmentAssetOffline(...)` (register-only, NOT register-and-deploy).
3. **Fragment** `ui/rocketdry/SerializedEquipmentPoolFragment.kt` — mirror the room fragment: inflate, wire RecyclerView with a pool adapter (reuse `SerializedEquipmentAdapter`; primary action = "Details" → navigate to [RP-FR-027] when it lands; secondary = none/overflow for now), search/filter listeners, register dialog (reuse `catalogChoices()` pattern), toasts from `events`.
4. **Navigation** `res/navigation/mobile_navigation.xml` — add `<fragment android:id="@+id/serializedEquipmentPoolFragment" …>` with a `companyId` long arg, plus an action into it from wherever the equipment tab/menu is hosted (find the existing entry point that routes to `equipmentRoomFragment`/`totalEquipmentFragment` and add a sibling route). Regenerate safe-args by building.
5. **Strings** add `serialized_pool_title`, filter labels, and empty/search-hint strings to `res/values/strings.xml` (mirror existing `serialized_equipment_*` keys).
6. **Pagination note:** local Flow already returns the full pulled pool, so on-screen paging is not required for correctness. If the list is large, add incremental UI paging later; do **not** block this ticket on it. Log any hard cap you introduce (RP-CD: no silent truncation).

## RP-CD rules touched

- Mount rules / mode gate (RP-FR-019): UNKNOWN never falls back to legacy.
- RP-CD-016 completeness guard already lives in `pullCompanyPool` — reuse, don't weaken it.

## Tests

- ViewModel unit test (mirror `SerializedRoomEquipmentViewModel` tests if present under `app/src/test/.../rocketdry/`): ON → Ready with all statuses; status filter + search narrow the list; UNKNOWN → Unavailable (retryable), never Ready-with-legacy; register enqueues via the repo (fake).
- Instrumented nav test is optional (parity with RP-FR-019's deferred Fragment/nav tests).

## Observability

- **Current:** `pullCompanyPool` logs pages + pagination-cap warnings; partial per RP-HD-008.
- **Add:** remote log (WARN) on pool-refresh failure in the new VM `resolve()/retry()` (reuse the RP-HD-008 category once landed; otherwise mirror `fetchEquipmentCatalog`'s `remoteLogger?.log(...)`). Local debug logs for filter/search transitions only.
- **Success:** with the flag ON the pool lists every company unit, filters/search work, works offline from cache, and register round-trips; refresh failures are visible in remote logs, not a silent empty list.

## Out of scope

Asset detail ([RP-FR-027]), placement history ([RP-FR-028]), edit ([RP-FR-029]), placement correction/delete ([RP-FR-030]/[RP-FR-031]), timeline/Gantt.
