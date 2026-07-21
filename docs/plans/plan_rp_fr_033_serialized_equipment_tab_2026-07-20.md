**Bug ID:** RP-FR-033
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-033_no_equipment_tab_for_serialized_companies_2026-07-20.md) · Plan (this doc) · parent [RP-FR-019](plan_rp_fr_019_serialized_equipment_2026-07-13.md) · sibling [RP-FR-032](../investigations/RP-FR-032_serialized_equipment_entry_points_2026-07-19.md)

# Plan — Keep the RocketDry Equipment tab visible for serialized companies; gate its *content* by mode (iOS parity)

## Goal / acceptance

Full parity with iOS `UnitContentView` + `RoomContentView`: the **Equipment tab is always present**
on the RocketDry screen for every equipment mode; only the tab **content** changes by mode. A
serialized-mode (ON) company must be able to tap Equipment and reach the serialized equipment surface
(per-room deployed units → detail hub, and the company pool). Never allow legacy count-based writes
for a serialized company.

- **OFF** → today's legacy count-based equipment content (unchanged).
- **ON** → serialized equipment content (see "Serialized tab content" below).
- **UNKNOWN** → tab visible, content shows a neutral loading/hold placeholder (never legacy, never
  hidden) — mirrors iOS `.unknown` → `messageEquipmentLoading`.

## Root cause recap (see investigation for detail)

- `ui/rocketdry/RocketDryViewModel.kt:63` `legacyEquipmentAllowed = (mode == OFF)`.
- `ui/rocketdry/RocketDryFragment.kt:316-319` `equipmentButton.isVisible = allowed` (+ force-switch to
  Moisture when hidden). Result: tab removed entirely for ON/UNKNOWN.

iOS reference: `RocketPlan/Views/Unit/UnitContentView.swift:138` (selector unconditional) and
`RocketPlan/Views/Room/RoomContentView.swift:125-135` (mode gates content only).

## Approach

Replace the boolean `legacyEquipmentAllowed` visibility gate with a **3-state mode** that drives
content, keeping the tab button always visible.

### 1. ViewModel (`RocketDryViewModel.kt`)
- Expose the resolved mode as a tri-state `StateFlow<SerializedEquipmentMode>` (ON/OFF/UNKNOWN) for
  the project owner-company (reuse the existing `observeProjects → companyId → observeMode` chain at
  lines 63-83; stop collapsing it to `== OFF`).
- Keep the existing write hard-gate semantics: legacy count-based mutations remain blocked unless
  mode == OFF (the VM already hard-gates writes; do not loosen).
- Add a serialized content source for the tab body — per-room **open placement** counts for this
  project from `OfflineEquipmentPlacementEntity` (open, not deleted, grouped by room), i.e. the
  serialized analogue of the legacy by-level counts. (Rooms list is mode-agnostic and already
  available.)

### 2. Fragment (`RocketDryFragment.kt`)
- Remove `equipmentButton.isVisible = allowed`; the Equipment tab button is **always visible**.
- Delete the force-switch-to-Moisture-when-hidden behaviour.
- Collect the tri-state mode and render `equipmentContentGroup` accordingly:
  - **OFF** → existing legacy views (`equipmentTotalCount`, `equipmentStatusBreakdown`,
    `equipmentLocationsRecyclerView`, `equipmentTotalsOpenButton`) as today.
  - **ON** → serialized content: a per-room list of deployed-unit counts (reuse
    `equipmentLocationsRecyclerView`/`EquipmentLevelAdapter` or a serialized variant); a room tap →
    `EquipmentRoomFragment` (already gates to `serializedRoomEquipmentFragment`); the totals/open
    button → the company pool (post-RP-FR-032, `TotalEquipmentFragment` already forwards ON→pool, so
    routing through it is fine, or navigate to the pool directly). Hide the count-based
    total/status-breakdown chips that have no serialized meaning, or replace with a deployed-count.
  - **UNKNOWN** → a neutral "loading equipment…" placeholder in `equipmentContentGroup`; no legacy
    views, tab still selectable.
- Keep the mid-session flip behaviour: if mode flips ON→OFF or OFF→ON while the screen is open, the
  content updates reactively (the tri-state flow already re-emits).

### 3. Strings / layout
- Add an UNKNOWN placeholder string (reuse an existing "loading equipment" string if present).
- Any serialized header label ("Deployed units", etc.) as needed. Minimal layout changes — prefer
  reusing existing views in `equipmentContentGroup`.

## Explicitly NOT in scope
- No sync/DTO/DAO/migration changes (open-placement query may already exist; add a read-only DAO query
  if not).
- Do not change the per-room screens (`EquipmentRoomFragment`/`serializedRoomEquipmentFragment`) or
  the pool — they already gate correctly (RP-FR-032).

## RP-CD / correctness notes
- Preserve the write hard-gate: a serialized (ON) company must never hit legacy equipment write paths
  (RP-BUG-279 class). Content-swap must not re-expose the legacy add/quantity/date controls for ON.
- Preserve CancellationException rethrow in any new flow `.catch`.
- Add RP-HD-008-style `equip_ui` logging for the gate decision (mode → shown content) so a future
  "equipment missing" report is diagnosable.

## Verification
- `compileDevStandardDebugKotlin` + `compileDevFlirDebugKotlin` clean.
- Unit test for the tri-state mode mapping in `RocketDryViewModel` (OFF→legacy, ON→serialized,
  UNKNOWN→hold), analogous to `SerializedEquipmentModeProviderTest`.
- On-device (company 7, serialized ON): Equipment tab is present next to Moisture; tapping it shows
  serialized content; a room opens `serializedRoomEquipmentFragment`; totals/open reaches the pool;
  an OFF company still shows the legacy tab unchanged; an UNKNOWN company shows the hold placeholder,
  not legacy. Fold into the RP-HD-010 on-device pass.

## Open design question for the implementer
What should the serialized Equipment tab **header** show in place of the legacy total-count/status
breakdown? Options: (a) total deployed units in this project; (b) nothing (just the per-room list +
pool entry); (c) available-vs-deployed split. Pick to match iOS's room-level presentation; (b) is the
lowest-risk minimum. Note the choice in the PR.
