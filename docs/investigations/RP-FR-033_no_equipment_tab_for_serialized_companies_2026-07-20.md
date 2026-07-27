---
bug_id: RP-FR-033
aliases: []
title: RocketDry Equipment tab is hidden entirely for serialized-mode companies — the legacy-wide gate removes the tab instead of swapping its content, so serialized companies have no equipment entry point on the RocketDry screen (iOS keeps the tab always visible)
type: functional
classification: new_code_bug
source: on-device parity check
evidence: reproduced
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-20 18:04:12 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_fr_033_serialized_equipment_tab_2026-07-20.md
related_review: null
related_test: null
priority: P2
last_updated: 2026-07-20
---

# RP-FR-033 — Equipment tab missing for serialized-mode companies on the RocketDry screen

> **Found on-device 2026-07-20** while comparing Android (tablet, user 832 `jeremie@rocketplantech.com`,
> company **7 · Team Fortify**, serialized-mode) against iOS for the same company. Android shows only a
> **Moisture** tab on the RocketDry screen — the **Equipment** tab is absent. iOS shows both.
> Parent: [RP-FR-019](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md). Sibling to the
> entry-point work in [RP-FR-032](RP-FR-032_serialized_equipment_entry_points_2026-07-19.md).

## Symptom

On the RocketDry room-list screen (`RocketDryFragment`), a serialized-mode company sees the Moisture
tab occupying the full width and **no Equipment tab button** beside it. There is therefore no way to
reach equipment (serialized room equipment, the company pool, or per-unit detail) from this screen —
the primary equipment surface. Reproduced on company 7 (serialized ON).

## Root cause

The "RP-FR-019 legacy-wide RocketDry cutover gate" (commit `a76bf84`) hides the *entire* equipment
tab button when the company is not serialized-mode OFF, instead of keeping the tab and swapping its
*content*:

- `ui/rocketdry/RocketDryViewModel.kt:63` — `legacyEquipmentAllowed` emits
  `mode == SerializedEquipmentMode.OFF`. For **ON** or **UNKNOWN** it emits `false`.
- `ui/rocketdry/RocketDryFragment.kt:316-319` — `equipmentButton.isVisible = allowed`; when not
  allowed and the current tab is EQUIPMENT it force-selects MOISTURE.

The intent was correct (never let a serialized company perform legacy count-based writes), but the
gate was applied at the **tab-visibility** level and the serialized **replacement content** for this
screen was never built. Net effect: serialized companies lose equipment access here entirely, and
UNKNOWN companies (still resolving the flag) also lose it rather than holding.

## iOS parity reference (the intended behaviour)

iOS **never** gates the Equipment/Moisture selector on equipment mode:

- `RocketPlan/Views/Unit/UnitContentView.swift:138` renders the `RPSegmentedControl` with
  `.equipment` + `.moisture` items unconditionally (the only branch above it is `showDamageTab`, a
  different flow). The Equipment tab is always present.
- The `equipmentMode` gate lives **one level deeper**, in
  `RocketPlan/Views/Room/RoomContentView.swift:125-135` (`equipmentContent`): `.serialized` →
  `SerializedRoomAssetsView`, `.legacy` → legacy count content, `.unknown` → a loading placeholder
  (holds; never legacy, never hidden).

So iOS keeps the tab visible for every mode and only changes what renders inside it. Android must do
the same for full parity.

## Impact

- **Serialized companies (mode ON): equipment is unreachable from the RocketDry screen.** This is the
  main equipment surface, so it is effectively "equipment is gone" for the entire serialized rollout
  on this screen. P2.
- **UNKNOWN companies:** the tab is hidden while the flag resolves rather than holding, so there is a
  window where equipment is missing even for OFF companies until the flag loads. (The `stateIn`
  default is `true`, mitigating the first frame, but a resolved-UNKNOWN stays hidden.)

## Observability

- No specific signal today. The gate decision (`legacyEquipmentAllowed`) is not logged. Consider a
  DEBUG `equip_ui` line when the equipment tab is hidden vs shown and why (mode), consistent with
  RP-HD-008 remote-logging conventions — so a future "equipment missing" report is diagnosable.

## Notes / related

- This supersedes the earlier minor gating observation (RocketDryViewModel seeds
  `legacyEquipmentAllowed = true` while `companyId == null`, which can briefly show the legacy tab for
  an ON/UNKNOWN company) — both are resolved by making the tab always-visible and gating content.
- Downstream destinations already gate correctly: room tap → `EquipmentRoomFragment` →
  `serializedRoomEquipmentFragment`; totals → pool (post-RP-FR-032). The missing piece is the
  RocketDry-level tab visibility + its serialized content.
- No data-model/sync change expected — this is UI/gating + a serialized content source for the tab
  body (open placements per room already exist in `OfflineEquipmentPlacementEntity`).
