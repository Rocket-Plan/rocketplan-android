**Bug ID:** RP-BUG-336
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-336_duplicate_asset_row_lost_register_response_2026-07-17.md) · Plan (this doc)

# Plan — Adopt pending register rows by natural key on pull (RP-CD-018)

## Fix
`data/repository/sync/EquipmentAssetPullService.kt` — `saveAssetsProtectingLifecycle(dtos)`.
For an incoming server row with **no** existing match by `serverId`, before inserting, adopt a local
PENDING / `serverId==null` register row by natural key instead of creating a duplicate:

1. Add a DAO query `getUnsyncedEquipmentAssets(companyId)` returning `serverId IS NULL` rows.
   (Confirm no equivalent natural-key query already exists first.)
2. Build a lookup of those keyed by natural key: `(companyId, catalogUuid, serialNumber)` when a
   serial exists, else `(companyId, catalogUuid, name)`.
3. When an incoming row misses the serverId map but matches a natural key, adopt it:
   `server.copy(assetId = local.assetId, uuid = local.uuid, …)` so it upserts onto the existing row.
4. Guards: adopt only `serverId==null` rows; if multiple local rows match one key (two identical
   un-serialized units mid-register), adopt the oldest and emit a remote WARN so the ambiguity is
   visible. Never adopt a row that already has a different serverId.

Scope strictly to equipment assets — this is a systemic offline-create limitation; do not touch
other entities' pull paths here.

## Verification
- Both compile gates clean.
- Regression test (add): local PENDING asset (serverId=null, name="Dehu-7", catalog X); pull returns
  an asset with a real serverId + a different server uuid + same (company, catalog, serial). Assert
  ONE row after the merge (the local row adopts the serverId), not two.
- Manual: register offline, drop the register response (proxy), let a pool pull land, confirm no
  duplicate; then let the register op retry and confirm still one row.

## Observability
Remote WARN on the ambiguous multi-match case (reuses the RP-HD-008 pull logger).
