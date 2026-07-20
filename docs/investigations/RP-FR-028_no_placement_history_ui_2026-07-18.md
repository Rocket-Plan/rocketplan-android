---
bug_id: RP-FR-028
aliases: []
title: Serialized equipment — no placement-history UI (endpoint wired, no screen)
type: feature
classification: new_feature
source: internal
evidence: inferred
found_in: "iOS parity review 2026-07-18"
found_at: "2026-07-18 10:43:18 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: plans/plan_rp_fr_028_placement_history_ui_2026-07-18.md
related_review: null
related_test: null
priority: P3
last_updated: 2026-07-18
---

> **Feature-parity gap** (Android RP-FR-019 vs iOS RP-BUG-344). Parent:
> [RP-FR-019](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md). iOS reference: `SerializedPlacementHistoryView`.

## Symptom

Android has **no screen** that shows a unit's placement history — the ordered
deploy → move → check-out timeline (which rooms/projects a unit has been in, each
`date_in`/`date_out`, and which placement is currently open). Users cannot answer
"where has this unit been?" iOS ships `SerializedPlacementHistoryView`.

## Current Android state (verified 2026-07-18)

- `OfflineSyncApi.getEquipmentAssetPlacements` → `GET /api/equipment-assets/{assetId}/placements`
  (`OfflineSyncApi.kt:566`) is wired, `EquipmentAssetPlacementDto` present, placements persisted
  in `OfflineEquipmentPlacementEntity` and pulled by `EquipmentAssetPullService`
  (`reconcileAssetPlacements`, with the RP-BUG-334 completeness guard).
- No history fragment / nav destination exists. Placements are read only to compute the
  room's "deployed" list.

So this is a UI build over an already-populated local table.

## Scope / acceptance

- New history screen keyed by asset, reading placements from Room via Flow, ordered desc by
  `date_in`, showing room + project display names, date_in/date_out, and open-vs-closed
  state.
- Reachable from the asset detail screen ([RP-FR-027]).
- (Editing/deleting placements is tracked separately: [RP-FR-030], [RP-FR-031].)

## Observability

### Current Signals
- Sentry / logs: none (screen doesn't exist); pull path logs are partial (RP-HD-008).

### Gaps
- The `reconcileAssetPlacements` destructive-reconcile path already had a data-loss bug
  (RP-BUG-334); a history UI would make such regressions user-visible instead of silent.

### Proposed Instrumentation
- Remote log on history-load failure (reuse RP-HD-008 category).

### Success Criteria
- QA: after deploy → move → check-out, the history screen shows all three placements in the
  correct order with correct dates and one (or zero) open placement.
