---
bug_id: RP-BUG-338
aliases: []
title: Serialized-equipment flag rollback (ON→OFF) drops the user to the RocketDry project screen, not the legacy equipment screen; move dialog also lists the current room
type: ui_bug
classification: new_code_bug
source: review
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-17 21:18:13 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_338_rollback_nav_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-BUG-338 — Flag rollback lands on the wrong screen (+ move dialog self-room)

## Symptom
1. A tech is on the serialized equipment screen when ops flips the company's serialized flag OFF
   (emergency rollback). Instead of falling back to the legacy equipment screen, they are dropped on
   the RocketDry **project** screen and must re-navigate into equipment to reach legacy.
2. Minor: the "move to room" dialog lists the current room as a choice, which the move service always
   rejects ("Couldn't move this unit.").

## Root cause
1. The forward nav action `equipmentRoomFragment → serializedRoomEquipmentFragment`
   (`mobile_navigation.xml:747-750`) uses `popUpToInclusive=true`, so beneath the serialized fragment
   sits `rocketDryFragment`, not the legacy host. On `LegacyMode`,
   `SerializedRoomEquipmentFragment.kt:196` calls `popBackStack()`, landing on RocketDry — contrary
   to its own comment ("fall back to the legacy screen").
2. `SerializedRoomEquipmentViewModel.roomChoices()` (~line 176) returns all non-deleted rooms
   including the current `roomId`.

## Verified
- Nav graph confirmed `popUpToInclusive=true` on the forward action.
- The move service rejects a same-room move (confirmed in code + backend `move()` validation).

See plan for the reverse-nav fix (must re-verify the round-6/7 ON→OFF→ON no-double-mount guarantees)
and the `roomChoices` filter.
