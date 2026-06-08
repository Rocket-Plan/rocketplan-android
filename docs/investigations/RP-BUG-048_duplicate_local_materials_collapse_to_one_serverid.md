---
bug_id: RP-BUG-048
aliases: []
title: RocketDry creates a new local material per reading/goal (no name-based dedup) — repeated readings on the same material spawn duplicate local materials that collapse to one server id but are never deduped locally, stranding child moisture readings
type: functional
classification: pre_existing_latent
source: internal
found_in: "1.0.00"
found_at: "2026-06-08 00:30:00 PST"
fixed_in: null
released_in: null
state: investigating
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
last_updated: 2026-06-08
---

# RP-BUG-048: duplicate local materials collapse to one server id (root of the moisture stranding)

> Found 2026-06-08 by `scripts/check_sync_duplicates.sh` run against live device data (tablet `30407ef`)
> — the observability gate built to verify the RP-BUG-036/037/038 duplicate-on-refresh fixes. Those 8
> entity types came back **clean**; **materials** flagged a real duplicate.

## Symptom

A drying material (e.g. "Concrete") appears as **multiple identical entries** for one room, and moisture
readings authored against it are split across the phantom copies — some readings never sync (see
RP-BUG-046). The duplication is invisible in normal use until the material list shows repeats or readings
go missing.

## Observed evidence (live DB)

`check_sync_duplicates.sh` output — 8/9 reconcilable tables clean, materials not:
```
ENTITY            rows  w/srvId  dupSrvIds  dupUuids
Materials           33       33          1         0
  ⚠️ offending serverId: 512922  (x4)
```
The 4 rows:
```
materialId       serverId  uuid                                  name      syncStatus
-1780893355626   512922    019ea584-0e69-…                       Concrete  SYNCED
-1780893235438   512922    019ea582-38ed-…                       Concrete  SYNCED
-1780893222074   512922    019ea582-04b8-…                       Concrete  SYNCED
-1780893219155   512922    019ea581-f951-…                       Concrete  SYNCED
```
- 4 distinct local uuids + negative PKs, **one** server id `512922`, no other "Concrete" with a different
  serverId. So it is pure **local duplication of one server entity**, not 4 real materials.
- The **4 stranded moisture readings** (RP-BUG-046) each point to one of these 4 phantom material rows by
  negative `materialId`. **This duplication is the root of the moisture stranding.**

## Root cause

Two compounding gaps:

1. **Create side — no name-based reuse** (`RocketDryRoomViewModel.addMaterialDryingGoal` :192, and the
   `…ByName` path): every call generates a fresh `uuid` and inserts a **new** `OfflineMaterialEntity`
   (`materialId = -System.currentTimeMillis()`, `syncStatus = PENDING`) with **no lookup for an existing
   material of that name in the room/project**. No `getMaterialByName(...)` helper exists. So N readings
   on "Concrete" create N local materials.
2. **Reconcile side — can't collapse N→1** : when each of the N rows is pushed, the server dedupes them
   to one material (`512922`) and returns that id, so all N local rows end up `serverId = 512922`.
   `mergePulledRowsByServerId` keys `existingByServerId` by serverId, which can represent only **one**
   local row per serverId — it cannot collapse the other N-1 duplicates. (This is *not* the RP-BUG-036/037
   single-create case; it's many-locals→one-server.)

## Relationship to RP-BUG-046
The 4 phantom materials are why the 4 readings stranded: readings attach to the duplicate (often
unsynced-at-author-time) material rows, and the push then fails/duplicates. Fixing this dedup is likely a
prerequisite for fully closing RP-BUG-046 (and would prevent the stranding in the first place).

## Suggested fix
- **Create side:** before inserting, look up an existing material by (project/room, name) — add
  `getMaterialByName(...)` to `LocalDataService` — and **reuse** it (attach the reading to the existing
  materialId) instead of minting a new row. Mirrors how readings should share one material.
- **Reconcile side (defense in depth):** when a push assigns a serverId that already exists on another
  local material row, **collapse** the duplicates (re-point child readings to the keeper, delete the
  extras) — the same "merge to keeper" pattern iOS uses (`OfflineSync+Materials.swift` re-points reading
  rows before deleting the duplicate). Cite RP-CD identity-reconciliation rules.
- Backfill/repair the existing stranded rows (re-point the 4 readings to one keeper material).

## Observability
### Current Signals
- `scripts/check_sync_duplicates.sh` — flags any serverId materialized as >1 local row (this bug's exact
  signature), across all reconcile-by-serverId tables. Run after create→sync→refresh; exits non-zero on dup.
### Gaps
- Nothing surfaces the duplicate to the user or to telemetry today; only a DB pull reveals it.
### Success Criteria
- Authoring multiple readings on one named material yields **one** local material row (`dupSrvIds = 0` in
  the detector), and all readings stay attached + sync. No stranded PENDING readings.
