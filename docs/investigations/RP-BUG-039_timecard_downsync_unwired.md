---
bug_id: RP-BUG-039
aliases: []
title: Timecard down-sync is unwired — getTimecards/saveTimecards exist but are never called, so server-side timecards (admin/web/other-device) never appear locally
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: open
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P2
last_updated: 2026-06-07
---

# RP-BUG-039: Timecard down-sync is unwired

> Found while verifying whether timecards have the RP-BUG-038 duplicate-on-pull defect. They do **not**
> — because there is **no timecard pull at all**. Verified against the Android callers and the mongoose
> API.

## Symptom

Timecards created anywhere other than this device — by an admin/dispatcher on the web dashboard, or by
the same user on another device — **never appear** in the Android timecard views. The app only ever
shows timecards created locally on this device.

## Root cause (verified)

The down-sync machinery exists but is never invoked:
- `OfflineSyncApi.getTimecards(projectId, filter[updated_date])` (`:595`) is wired to
  `GET /api/projects/{projectId}/timecards` and even takes an incremental `updated_date` filter — but a
  repo-wide search finds **no caller**.
- `LocalDataService.saveTimecards(...)` (`:2054`, the list-persist used by a pull) likewise has **no
  caller**.
- Android timecards are only ever **created locally** (clock-in/out → `saveTimecard` singular) and
  **pushed** (`TimecardPushHandler`), then read locally (`getTimecardsForDate/Week`). Nothing reads the
  server list down.

The backend fully supports the pull: `ProjectTimecardsController.index` returns a paginated
`TimeCardResource` collection (also company-/user-scoped index endpoints). And the rest of the sync
system *assumes* timecards round-trip — `DeletedRecordsSyncService.applyDeletedRecords` calls
`markTimecardsDeleted` — so the missing down-sync is a gap, not a deliberate write-only design.

> **iOS parity (confirms this is a gap, not a design choice):** iOS actively pulls timecards —
> `TimecardService.getTimecards` (`GET /api/projects/{id}/timecards`, plus a user-scoped variant) is
> implemented, used, and tested (`TimecardServiceTests`), and iOS has a full `OfflineTimecardService`
> for the offline side. So iOS surfaces server-side timecards to the user; Android has the identical
> endpoint wired but never calls it. Android is missing the down-sync iOS has.

## Latent coupling (must fix together)

`saveTimecards` is a plain `upsert` with **no serverId reconciliation**. `OfflineTimecardEntity` has a
unique `uuid` index, but the backend has **no timecard `uuid`** (not fillable, no column, not in
`TimecardResource`; create dedups via `idempotency_key`), so `TimecardDto.uuid` is always null and
`TimecardDto.toEntity` generates a fresh random uuid on each pull. Therefore, the moment the pull is
wired, an offline-created timecard (local negative PK, client uuid, serverId set on push) plus its
pulled copy (server-id PK, random uuid) would **duplicate** — exactly the RP-BUG-038 class. The fix
must wire the pull **and** add serverId reconcile to `saveTimecards` in the same change.

## Affected code

| Concern | File:line |
|---------|-----------|
| Pull endpoint, never called | `OfflineSyncApi.getTimecards` (`:595`) |
| List-persist, never called | `LocalDataService.saveTimecards` (`:2054`) |
| Mapper generates random uuid when server uuid null | `TimecardDto.toEntity` (`SyncEntityMappers.kt:907`) |
| Backend list source | `ProjectTimecardsController.index` (paginated `TimeCardResource`) |
| System assumes round-trip | `DeletedRecordsSyncService` → `markTimecardsDeleted` |

## Suggested fix

1. Wire a timecard down-sync (e.g. in the project-metadata or a dedicated timecard sync step), calling
   `getTimecards(projectId, updatedSince)` with the incremental checkpoint and persisting via
   `saveTimecards`.
2. In the same change, add serverId reconciliation to `saveTimecards` (mirror
   `mergePulledRowsByServerId`: `isDirty = { it.isDirty }`, adopt local `timecardId` + `uuid`) so the
   newly-wired pull does not duplicate offline-created timecards (RP-BUG-038 class).
3. Add a checkpoint store entry for incremental timecard pull if appropriate.

## Observability

### Current Signals
- None — no timecard pull runs.

### Success Criteria
- A timecard created on the web/another device appears on this device after sync; offline-created
  timecards are not duplicated when the pull runs.
