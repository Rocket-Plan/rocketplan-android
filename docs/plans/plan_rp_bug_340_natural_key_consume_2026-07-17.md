**Bug ID:** RP-BUG-340
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-340_natural_key_double_adoption_collision_2026-07-17.md) · Plan (this doc)

# Plan — Consume the local row on natural-key adoption (one-to-one)

## Fix
`data/repository/sync/EquipmentAssetPullService.kt` — `saveAssetsProtectingLifecycle`.

1. **Hoist the query out of the loop** (fixes the secondary efficiency issue): build
   `unsyncedByNaturalKey` once, from the distinct companyId(s) in `servers` (in practice one), before
   the `servers.map { }`. Do not call `getUnsyncedEquipmentAssets` per server.

2. **Consume on adopt** so each pending local row is adopted by at most one incoming server row.
   Track adopted local `assetId`s and remove the key once used:

```kotlin
val adoptedAssetIds = mutableSetOf<Long>()
val merged = servers.map { server ->
    val local = existingByServer[server.serverId]
    if (local != null) {
        adoptOrMerge(server, local, livePlacementAssetIds)
    } else {
        val key = NaturalKey(server.companyId, server.catalogUuid, server.serialNumber, server.name)
        val naturalMatch = unsyncedByNaturalKey[key]?.takeIf { it.assetId !in adoptedAssetIds }
        if (naturalMatch != null) {
            adoptedAssetIds += naturalMatch.assetId
            unsyncedByNaturalKey.remove(key)   // one-to-one; a 2nd server row with this key inserts as new
            adoptOrMerge(server, naturalMatch, livePlacementAssetIds)
        } else {
            server   // no (remaining) local match — insert the server row as its own new row
        }
    }
}
```

   For the multi-identical case this yields: server row #1 adopts the one pending local row; server
   row #2 falls through to `server` (a fresh insert with its own `serverId`/`assetId`) — no assetId
   collision, no lost asset. (If there were genuinely two local pending rows for the same key, only
   one is adopted per pull; the second reconciles via its own register op's idempotency on retry —
   acceptable, matches the pre-fix single-adoption intent.)

3. Keep the existing multi-match WARN, but only when it reflects a real ambiguity after consumption.

## Verification
- Both compile gates clean.
- Regression test (add): two incoming server rows (serverId 101/102) with `serialNumber=null`, same
  companyId+catalogUuid+name; one local pending row (assetId=10, serverId=null) same key. Assert the
  merged result has NO duplicate `assetId` and both server ids survive (one adopts assetId=10, the
  other is a fresh insert) — i.e. two distinct local rows after save, not one.
- Existing RP-BUG-336 single-match test still passes (one server row adopts the one local row).

## Observability
Reuses the RP-HD-008 pull `remoteLogger`; no new categories.
