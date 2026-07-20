---
bug_id: RP-FR-030
aliases: []
title: Serialized equipment — no placement date-correction (no endpoint, no UI)
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
related_plan: plans/plan_rp_fr_030_placement_date_correction_2026-07-18.md
related_review: null
related_test: null
priority: P3
last_updated: 2026-07-19
---

> **Feature-parity gap** (Android RP-FR-019 vs iOS RP-BUG-344). Parent:
> [RP-FR-019](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md). iOS reference: `SerializedPlacementEditView` / `correctPlacement`.

## Symptom

Android cannot correct a placement's `date_in`/`date_out` after the fact. If a unit was
deployed/checked-out with the wrong date, the room-occupancy / drying-duration reporting is
wrong and there is no way to fix it on-device. iOS added a backend
`PATCH /api/equipment-asset-placements/{id}` and a `SerializedPlacementEditView`
(UTC date-only correction; iOS RP-BUG-345 fixed an off-by-one in exactly this path — worth
mirroring the fix).

## Current Android state (verified 2026-07-18)

- Neither the endpoint nor a UI exists. Android wires 11 serialized endpoints
  (`OfflineSyncApi.kt:524-589`); placement PATCH is **not** among them.
- Note the related Android bug RP-BUG-337 (move sent the deploy time as `moved_at`) —
  incorrect placement dates can already occur, which raises the value of a correction path.

## ⚠️ API Contract Discipline

Per `CLAUDE.md`, **verify `PATCH /api/equipment-asset-placements/{id}` exists on the current
backend spec** (mongoose `docs/openapi.yaml` + Resource shapes) before adding the Retrofit
call. iOS built it against MONGOOSE-FR-015 "C11"; confirm the path, method, request body
(date_in/date_out + `updated_at` optimistic lock) and response wrapper on the Android target
backend. Do not call a route not in the spec.

## Scope / acceptance

- Wire `PATCH /api/equipment-asset-placements/{id}` (after contract verification) + tolerant
  request/response DTOs + golden-fixture parse test.
- Offline op-type + push handler branch (or extend the placement handler) with idempotency +
  optimistic lock + 409/422 handling, mirroring the existing serialized handlers.
- Correction UI reachable from the placement-history screen ([RP-FR-028]).
- Mirror iOS RP-BUG-345: treat the dates as UTC date-only to avoid an off-by-one.

## Observability

### Current Signals
- None (capability absent).

### Gaps
- Wrong placement dates are currently uncorrectable and silent.

### Proposed Instrumentation
- Remote log on correction push failure / 409 / 422.

### Success Criteria
- QA: correcting date_in/date_out round-trips, survives offline replay, and does not shift by
  a day across timezones.
