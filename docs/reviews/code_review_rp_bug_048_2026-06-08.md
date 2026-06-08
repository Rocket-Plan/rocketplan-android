**Bug ID:** RP-BUG-048
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-048_duplicate_local_materials_collapse_to_one_serverid.md)

# Code Review: RP-BUG-048 duplicate local materials collapse to one server id

**Bug ID(s):** RP-BUG-048
**Reviewer:** Codex
**Date:** 2026-06-08
**Timestamp:** 2026-06-08 07:57:32 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

The investigation is strong: the live-device duplicate detector, the RocketDry create path, and the
material reconcile path all line up with the reported failure mode.

I agree that RP-BUG-048 is a real bug and very likely the root cause behind the stranded moisture logs in
RP-BUG-046. The main thing to tighten is the proposed fix shape: preventing duplicate inserts is necessary,
but the current RocketDry flow also re-resolves the material by **name** afterward, project-wide, which is
too weak to be a trustworthy identity link even after create-side dedup is added.

## Findings

### Must Fix

1. **Do not keep the post-create "find by name" flow in the eventual fix.**

   Today the new-material path is:

   - `addMaterialDryingGoal(name, goal)` inserts a new `OfflineMaterialEntity`
   - then `SetLatestAverageFragment` calls `addMaterialMoistureLogByName(materialName, ...)`
   - which does `observeMaterialsForProject(projectId).first().firstOrNull { it.name.equals(...) }`

   That second step is project-scoped and name-based, so it can attach the reading to the wrong row if:

   - duplicates already exist locally
   - two materials legitimately share the same display name
   - the intended reuse should be room-scoped but the lookup is project-wide

   The eventual fix should return or resolve a **canonical materialId** directly and pass that through to
   `addMaterialMoistureLog`, ideally in one transaction/helper, rather than creating/reusing a material and
   then looking it up again by name.

### Should Fix

1. **Tighten the investigation's suggested helper contract.**

   The doc suggests `getMaterialByName(project/room, name)`, but `OfflineMaterialEntity` only stores
   `projectId`, not `roomId`. If room scoping matters, the fix likely needs to resolve via the current
   room's moisture-log/material relationships or explicitly document why project-wide name reuse is safe.

2. **Fix the investigation timestamp abbreviation.**

   The front matter says `found_at: "2026-06-08 00:30:00 PST"`, but June 8, 2026 in
   `America/Los_Angeles` is **PDT**, not PST.

### Consider

1. **Add a regression test around the full RocketDry "new material + first reading" flow.**

   The key guard is not just "dedup by name exists", but:

   - repeated readings for the same chosen material produce one local material row
   - both readings attach to the same `materialId`
   - sync/reconcile does not leave multiple local rows with one `serverId`

2. **Add a local repair path in the eventual fix.**

   The investigation already notes this, and I agree: the implementation should not only prevent new
   duplicates but also re-point existing moisture logs to a keeper material and remove orphan duplicates.

### Verified Safe

1. **The detector script is useful and appropriately targeted.**

   `scripts/check_sync_duplicates.sh` checks the exact many-local-to-one-server signature across every
   reconcile-by-serverId table, which is a good observability complement to unit tests.

2. **The create-side evidence is direct.**

   `RocketDryRoomViewModel.addMaterialDryingGoal` always generates a fresh UUID and negative PK and inserts
   a new material without any existing-row lookup.

3. **The reconcile-side limitation is real.**

   `LocalDataService.saveMaterials(..., reconcileByServerId = true)` builds `existingByServerId` with
   `associateBy`, which can only retain one local row per `serverId` and therefore cannot collapse an
   already-duplicated N→1 local set by itself.
