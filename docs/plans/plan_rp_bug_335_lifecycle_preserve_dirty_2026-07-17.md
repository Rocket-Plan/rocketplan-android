**Bug ID:** RP-BUG-335
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-335_lifecycle_success_clobbers_concurrent_metadata_edit_2026-07-17.md) · Plan (this doc)

# Plan — preserveDirty=true on the lifecycle-success asset write

## Fix
`data/repository/sync/handlers/EquipmentAssetPlacementPushHandler.kt` — pass `preserveDirty = true`
at both post-lifecycle writes (matching the deploy comment):

```kotlin
// deploy path (~line 88):
ctx.localDataService.saveEquipmentAssets(listOf(mergeAfterLifecycleSuccess(asset, assetDto)), preserveDirty = true)

// applyAssetResponse (~line 203, move/check-out):
ctx.localDataService.saveEquipmentAssets(listOf(mergeAfterLifecycleSuccess(existingAsset, assetDto)), preserveDirty = true)
```

`saveEquipmentAssets(preserveDirty=true)` (LocalDataService.kt:1218) re-reads the live row by
serverId and merges dirty fields. Clean-row case is unchanged (adopts the merged server row fully).

## Trade-off (document in commit)
When a concurrent edit is preserved, the fresh `serverUpdatedAt` lock token from the lifecycle
response is not adopted (the live edit-base token is kept), so the pending metadata update will 409
and go through conflict resolution — correct behavior for a real concurrent edit, and strictly
better than silent loss.

## Verification
- Both compile gates clean.
- Regression test (add): mark the live asset row dirty (simulate a rename) between handler entry and
  the write; assert the post-deploy row keeps the local name + `isDirty=true` rather than the server
  name. Clean-row case: assert it adopts the server row + fresh lock token.
- Manual: deploy an asset offline-slow, rename it during the sync window, confirm the rename survives
  and eventually syncs (or conflicts, not vanishes).

## Observability
Existing RP-HD-008 WARNs on the retry/refresh-fail paths; no new logs needed.
