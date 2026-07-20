**Bug ID:** RP-BUG-334
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-334_placement_pull_wipes_open_placement_2026-07-17.md) · Plan (this doc)

# Plan — Guard the placement pull against a spurious empty response

## Fix
`data/repository/sync/EquipmentAssetPullService.kt` — `reconcileAssetPlacements(assetLocalId, dtos)`.
Do not run the destructive stale-close when the server returned an empty list:

```kotlin
private suspend fun reconcileAssetPlacements(assetLocalId: Long, dtos: List<EquipmentAssetPlacementDto>) {
    if (dtos.isNotEmpty()) {
        // …existing upsert of entities (unchanged)…
    } else {
        // Spurious/empty history for an asset the room endpoint returned as deployed is NOT
        // authoritative (endpoint is unpaginated with no completeness signal; a deployed asset
        // always has an open placement server-side). Skip the destructive close — self-heals on the
        // next non-empty pull. Mirrors pullCompanyPool's empty-is-not-authoritative guard.
        return
    }
    // …existing stale-close: serverIds / stale / markReconciledDeleted + the RP-HD-008 DEBUG log…
}
```

The legitimate "checked-out elsewhere" case still returns its closed rows (non-empty), so it
reconciles normally. Only the empty response is now treated as non-authoritative.

## Verification
- `compileDevStandardDebugKotlin` + `compileDevFlirDebugKotlin` clean.
- Regression test (add): given a local open placement + `getEquipmentAssetPlacements` returns
  `{data:[]}`, assert the open placement is NOT closed. Given a non-empty list omitting a stale
  closed row, assert that row IS closed (existing behavior preserved).
- Manual: deploy an asset, force one empty placements response (proxy/airplane toggle), confirm the
  asset stays in the room.

## Observability
Covered by RP-HD-008 (pull WARN + reconcile DEBUG).
