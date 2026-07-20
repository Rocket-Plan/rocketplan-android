**Bug ID:** RP-BUG-337
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-337_move_sends_deploy_time_not_move_time_2026-07-17.md) · Plan (this doc)

# Plan — Send the actual move time as moved_at

## Fix
`data/repository/sync/EquipmentAssetSyncService.kt` (`moveAsset`) +
`data/repository/mapper/EquipmentAssetMappers.kt:158` (`toMoveRequest`).

Do not reuse the original deploy `dateIn` for `moved_at`.

- Preferred (offline-correct): capture the move instant when the user performs the move in
  `moveAsset` (persist it on the placement/op record), and have `toMoveRequest` send that timestamp
  as `moved_at`. This preserves the true move time even when the move syncs later.
- Minimal acceptable: `movedAt = Date().toApiTimestamp()` at request-build in `toMoveRequest`.

Do NOT simply omit `moved_at` (backend would default to server-side `now()`, losing the true offline
move time).

Backend contract (verified): `move()` validates `moved_at >= current placement date_in` and applies
it to both the old `date_out` and new `date_in`. A captured move time is always ≥ deploy time, so it
passes.

## Verification
- Both compile gates clean.
- Regression test (add): given a placement deployed at T0 and a move performed at T1>T0, assert the
  move request carries `moved_at == T1` (not T0).
- Manual: deploy, wait, move to another room, inspect the new placement's `date_in` on the server /
  in the timeline — it should be the move time.

## Observability
None required.
