---
bug_id: RP-HD-008
aliases: []
title: RP-FR-019 serialized-equipment error/swallow paths are not remote-logged — sync/pull/UI failures are invisible in the wild
type: functional
classification: new_code_bug
source: review
found_in: "feat/RP-FR-019-serialized-equipment (unreleased, built as 1.30 (35)-dev)"
found_at: "2026-07-17 20:55:27 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_hd_008_equipment_remote_logging_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-HD-008 — Remote-log the serialized-equipment (RP-FR-019) failure paths

Observability hardening for the new serialized-equipment feature, mirroring the RP-HD-005 pattern
(auth-gate decisions were local-logcat only). Governing rule: **RP-CD-019** — a non-retryable
server error (and, by the same diagnosability principle, an unexpected/swallowed error) must be
captured into the **local + remote** log so the failing condition is diagnosable in the field.
Preventive: no confirmed live failure, but the feature ships partly blind.

## Rationale / Symptom

The RP-FR-019 code has **good** remote coverage on the *terminal, classified* error paths
(422 DROP, 404/410 reconcile, 409 conflict). The gap is on the **generic/unexpected and swallow
paths**, where a failure produces either a local-only `Log.w` (never reaches `/api/logs/ios`) or
no log at all. Because equipment writes go through the offline queue and a destructive
authoritative-snapshot pull, a silent failure there is exactly the class of bug that is
undiagnosable after the fact — there is no Sentry crash and no remote trail.

## Gaps (from the 2026-07-17 audit)

1. **`EquipmentAssetPullService.refreshRoom`** — the entire body is wrapped in `runCatching { … }`
   (`EquipmentAssetPullService.kt:38`) and the `Result.failure` is swallowed with **zero logging**,
   local or remote. This path runs the *destructive* reconciliation (`markMissingAssetsDeleted`,
   `markReconciledDeleted`) — a mis-reconciliation or malformed pagination that removes local rows
   would be invisible. **Highest risk.**
2. **`SerializedRoomEquipmentViewModel.runAction`** — the catch-all `runCatching { block() }.getOrElse { … }`
   (`SerializedRoomEquipmentViewModel.kt:229`) discards the exception and only emits a toast
   ("Action failed — please retry."). The offline register/deploy/move/check-out/retire staging
   throwable is lost. Same for the deploy-failure branch in `registerAndDeploy` (line 201) and the
   pull-failure branch in `resolve()` (line 116-121).
3. **Generic RETRY catch branches in the push handlers** log locally only (`Log.w`, no remote):
   - `EquipmentAssetPlacementPushHandler.kt:91` (deploy succeeded but asset refresh failed),
     `:102` (deploy retry), `:138` (move retry), `:171` (check-out retry).
   - `EquipmentAssetPushHandler.kt:51` (upsert unknown-error retry), `:126` (retire retry).
   A repeated/unexpected sync failure therefore never reaches the backend log store.
4. **`OfflineSyncRepository.fetchEquipmentCatalog`** — `runCatching { … }` at
   `OfflineSyncRepository.kt:1244` swallows catalog-fetch failures silently; the register picker
   then shows an empty catalog with no signal as to why.

## Observability

### Current Signals
- Local console: `Log.w(SYNC_TAG=…)` on the push-handler RETRY branches only.
- Remote (`/api/logs/ios` via `RemoteLogger`): 422/404/409 terminal paths in both equipment push
  handlers. Nothing from the pull service, the repository equipment-asset methods, or the UI VM.
- Sentry: none (these paths don't crash; they return/retry/toast).

### Gaps
- A destructive pull reconciliation failure is fully silent.
- An offline write that rolls back is a toast with no server-side trace.
- Repeated generic sync RETRYs never surface remotely, so a stuck asset can't be diagnosed.

### Proposed Instrumentation
- Categories (reuse `SYNC_TAG` = `"API"` for handler/repo, a new `equip_ui` tag for the VM).
- Remote WARN on: pull `refreshRoom` failure; each generic RETRY branch; catalog-fetch failure;
  each VM action swallow + pull failure + register/deploy failure.
- Remote DEBUG (threshold-crossing) on: reconciliation actually removing N rows
  (`markMissingAssetsDeleted` / room-placement close) so a destructive delete leaves a trail.
- Key fields: `assetUuid` / `placementUuid`, `serverId`, `roomId`, `companyId`, `action`,
  `error` (message/class only — no raw user text, tokens, or full URLs, per the tracker's
  remote-logging guidance).
- Sampling/throttling: one-shot per operation (the queue already bounds retry cadence); WARN level.
- Build/env gating: all builds (low volume, actionable), via the existing `RemoteLogGate`.

### Success Criteria
- A single `log_entries`/`/api/logs/ios` query over the equipment categories reconstructs why an
  asset is stuck (which stage failed, on which asset/room, with the error class).
- A destructive pull reconciliation that removes rows leaves a remote DEBUG trail with counts.
- No raw user-entered text, tokens, or secrets in any added payload.

## Scope note

Filed as a single hardening ticket (not four) because all four gaps are one theme (RP-FR-019
error paths not reaching remote logs), share one fix approach (`RemoteLogger` calls at the
swallow/RETRY sites), and land together on the feature branch — mirroring how RP-HD-005 bundled
the auth-gate decisions.
