---
bug_id: RP-FR-032
aliases: []
title: Serialized equipment — interim/missing navigation entry points (pool discoverability + room-row detail)
type: feature
classification: new_feature
source: internal
evidence: inferred
found_in: "iOS parity review 2026-07-19"
found_at: "2026-07-19 22:02:42 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P3
last_updated: 2026-07-20
---

> **Follow-up** surfaced by the post-implementation Android↔iOS equipment parity review (2026-07-19),
> after RP-FR-026/027 landed. The screens work and are correctly mode-gated; this is about how a
> user *reaches* them. Parent: [RP-FR-019 plan](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md).

## Symptom

Two navigation gaps left as interim/deferred when the pool and detail screens shipped:

1. **Company pool is undiscoverable.** `SerializedEquipmentPoolFragment` (RP-FR-026) is only reachable
   via a **long-press** on the equipment-totals button in `RocketDryFragment` (the normal tap still
   opens `TotalEquipmentFragment`). A long-press is not a discoverable affordance — a real user would
   never find the company pool.
2. **No room-row → detail affordance.** On the room-scoped list (`SerializedRoomEquipmentFragment`),
   the per-unit detail screen (RP-FR-027) can only be opened from the pool. The room rows' tap +
   both buttons are already claimed (title-tap → edit per RP-FR-029; primary/secondary → lifecycle
   actions), so there is no way to open a deployed unit's detail from the room it's in.

iOS mounts the pool and room-assets cleanly through the room/totals `equipmentMode` gate
(`RoomContentView`), so both are reachable by normal navigation.

## Current Android state (verified 2026-07-19)

- Pool entry: `RocketDryFragment` long-press → `action_rocketDryFragment_to_serializedEquipmentPoolFragment` (interim, per RP-FR-026 deviation notes).
- Detail entry: only `SerializedEquipmentPoolFragment` primary "Details" → `serializedAssetDetailFragment`. Room rows carry a TODO for the deferred affordance.

## Scope / acceptance

- A **discoverable** company-pool entry point for serialized-mode companies — e.g. a menu/tab/button on the equipment surface shown when `SerializedEquipmentMode == ON` (mirror where iOS mounts it), replacing the long-press. Keep the mode gate (never show for OFF/UNKNOWN-as-legacy).
- A **room-row → asset-detail** affordance on `SerializedRoomEquipmentFragment` (e.g. an overflow/"info" control, or a dedicated tap target that doesn't collide with edit/lifecycle) → `serializedAssetDetailFragment`.
- No new data-layer work — both are pure navigation/affordance changes over existing destinations.

## Observability

### Current Signals
- None specific (navigation only).

### Gaps
- The pool screen is effectively dead-to-users without a real entry point — a shipped-but-unreachable feature.

### Proposed Instrumentation
- None required beyond existing screen-load logging.

### Success Criteria
- QA: a serialized-mode user reaches the company pool by an obvious control (no long-press), and can open any deployed unit's detail from its room; OFF/UNKNOWN companies never see the serialized entry points.

## Resolution (2026-07-20 — full iOS parity)

Read the iOS source (`ios.rocketplantech.com` branch `dev`) for the actual pattern:
`TotalEquipmentContentView` switches on `equipmentMode` and renders `SerializedEquipmentContentView`
(the pool) for `.serialized`; both the pool and `SerializedRoomAssetsView` rows are full-width
`Button`s that push `SerializedAssetDetailView`, and all lifecycle actions live on the detail view
(no inline row buttons). Android now mirrors this:

1. **Pool entry — totals *becomes* pool.** `TotalEquipmentFragment.gateOnOwnerMode` now routes by
   mode: `ON` → `navigateToSerializedPool(companyId)` (new nav action
   `action_totalEquipmentFragment_to_serializedEquipmentPoolFragment`, `popUpTo` totals inclusive so
   Back lands on RocketDry); `UNKNOWN` → leave legacy (unchanged); `OFF` → stay on legacy totals.
   The interim long-press in `RocketDryFragment` and its `navigateToSerializedEquipmentPool` helper +
   `action_rocketDryFragment_to_serializedEquipmentPoolFragment` are removed.
2. **Row → detail hub.** `SerializedEquipmentAdapter` collapsed to a single `onClick(assetId)`;
   `item_serialized_equipment.xml` drops the two `MaterialButton`s (whole card is a ripple tap
   target with a trailing chevron). `SerializedRoomEquipmentFragment` (deployed + pool lists) and
   `SerializedEquipmentPoolFragment` all navigate to `SerializedAssetDetailFragment`, which already
   hosts deploy/check-out/move/retire/edit/history with status-gated affordances (RP-FR-027).

Verified: `compileDevStandardDebugKotlin` + `compileDevFlirDebugKotlin` + serialized/equipment unit
tests green. On-device verification folds into the RP-HD-010 offline E2E pass.

### Known tradeoff
Deploy-from-pool from *inside a room* is now a tap into detail → **Deploy** (pick room) rather than a
one-tap room-scoped "Deploy" button on the pool row. This matches iOS's "detail is the hub" model but
costs a tap of room context; if room-scoped one-tap check-in is wanted later, add a "+ Check in
equipment" action to the room screen (small follow-up, no data-layer work).
