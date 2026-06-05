---
bug_id: RP-HD-004
aliases: []
title: Add unit-test coverage for the 2026-06 sync-fix batch (RP-BUG-029..035, RP-FR-003/004, RP-BUG-031)
type: hardening
classification: pre_existing_latent
source: internal
evidence: preventive
found_in: "1.29 (32)"
fixed_in: "1.29 (32)"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: "app/src/test: handler/service/mapper + Robolectric Room (OfflineDaoCascadeTest, OfflineDatabaseMigrationTest)"
priority: P2
last_updated: 2026-06-05
---

# RP-HD-004 — Test coverage for the 2026-06 sync-fix batch

> Preventive hardening. The fixes in this batch are implemented and the full unit
> suite is green (384/0), but the **new behavior** is thinly covered. Running the
> suite during this batch already caught a real regression (PropertyPushHandler
> returning `RETRY` on a 422 instead of `DROP`) — that near-miss is the motivation
> for locking the new behavior down with targeted tests.

## Why this is `RP-HD` and not `RP-BUG`

No user-visible failure is currently reachable — code is merged and green. This ticket
adds regression guards so the subtle, easy-to-revert logic in the batch (dirty-merge
gating, RETRY backoff accounting, surgical cascade ID-spaces) can't silently break in
a future change. It ships test code only.

## Scope — coverage gaps by source fix

### RP-FR-003 — pull-sync dirty-aware merge (`preserveDirty`)
Highest-value gap; the merge gates on a boolean and an `isDirty` check per entity.
- Per entity (Project, Location, Photo, AtmosphericLog, MoistureLog, Equipment, Note):
  pull save (`preserveDirty = true`) with an existing **dirty** row → local row preserved
  (`isDirty` kept, server overwrite dropped); existing **clean** row → server applied;
  no existing row → inserted as-is.
- **Push-success regression guard** (`preserveDirty = false`, the default): existing dirty
  row + incoming synced clean row → server row applied, `isDirty` cleared,
  `serverId`/`serverUpdatedAt` refreshed. This is the exact path the first draft of the
  fix would have broken.
- Property (`persistSyncedPropertyAtomically`): dirty local property preserved unless
  `forcePropertyIdUpdate`/pending-upgrade.

### RP-FR-004 / RP-BUG-031 — `OperationOutcome.RETRY` semantics
- `SyncQueueProcessor`: an op resolving to `RETRY` increments `retryCount`, sets a future
  `scheduledAt` with backoff, and flips to `FAILED` at `maxRetries` — i.e. parity with the
  old throw→`markSyncOperationFailure` path. (Guards the FR-004 Step 0 prerequisite.)
- Handlers (Equipment, Timecard, MoistureLog upsert+delete, Property create, Support):
  unknown error → `RETRY`; `CancellationException` still propagates; 409 → `CONFLICT_PENDING`,
  422 → `DROP` unchanged. (MoistureLog already updated this batch; extend to the rest.)
- RoomPushHandler (RP-BUG-031): retry failure with a non-409/422 error → `RETRY`, op stays
  queued, room not marked synced.

### RP-BUG-029 — surgical property→location→room cascade
- Payload `properties=[P], locations=[]` with a clean synced child location + room cached →
  child location and room marked deleted.
- Dirty or local-only (`serverId == null`) child under deleted P → **preserved**.
- Live replacement property in the same project → its locations/rooms preserved
  (relies on `propertyServerId` scoping).
- Room cascade matches `offline_rooms.locationId` against **local** location PKs, not serverIds.
- Migration 29→30: column added, index present, existing rows preserved (`propertyServerId` NULL).
  *(Migration test belongs in `androidTest` — instrumented Room migration harness.)*

### RP-BUG-030/032/033/034/035 — lighter guards
- 030: `resolveServerRoomId` returns null for unsynced room; unsynced-room assembly held
  `WAITING_FOR_ROOM` and promoted once the room syncs.
- 032: `observeMaterialsForProject` scopes by project; mapper carries `projectId`.
- 033: `MoistureLogRequest` serializes `drying_goal`; `toRequest` includes it.
- 034: `propertyTypeId <= 0` → defaults to 1; **422 → DROP** (the regression already fixed).
- 035: `syncRoomWorkScopes` merges pending local creates with the fetch.

## Suggested sequencing

1. RP-FR-004 processor backoff test + RP-BUG-029 cascade tests (trickiest logic, highest value).
2. RP-FR-003 per-entity dirty-merge matrix (incl. the push-success regression guard).
3. Remaining handler RETRY guards and the lighter 030/032/033/034/035 assertions.
4. RP-BUG-029 Room migration test under `androidTest` (separate from JVM unit tests).

## Outcome (implemented 2026-06-05)

408 unit tests, 0 failures (was 384). Two phases:

- **Phase 1 (mockk):** FR-004 handler RETRY + cancellation (Equipment/Timecard/Property/Support/
  MoistureLog), RP-BUG-031 Room retry-failure → RETRY, RP-BUG-034 propertyTypeId guard,
  RP-BUG-029 DeletedRecordsSyncService cascade call, RP-BUG-035 WorkScopeSyncService merge,
  RP-BUG-032/033 mapper tests.
- **Phase 2 (Robolectric + room-testing):** the cascade/merge SQL has no JVM seam via
  `LocalDataService`, so it is covered against an in-memory Room DB —
  `OfflineDaoCascadeTest` (clean-synced-only cascade, dirty/local/other-property preserved,
  rooms matched by LOCAL location PK; by-serverIds merge lookup) and
  `OfflineDatabaseMigrationTest` (real `MIGRATION_29_30` applied to a hand-built v29 table;
  `exportSchema=false` ruled out `MigrationTestHelper`).

### Coverage deliberately not added
- RP-FR-003 per-entity dirty-merge matrix at the `LocalDataService` level (private ctor builds
  its own file DB → no seam). The risky **SQL** (`getXByServerIds`) is covered via the Room
  in-memory DAO test; the thin Kotlin merge gating is covered indirectly. A full matrix would
  need DI refactor or a Robolectric `LocalDataService` harness — out of scope here.
- `SyncQueueProcessor` `RETRY`-backoff direct unit test (processor needs many ctor deps and the
  outcome handling is private); the RETRY behavior is asserted at the handler boundary instead.

### Notes
- A pre-existing red test (`EquipmentPushHandlerTest` consumed-409 SKIP) was corrected to
  `CONFLICT_PENDING` during this batch; suite is green as of `1.29 (32)`.

## Related

- Parent batch fixes: RP-BUG-029, RP-BUG-030..035, RP-FR-003, RP-FR-004 (and RP-BUG-031).
