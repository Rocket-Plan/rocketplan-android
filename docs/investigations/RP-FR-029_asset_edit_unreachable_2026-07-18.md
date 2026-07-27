---
bug_id: RP-FR-029
aliases: []
title: Serialized equipment — asset edit/update is unreachable (no repo entry point, no UI)
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
related_plan: plans/plan_rp_fr_029_asset_edit_2026-07-18.md
related_review: null
related_test: null
priority: P2
last_updated: 2026-07-19
---

> **Feature-parity gap** (Android RP-FR-019 vs iOS RP-BUG-344). Parent:
> [RP-FR-019](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md). iOS reference: `SerializedEditView`.

## Symptom

Once a serialized unit is registered on Android, its editable metadata —
`serial_number`, `asset_tag`, `status` (available ↔ maintenance), `note`, and the other
`PUT`-updatable fields — can **never be corrected**. There is no edit form and no code path
to enqueue an update. A typo'd serial number or a unit that needs to be marked
`maintenance` is permanent until the next server-side edit from another client. iOS ships
`SerializedEditView`.

## Current Android state (verified 2026-07-18) — this is a dead-wired capability

The sync layer *can* push an update, but nothing can ever invoke it:

- **Handler exists**: `EquipmentAssetPushHandler` dispatches the UPDATE op and calls
  `OfflineSyncApi.updateEquipmentAsset(asset.serverId, asset.toUpdateRequest(lockUpdatedAt))`
  (`EquipmentAssetPushHandler.kt:39`) → `PUT /api/equipment-assets/{assetId}`
  (`OfflineSyncApi.kt:555`), with optimistic-lock (`updated_at`) + 409 recovery.
- **No repo entry point**: `OfflineSyncRepository` exposes only
  `registerEquipmentAssetOffline`, `deployEquipmentAssetOffline`,
  `retireEquipmentAssetOffline`, `moveEquipmentAssetOffline`, `checkOutEquipmentAssetOffline`
  (`OfflineSyncRepository.kt:1206-1233`). There is **no** `updateEquipmentAssetOffline` to
  stage a dirty edit + enqueue the UPDATE op.
- **No UI**: register captures name/catalog/serial only; there is no edit screen.

So the fix is: an offline update method (stage dirty fields with `preserveDirty` semantics +
enqueue UPDATE, honouring the optimistic-lock token) + an edit UI, then wire it from the
asset detail screen ([RP-FR-027]).

## Scope / acceptance

- `OfflineSyncRepository.updateEquipmentAssetOffline(assetLocalId, fields…)` staging a local
  dirty write and enqueuing the existing UPDATE op (no new handler needed).
- Edit form (serial_number, asset_tag, status available/maintenance, note; optionally the
  purchase/vendor/warranty/rental fields the `PUT` accepts).
- Optimistic-lock 409 → refresh + re-present (handler already recovers `current_updated_at`).
- Backend rejects a status change while a placement is open (422) — surface that as a
  friendly "check the unit out first" message, matching the contract.

## Observability

### Current Signals
- Local console logs: `EquipmentAssetPushHandler` logs update retries/conflicts.
- Remote logs: partial (RP-HD-008).
- Sentry: none.

### Gaps
- The UPDATE op is currently unreachable, so any bug in its push path is untested in the wild.

### Proposed Instrumentation
- Remote log on update failure / 409 exhaustion / 422 status-change rejection.

### Success Criteria
- QA: editing serial/tag/note round-trips; toggling status available↔maintenance works when
  no placement is open and is blocked with a clear message when one is; a concurrent server
  edit produces a 409 refresh, not a silent clobber.
