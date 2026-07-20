---
bug_id: RP-BUG-336
aliases: []
title: Duplicate serialized-asset row when a register response is lost and a pull lands before the register op reconciles
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
related_plan: docs/plans/plan_rp_bug_336_register_natural_key_adopt_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-BUG-336 — Duplicate asset row on a lost register response

**Rule:** RP-CD-014 / RP-CD-018 (list-replacing pulls must merge locally-staged offline creates;
reuse local rows by natural key on user-create). RP-BUG-036/037/048 signature via the
lost-register-response window.

## Symptom
The same physical unit appears twice in the equipment pool, both rows eventually sharing one
`serverId`.

## Root cause
`RegisterEquipmentAssetRequest` sends no `uuid` (only `idempotency_key`), so the server mints its own
uuid. Pull reconciliation (`EquipmentAssetPullService.saveAssetsProtectingLifecycle` +
`mergePulledRowsByServerId`) matches only on non-null `serverId`. If register succeeds server-side
but the response is lost, the local row stays `serverId=null`/PENDING; a company-pool pull returns
that asset with a real `serverId` and a *different* server uuid, which doesn't match the local
`serverId=null` row → a second row is inserted. `unique(uuid)` can't collapse them (uuids differ).
When the register op later retries, idempotency returns the same `serverId` and adopts the local
row, leaving two rows on one `serverId`.

## Verified
- Confirmed `RegisterEquipmentAssetRequest` carries no `uuid` field.
- General limitation of the merge-by-serverId pattern (shared with other offline-created entities),
  but reachable here because register never round-trips the local uuid — hence P3.

See plan for the natural-key adoption fix (scoped to equipment assets).
