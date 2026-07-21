---
bug_id: RP-FR-035
aliases: []
title: Deletion sync crashes the sync job with "too many SQL variables" — mark*Deleted(serverIds) pass an unbounded IN(:serverIds) list to SQLite (999-var cap) when /api/sync/deleted returns a large batch
type: functional
classification: pre_existing_latent
source: on-device (QA logcat)
evidence: reproduced
found_in: "feat/RP-FR-019-serialized-equipment (35-dev) — pre-existing, surfaced on QA"
found_at: "2026-07-20 20:20:00 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P2
last_updated: 2026-07-20
---

# RP-FR-035 — Chunk deletion-sync `WHERE serverId IN (...)` under SQLite's 999-variable cap

> **Found on-device (tablet, QA)** during the RP-FR-033 verification. Not caused by the serialized
> work — a pre-existing latent bug in the deletion-sync path, triggered when QA's `/api/sync/deleted`
> returned a large deleted-equipment batch.

## Symptom (from QA logcat)

```
E SyncQueueManager: android.database.sqlite.SQLiteException: too many SQL variables (code 1): ,
while compiling: UPDATE offline_equipment SET isDeleted = 1 WHERE serverId IN (?,?,… ~1000+ …)
```

The whole deleted-records sync job fails (caught by `SyncQueueManager` — logged, not an app crash),
so server-side deletions stop applying locally for that cycle. Equipment was the entity that
overflowed, but the flaw is shared across entities.

## Root cause

`DeletedRecordsSyncService` feeds the server's full deleted-id lists straight into
`LocalDataService.mark*Deleted(serverIds)`, which call Room DAO queries of the form
`... WHERE serverId IN (:serverIds)`. SQLite caps a statement at **999 bound variables**
(`SQLITE_MAX_VARIABLE_NUMBER`); a large cleanup on the backend returns >999 ids and the statement
fails to compile. This affects **every** entity type with that query shape: projects, locations,
rooms, atmospheric logs, photos, equipment, equipment_assets, moisture logs, notes, damages,
work_scopes, properties, timecards.

## Fix

Chunk the id list below the cap at the `LocalDataService` boundary (covers all entities in one place):

- Added `private const val SQLITE_MAX_BIND_VARS = 900` (headroom under 999 for the odd extra bound
  param).
- Every `mark*Deleted(serverIds)` wrapper now does
  `serverIds.chunked(SQLITE_MAX_BIND_VARS).forEach { dao.mark*Deleted(it) }` instead of one call.
- `markRoomsDeleted` chunks the whole `withTransaction { markRoomsDeleted + clearRoomPhotoSnapshots }`
  per chunk (both are `IN(:serverIds)`).

Empty lists are a no-op (`chunked` of empty → no iterations). No behaviour change for lists ≤900.

## Not covered (follow-up candidates, lower risk)
- The `SELECT * FROM ... WHERE serverId IN (:serverIds)` reconcile reads (e.g.
  `getEquipmentAssetsByServerIds`) share the same theoretical cap but are fed page-sized lists, not
  the unbounded /sync/deleted set — not chunked here. Chunk them if a large-page reconcile ever
  overflows.

## Verification
- `compileDevStandardDebugKotlin` + `compileDevFlirDebugKotlin` clean.
- On-device: re-run a create→delete-server-side→sync cycle against a company with a large deleted set;
  the deletion sync completes without the SQLiteException.
