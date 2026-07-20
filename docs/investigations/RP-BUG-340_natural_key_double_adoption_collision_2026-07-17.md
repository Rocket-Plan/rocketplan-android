---
bug_id: RP-BUG-340
aliases: []
title: RP-BUG-336 natural-key adoption double-adopts one local row when multiple identical un-serialized assets share a key — assetId collision drops a server asset
type: functional
classification: regression
source: review
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-17 22:06:33 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: RP-BUG-336
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_340_natural_key_consume_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-BUG-340 — Natural-key adoption double-adopts one local row (assetId collision)

**Regression of:** RP-BUG-336 (the natural-key adoption fix). **Rule:** RP-CD-018 (natural-key
reuse must be one-to-one).

## Symptom
When a company has multiple identical **un-serialized** units (e.g. 6 air movers with no serial
number) registered offline, one of them silently disappears from the local equipment pool after a
sync/pull, until a later clean pull re-fetches it. Common in restoration workflows where identical
un-serialized equipment is deployed in bulk.

## Root cause
`EquipmentAssetPullService.saveAssetsProtectingLifecycle` (~lines 141-191). The natural-key lookup
`unsyncedByNaturalKey[key]` is read but **never consumed**:

```kotlin
val naturalMatch = unsyncedByNaturalKey[key]      // returns the SAME local row every time
if (naturalMatch != null) adoptOrMerge(server, naturalMatch, ...)
```

For a serial-less asset the key is `(companyId, catalogUuid, name)`. When two or more incoming
server rows share that key, each adopts the **same** single local pending row, so the resulting
`merged` list contains two entries with the **same `assetId`** (Room primary key). The subsequent
`saveEquipmentAssets(merged)` upsert (REPLACE) collapses them → one server asset's local row is
lost, and the other pending local row is never adopted (stays `serverId=null`).

## Verified (by inspection)
- `unsyncedByNaturalKey` is not mutated inside the `servers.map { }`; nothing prevents N server rows
  from mapping to one local row.
- Reachable whenever ≥2 identical un-serialized units exist as pending registers and a pool/room
  pull lands before their register ops reconcile. P3 (needs the timing + identical un-serialized
  units), but a real correctness regression from the RP-BUG-336 fix.

## Secondary (efficiency, fold into the fix)
`getUnsyncedEquipmentAssets(server.companyId)` is called **inside** the per-server loop, so a
100-asset pool page issues ~100 identical DB queries and rebuilds the same map each time. Hoist it
to one query per distinct companyId before the loop.

## Observability
The multi-match WARN added by RP-BUG-336 fires, but adoption still proceeds incorrectly — so the log
signals the collision without preventing it. The fix removes the incorrect adoption.

See plan for the consume-on-adopt fix.
