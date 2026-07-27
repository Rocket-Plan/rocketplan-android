---
bug_id: RP-HD-010
aliases: []
title: Serialized equipment — offline hardening audit vs iOS + on-device E2E pass
type: functional
classification: pre_existing_latent
source: internal
evidence: inferred
found_in: "iOS parity review 2026-07-19"
found_at: "2026-07-19 22:02:42 PDT"
fixed_in: null
released_in: null
state: open
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P2
last_updated: 2026-07-19
---

> **Hardening / verification follow-up** from the Android↔iOS equipment parity review (2026-07-19).
> The Android serialized-equipment feature is functionally complete (RP-FR-026..031 on the RP-FR-019
> spine) but has been validated by **unit + build gates only** — never driven on a device — and its
> offline path has not been through the tail of real-world fixes iOS accumulated. Parent:
> [RP-FR-019 plan](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md).

## Symptom (risk)

The offline write/replay path for serialized assets + placements is plausibly correct (push handlers
with SKIP-until-ready, optimistic lock, 409/422 handling; pull reconcile with completeness guards,
natural-key adoption, and the soft-delete-not-resurrected guard) but **unverified in the field**. iOS
shipped the same feature and then fixed a long tail of offline defects that a design review does not
catch. The equivalent Android edge cases are untested.

## What to audit against (iOS shipped fixes)

Cross-check the Android serialized offline path against the lessons behind these iOS tickets:
- **RP-BUG-349** — serialized server assets cached for offline reads + mutations.
- **RP-BUG-350** — check-in/move offline room IDs promoted on room creation (don't wait forever).
- **RP-BUG-351** — replay success reconciliation + advancing chained lock tokens.
- **RP-BUG-352 / 345** — equipment-remap identity carriers preserved; correction-form date round-trip.
- **RP-BUG-354 / 358** — general offline-flow hardening.
- **RP-BUG-357 / 360 / 361** — placement project_id remap / project attribution / labeling across projects.
- **RP-BUG-259** — preserve equipment start date on nil-server merge.

For each, confirm the Android path either cannot hit the failure or already handles it; file specific
`RP-BUG-###` items for any gap found.

## On-device E2E pass (enabled-company build)

Drive all six screens on a real device with `serializedEquipment` ON for the test company:
- register (pool + room), deploy/check-in, move (same-company cross-project), check-out, retire;
- edit metadata (incl. available↔maintenance guard while deployed);
- placement date-correction and delete (closed rows only);
- **offline**: perform each while offline → reconnect → verify sync + no duplicates
  (`scripts/check_sync_duplicates.sh`) + no resurrection of deleted placements;
- **process-death** mid-edit / mid-sync; **multi-company** switching; optimistic-lock 409 refresh.

Complements RP-FR-019's still-open "on-device enabled-company E2E" outstanding item — this narrows it to
the serialized offline matrix specifically.

## Observability

### Current Signals
- Remote WARN logs exist on serialized pull/push/action failures (RP-HD-008 territory) — verify coverage while testing.

### Gaps
- No field data at all yet (feature dark; never device-run).

### Proposed Instrumentation
- Confirm the RP-HD-008 remote-logging gaps are closed before/while running the on-device pass so failures are visible.

### Success Criteria
- Every offline scenario round-trips correctly on device with no duplicate rows, no lost edits, and no resurrected deletes; each audited iOS failure mode is confirmed handled or filed.
