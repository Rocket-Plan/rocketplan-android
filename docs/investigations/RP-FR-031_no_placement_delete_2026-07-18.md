---
bug_id: RP-FR-031
aliases: []
title: Serialized equipment — no placement delete (no endpoint, no UI)
type: feature
classification: new_feature
source: internal
evidence: inferred
found_in: "iOS parity review 2026-07-18"
found_at: "2026-07-18 10:43:18 PDT"
fixed_in: "feat/RP-FR-019-serialized-equipment"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: plans/plan_rp_fr_031_placement_delete_2026-07-18.md
related_review: null
related_test: null
priority: P3
last_updated: 2026-07-19
---

> **Feature-parity gap** (Android RP-FR-019 vs iOS RP-BUG-344). Parent:
> [RP-FR-019](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md). iOS reference: `deletePlacement`.

## Symptom

Android cannot delete an erroneous placement record (e.g. a unit deployed to the wrong room
by mistake and immediately corrected, leaving a spurious history entry). There is no way to
remove it on-device. iOS added a backend `DELETE /api/equipment-asset-placements/{id}` and a
swipe-to-delete affordance in its placement-history view.

## Current Android state (verified 2026-07-18)

- Neither the endpoint nor a UI exists. Placement delete is **not** among the 11 serialized
  endpoints wired in `OfflineSyncApi.kt:524-589`.

## ⚠️ API Contract Discipline

Per `CLAUDE.md`, **verify `DELETE /api/equipment-asset-placements/{id}` exists on the current
backend spec** before adding the Retrofit call. Confirm the path, whether it requires an
`updated_at` optimistic-lock token, and the 422 conditions (e.g. cannot delete the open
placement / must check out first). Do not call a route not in the spec.

## Scope / acceptance

- Wire `DELETE /api/equipment-asset-placements/{id}` (after contract verification) + offline
  op-type + push handler branch with idempotency + 409/422 handling, mirroring the existing
  serialized handlers.
- Delete affordance reachable from the placement-history screen ([RP-FR-028]) with a
  confirmation.
- Local reconcile: mark the placement deleted and ensure `reconcileAssetPlacements` (with its
  RP-BUG-334 completeness guard) does not resurrect it.

## Observability

### Current Signals
- None (capability absent).

### Gaps
- Erroneous placements are currently permanent and pollute history/reporting.

### Proposed Instrumentation
- Remote log on delete push failure / 409 / 422.

### Success Criteria
- QA: deleting a closed placement removes it and it does not reappear after a pull; attempting
  to delete the open placement is blocked with a clear message (matching the backend rule).
