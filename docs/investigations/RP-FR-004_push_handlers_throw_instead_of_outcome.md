---
bug_id: RP-FR-004
aliases: []
title: Some push handlers throw instead of returning OperationOutcome (RP-CD-004)
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: open
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
parent: RP-HD-001
violates: RP-CD-004
priority: P3
last_updated: 2026-06-02
---

# RP-FR-004: Push handlers `throw` instead of returning `OperationOutcome`

> Filed by the `RP-HD-001` RP-CD audit sweep. Violates **`RP-CD-004` — push handlers return `OperationOutcome`, never throw**.

## Finding

Several handlers have an `else throw e` branch for unrecognized errors instead of mapping to an
explicit outcome:

- `EquipmentPushHandler.kt:75` (upsert), `:95` (delete)
- `TimecardPushHandler.kt:55` (upsert), `:75` (delete)
- `MoistureLogPushHandler.kt:49` (upsert), `:69` (delete)
- `PropertyPushHandler.kt:69` (create)
- `SupportPushHandler.kt:85` (conversation create)

## Severity: LOW — mitigated by a processor-level safety net

This is **not** the "uncaught exception stalls the queue" failure the rule's worst case describes.
`SyncQueueProcessor.handleOperation` wraps every handler call in `runCatching { block() }`
(`SyncQueueProcessor.kt:202`) and routes any thrown exception to `.onFailure { markSyncOperationFailure(...) }`
(`:305-336`). So a throw is caught and converted to a generic failure/retry — the queue does not
stall and nothing crashes.

The real cost is **semantic granularity**: a thrown exception goes through the generic failure path
instead of an explicit `SKIP` / `RETRY` / `DROP` / `CONFLICT_PENDING`, so metrics and backoff
treat it as an opaque failure rather than its true category. That is a code-shape/observability
concern (`RP-FR`), not a user-visible defect (`RP-BUG`).

## Suggested remediation (optional, low priority)

Either:
- Tighten the handlers to map unknown errors to `OperationOutcome.RETRY` (preserving the safety
  net's behavior but making it explicit), or
- Relax `RP-CD-004` to state that re-throwing to the processor's catch-all is an accepted fallback,
  and reserve the rule for handlers that throw *without* such a net.

No code change made under this ticket; severity does not justify reworking 8 call sites without a
decision on which direction to take.
