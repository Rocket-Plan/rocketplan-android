---
bug_id: RP-BUG-037
aliases: []
title: Offline-created notes/equipment/moisture logs/atmospheric logs duplicate after metadata refresh — pull maps server rows to a server-id PK with no serverId reconciliation, and the server uuid differs from the local uuid so the unique index does not collapse them
type: functional
classification: pre_existing_latent
source: review
evidence: reproducible
found_in: "1.0.00"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: app/src/test/java/com/example/rocketplan_android/data/local/dao/UpsertIdentityProbeTest.kt
violates: RP-CD-014
priority: P2
last_updated: 2026-06-07
---

# RP-BUG-037: Offline-created metadata entities duplicate on refresh

> Same identity-reconciliation root cause as [[RP-BUG-036]] (support) — found by the 2026-06-07 sweep
> for that class, then **verified against the backend and with a deterministic Room probe** before
> filing. Unlike [[RP-FR-005]]/[[RP-FR-006]] (latent, reads unused), these lists ARE rendered, so the
> duplicate is user-visible → filed `RP-BUG`.

## Affected entities

Note, Equipment, MoistureLog (`DamageMaterialRoomLog`), AtmosphericLog. (Room, Photo, Project were
checked and are SAFE; Property has its own dedup mitigation — see "Scope".)

## Symptom

A user creates a note / equipment / moisture log / atmospheric log **offline**, it syncs, and on the
next project-metadata refresh the item appears **twice** (two rows for one server record). Because
these lists are observed by the UI (`RoomDetailViewModel` notes; `RocketDryViewModel`
equipment/moisture/atmospheric), the duplicate is visible to the user.

## Root cause (verified)

A local row and the pulled server row end up with the same `serverId` but a **different primary key
and a different `uuid`**, and nothing reconciles them, so Room inserts a second row.

1. **Offline create** — `*SyncService`/ViewModel writes the row with a negative local PK, a
   client-generated `uuid`, `serverId = null`, `PENDING`.
2. **Backend does NOT echo the client uuid.** The create requests carry the client uuid only as
   `idempotency_key` (`CreateNoteRequest`/`EquipmentRequest`/`MoistureLogRequest`/`AtmosphericLogRequest`
   have no `uuid` field). On the server, `App\Models\Traits\HasUuid::bootHasUuid` mints
   `Str::orderedUuid()` **only when `uuid` is empty**, so the record's `uuid` is server-minted; Equipment
   and `DamageMaterialRoomLog` don't even `use HasUuid`. The resources (`NoteResource`,
   `EquipmentResource`, `AtmosphericLogResource`, `DamageMaterialRoomLogResource`) return that
   server-side `uuid`, which differs from the local one. *(Backend: `mongoose.rocketplantech.com`.)*
3. **Push preserves the local uuid** — e.g. `NotePushHandler` saves `dto.toEntity().copy(uuid = note.uuid, …)`,
   so the local row keeps its client uuid and negative PK, only gaining `serverId`.
4. **Pull maps to PK = server id with no reconcile** — `ProjectMetadataSyncService` calls
   `it.toEntity()` (no `existing`), so the mapper sets the PK to the server id; `saveNotes` /
   `saveEquipment` / `saveMoistureLogs` / `saveAtmosphericLogs` use `preserveDirty=true`, which only
   keeps the local row when it is **dirty** (after push it is clean) and otherwise upserts the server
   version under the server-id PK. There is **no** `serverId`-based dedup (contrast `Property`,
   `LocalDataService.kt:376`).
5. **Room `@Upsert` inserts a duplicate** — proven by `UpsertIdentityProbeTest`: with a different PK
   and a different `uuid` (so the `unique(uuid)` index does not fire) and the same `serverId`, the
   upsert inserts a **second** row. (The same probe shows that if the uuids matched, the unique index
   would collapse them — but they do not match here.)

## Reproduction

`app/src/test/java/.../dao/UpsertIdentityProbeTest.kt` reproduces the decisive DB behavior:
- `pull with DIFFERENT uuid and new PK but same serverId DUPLICATES the row` → 2 rows.
- `pull with SAME uuid and new PK but same serverId does NOT duplicate` → 1 row.

End-to-end: create offline → push → trigger `syncProjectMetadata` → two rows for one server id.

## Fix (implemented 2026-06-07)

The four `preserveDirty` pull merges (`saveNotes`/`saveEquipment`/`saveMoistureLogs`/
`saveAtmosphericLogs`) now reconcile by `serverId`: when a **clean** local row already exists for the
incoming `serverId`, the merged row adopts that local row's PK + `uuid`, so the upsert updates it **in
place** instead of inserting a server-id-PK duplicate. (Dirty local rows are still preserved per
RP-FR-003; rows with no local match insert as new.) The merge policy was extracted into a pure
`mergePulledRowsByServerId` helper so it is unit-testable independent of Room.

Tests: `MergePulledRowsByServerIdTest` (3 cases: clean→update-in-place, dirty→preserve, new→insert);
`UpsertIdentityProbeTest` documents the underlying `@Upsert` hazard the merge now avoids. Full suite
green (422 tests).

> Pre-existing duplicates (created before this fix) are not retroactively cleaned — this prevents *new*
> duplicates. A one-time dedup-by-serverId cleanup could be added if any are found in the wild.

## Scope

- **SAFE (verified):** Room (`resolveExistingRoomForSync` reconciles by serverId then uuid), Photo (no
  offline create; reconciles by serverId), Project (reconciles by serverId then uuid; compound unique
  `(uuid, companyId)`).
- **Property:** has a `serverId`-based dedup (`persistSyncedPropertyAtomically` deletes non-dirty
  duplicates) and a pending-upgrade path; a narrower residual (dedup skips dirty rows;
  `fetchProjectProperty` passes `existing=null`) needs its own trace — **not** included here.

## Observability

### Current Signals
- Local: `support_pull_reconciled`-style logs do not exist for these entities; `saveNotes` etc. log
  nothing about duplicates.
- Sentry: not captured (no crash; silent duplication).

### Gaps
- Nothing flags two rows sharing one `serverId`.

### Proposed Instrumentation
- In each `save*` merge, when a row is upserted whose `serverId` already exists under a different PK,
  emit a WARN (`pull_duplicate_server_id`, entity + serverId) — surfaces the bug and proves the fix.

### Success Criteria
- Create offline → sync → refresh yields exactly one row per server id for all four entities.
