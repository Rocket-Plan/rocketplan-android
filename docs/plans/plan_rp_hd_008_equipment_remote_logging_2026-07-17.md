**Bug ID:** RP-HD-008
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-HD-008_equipment_remote_logging_gaps_2026-07-17.md) · Plan (this doc) · Review (pending)

# Plan — Remote-log the serialized-equipment (RP-FR-019) failure paths

## Goal

Close the four observability gaps from the RP-HD-008 investigation by emitting `RemoteLogger`
entries at the swallow/RETRY sites, without changing control flow. Governed by RP-CD-019
(diagnosability of terminal/unexpected errors) and the tracker's remote-logging guidance
(one-shot, WARN/DEBUG, no raw user text/tokens).

## Changes

1. **`EquipmentAssetPullService`** — inject `remoteLogger: RemoteLogger? = null` (thread from
   `OfflineSyncRepository` construction at `OfflineSyncRepository.kt:176`). Add:
   - `.onFailure { }` WARN on `refreshRoom` (roomLocalId, companyId, error class/message).
   - DEBUG when `markMissingAssetsDeleted` and the room-placement close step actually remove rows
     (count), so the destructive reconciliation leaves a trail.

2. **`SerializedRoomEquipmentViewModel`** — use `app.remoteLogger` to WARN in:
   - `runAction`'s `getOrElse` (assetId, action-failure message, error class),
   - `registerAndDeploy` deploy-failure branch (line 201),
   - `resolve()` pull-failure branch (line 116-121, "showing cached / unavailable").
   Category tag `equip_ui`.

3. **`EquipmentAssetPlacementPushHandler`** — add `ctx.remoteLogger?.log(WARN, …)` alongside the
   existing `Log.w` at the deploy-refresh-failed (`:91`) and the deploy/move/check-out generic
   RETRY branches (`:102/:138/:171`). Fields: placementUuid, assetServerId, roomServerId, error.

4. **`EquipmentAssetPushHandler`** — same treatment for the upsert unknown-error RETRY (`:51`) and
   the retire RETRY (`:126`). Fields: assetUuid, serverId, error.

5. **`OfflineSyncRepository.fetchEquipmentCatalog`** — `.onFailure { }` WARN (companyId, error).

## Observability

This change *is* the observability fix: it adds remote WARN/DEBUG at every currently-silent
equipment failure path. No new metrics/watchdogs; reuses `RemoteLogger` + `RemoteLogGate`.
Payloads carry ids + error class only — no names, serials, notes, tokens, or full URLs.

## Verification

- `compileDevStandardDebugKotlin` + `compileDevFlirDebugKotlin` clean.
- Manual: force a pull failure (airplane mode mid-refresh) and a bad-catalog fetch on-device; grep
  `adb logcat` and confirm a corresponding remote-log enqueue. Full `/api/logs/ios` round-trip
  verified in QA once the branch is on a test build.
- No automated test for log emission (low value); rely on compile + manual logcat check.
