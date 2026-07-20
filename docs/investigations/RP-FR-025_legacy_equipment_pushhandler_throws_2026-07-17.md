---
bug_id: RP-FR-025
aliases: []
title: Legacy EquipmentPushHandler.handle409Conflict throws instead of returning OperationOutcome (RP-CD-004)
type: functional
classification: pre_existing_latent
source: review
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-17 22:16:43 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_fr_025_legacy_pushhandler_outcome_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-FR-025 — Legacy EquipmentPushHandler throws instead of returning an outcome (RP-CD-004)

**Rule:** RP-CD-004 (push handlers return `OperationOutcome`, never throw). No user-visible failure →
`RP-FR`. Sibling of the historical RP-FR-004.

## Deviation
`EquipmentPushHandler.handle409Conflict` (`EquipmentPushHandler.kt:180`) does `throw retryError` on the
else branch — reached when the post-409 retry hits a transient/non-4xx error — which escapes
`handleUpsert` rather than returning `OperationOutcome.RETRY`. This is **legacy count-based** equipment
code, untouched by RP-FR-019 (the serialized `EquipmentAssetPushHandler` correctly maps all exceptions to
outcomes).

## Live risk: none
The processor wraps the handler in `runCatching { block() }` (`SyncQueueProcessor.kt:215`), so the throw is
absorbed and treated as a failed op (≈ RETRY) — no queue stall. It violates the "never throw" contract and
loses the explicit outcome, but has no behavioral consequence today.

## Proposal
Replace `throw retryError` with `return OperationOutcome.RETRY` (mirroring the serialized handler and the
RP-FR-004 remediation). Optional given this handler is superseded by the serialized rebuild — deprioritize
if legacy equipment is being retired.

## Observability
N/A.
