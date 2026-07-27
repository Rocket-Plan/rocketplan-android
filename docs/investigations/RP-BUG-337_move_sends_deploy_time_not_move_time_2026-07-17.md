---
bug_id: RP-BUG-337
aliases: []
title: Equipment move sends the original deploy time as moved_at, so the new-room placement date_in is wrong
type: functional
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
related_plan: docs/plans/plan_rp_bug_337_move_timestamp_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-BUG-337 — Move sends deploy time instead of move time

## Symptom
After moving a deployed asset to another room, its new-room placement history shows it arriving at
the *original deploy* time, not when it was actually moved. Room-occupancy / drying-duration reports
derived from `date_in` are wrong for moved units.

## Root cause
`EquipmentAssetMappers.kt:158` (`toMoveRequest`) sets `movedAt = dateIn.toApiTimestamp()`, where
`dateIn` is the original deploy timestamp (`EquipmentAssetSyncService.moveAsset` retargets the room
and bumps `updatedAt` but leaves `dateIn` unchanged). Backend `move()` uses `moved_at` for both the
old placement's `date_out` and the new placement's `date_in`.

## Verified
- Backend `EquipmentAssetPlacementController@move` reads `moved_at`, validates `moved_at >= current
  date_in`, and applies it to both `date_out` (old) and `date_in` (new); it defaults to `now()` when
  omitted. Because the client sends the original deploy time (== the open placement's `date_in`), the
  `>= date_in` check passes, so this is silent wrong data, not a rejection.
- Not intentional: the backend models `moved_at` as a distinct concept with a `now()` default.

See plan for the fix (send the actual move time, ideally captured offline at move intent).
