---
bug_id: RP-BUG-339
aliases: []
title: SerializedRoomEquipmentViewModel.resolve() has no error boundary — a thrown Room read leaves a permanent spinner
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
related_plan: docs/plans/plan_rp_bug_339_resolve_error_boundary_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-BUG-339 — Uncaught throw in resolve() → permanent spinner

## Symptom
The serialized equipment screen is stuck on its loading spinner with no retry affordance.

## Root cause
`SerializedRoomEquipmentViewModel.resolve()` (~lines 89-133) collects a flow chain (`getProject`,
`observeEquipmentAssetsForCompany(...).first()`, the `contentFlow` combine) with no error boundary.
`Unavailable` is set only when `companyId == null`. If any Room read throws before the first `Ready`
emission (e.g. a DB/disk error), the collector dies silently and `_uiState` stays `Loading` forever.

## Verified
- The pull-failure path (`pull.isFailure`, ~line 116) *is* handled; this is the distinct DB-read
  path, which is uncaught. Low probability on offline-first Room, hence P3 — but it is exactly the
  "does the UI get stuck on a spinner if a flow fails?" case.

See plan for the `.catch { }` error boundary (emits `Unavailable`, remote-logs via the RP-HD-008
`equip_ui` category).
