---
bug_id: RP-BUG-334
aliases: []
title: Serialized-equipment placement pull closes the open placement on a spurious empty response (deployment vanishes from the room)
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
related_plan: docs/plans/plan_rp_bug_334_placement_pull_completeness_guard_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-BUG-334 — Placement pull wipes the open placement on a spurious empty response

**Rule:** RP-CD-016 (server-side absence must not remove locally-valid rows without proof of
completeness). Companion to the `pullCompanyPool` completeness guard, which was never applied to
the per-asset placement path.

## Symptom
A serialized asset shown as deployed in a room disappears from that room's equipment list until the
next successful pull. The asset row itself still says `status="deployed"`, so the local state is
inconsistent: "asset deployed, but no open placement in the room."

## Root cause
`EquipmentAssetPullService.reconcileAssetPlacements` (`EquipmentAssetPullService.kt`, ~lines 199-241,
called from the step-3 loop) treats `GET /api/equipment-assets/{id}/placements` `.data` as an
unconditionally authoritative set and `markReconciledDeleted()`s every clean synced local placement
whose `serverId` is not in the response — deliberately even when the response is empty. A transient
spurious empty `{"data":[]}` for an asset the room endpoint just returned as deployed therefore
closes ALL clean placements including the open one.

## Verified
- Backend `EquipmentAssetPlacementController@index` returns `->get()` (unpaginated, no `meta`), so
  there is no completeness signal — and a deployed asset always has an open placement server-side,
  making empty-for-a-deployed-asset always spurious.
- Dirty/pending placements are protected (`getSyncedPlacementsForAsset` filters `isDirty=0`) and the
  state self-heals on the next non-empty pull — which is why this is P2, not P1.

## Observability
Now covered by RP-HD-008 (remote DEBUG when the non-empty path closes rows; WARN on pull failure).
No signal exists today for the empty-response case because it takes the (silent) delete path.

See plan for the fix (empty-is-not-authoritative guard).
