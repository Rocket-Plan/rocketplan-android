# Fix Plan: [RP-BUG-374, RP-BUG-366, RP-BUG-365] P1 fix follow-ups — inert mode-rejection guard, migration index drift, and missing tests

**Bug ID(s):** RP-BUG-374 (not fixed), RP-BUG-366 (fixed, one follow-on), RP-BUG-365 (fixed, two cleanups), RP-BUG-367 (fixed, untested)
**Author:** Claude
**Date:** 2026-07-26
**State:** implemented 2026-07-26 — all four tasks landed. `parse409` single-read refactor (Task 1) and
the `catalogServerId` index convergence (Task 2) are in; `firstOrNull()` + dead-409-block removal
(Task 3) are in; tests for mode rejection, the live 409 branch and the 204 path (Task 4) are in.
Gates: 621 tests, 0 failures. Two P2s not in the original scope were fixed alongside: `RP-BUG-369`
(detail-hub flag gate) and `RP-BUG-370` (nav destination guards).
**Source review:** `docs/reviews/code_review_RP-FR-019_serialized_equipment_2026-07-26.md`
**Branch:** `feat/RP-FR-019-serialized-equipment` (changes are uncommitted in the working tree)

---

## Context for whoever picks this up

Four P1s were filed on 2026-07-26 and fixes were applied to the working tree. A review of those
fixes found: **two are correct, one is correct with a follow-on, and one does not work at all.**
Gates are green (618 tests, 0 failures) but the suite gained **zero** new tests, so green means
nothing here.

Read `docs/architecture/RP-CD_rules.md` before starting. Task 1 is a violation of a rule that
describes the exact failure, by name, including its symptom.

**Do not** re-litigate the parts already correct: `SyncHandlerUtils.extractUpdatedAt`'s new parse
order, the three `OfflineSyncApi` response-type changes, and the `PRAGMA table_info` guard in
`MIGRATION_31_32` are all right. Verified against the backend at
`/Users/kilka/GitHub/mongoose.rocketplantech.com`.

---

## Task 1 (P1, blocking) — make the mode-rejection guard actually run

### Problem

`SyncHandlerUtils.isModeRejection` reads the 409 error body a **second** time, after
`extractUpdatedAt` has already drained it. `RP-CD-005` states the failure verbatim:

> "`ResponseBody.string()` drains the buffered source; the second reader gets an empty string and
> conflict recovery silently SKIPs instead of retrying with the fresh timestamp."

Concretely: second `.string()` returns `""` → `gson.fromJson("", JsonObject::class.java)` returns
`null` → `json.has(...)` NPEs inside `runCatching` → `getOrNull()` → `?: false`. So
`isModeRejection` **always returns false**.

### Affected call sites — all read-after-drain except the last

| File | extractUpdatedAt | isModeRejection | Status |
|---|---|---|---|
| `handlers/EquipmentPushHandler.kt` | `:136` | `:138` | inert |
| `handlers/MoistureLogPushHandler.kt` | `:140` | `:142` | inert |
| `handlers/NotePushHandler.kt` | `:104` | `:106` | inert |
| `handlers/AtmosphericLogPushHandler.kt` | `:125` | `:127` | inert |
| `handlers/TimecardPushHandler.kt` | `:138` | `:140` | inert |
| `handlers/TimecardPushHandler.kt` | — | `:55` | **works** (body not yet read; `isValidationError()` only checks the status code) |

### Required change

Collapse to a **single** body read. Replace the two-function approach in
`SyncHandlerUtils.kt` with one parse that returns both facts, e.g.:

```kotlin
internal data class Conflict409(val updatedAt: String?, val isModeRejection: Boolean)

internal fun HttpException.parse409(gson: Gson): Conflict409? { /* one .string(), one fromJson */ }
```

`isModeRejection` is `updatedAt == null` **as determined from that same parse** — i.e. the body
parsed successfully and carried neither `current_updated_at` nor `data.current_updated_at`. Keep the
legacy `updated_at` / `data.updated_at` fallbacks for `updatedAt`, but note they must **not** count as
"not a mode rejection": the backend's mode rejection body has only `message`.

Distinguish three outcomes explicitly, because they map to different `OperationOutcome`s:
1. body unreadable / unparseable → unknown → **SKIP** (current behaviour for the null case)
2. parsed, has a conflict timestamp → **retry with it**
3. parsed, no timestamp, `message` present → **mode rejection → DROP**

Update all six call sites. Per `RP-CD-005` ("409 handling lives only in `SyncHandlerUtils`"), no
handler may call `.string()` on the body itself.

### Backend contract (authoritative — the routes are absent from `openapi.yaml`)

- Optimistic-lock conflict — `mongoose:app/Http/Controllers/Concerns/HandlesOptimisticLocking.php:64`
  → `{message, current_updated_at, resource_id}`. **Retryable.**
- Mode rejection — `mongoose:EquipmentRoomController::abortIfSerialized:31-40` and
  `RoomEquipmentRoomController::store:170-172` →
  `abort(409, 'Equipment serialization is enabled for this company; use the equipment-asset endpoints instead of legacy equipment-room placements.')`,
  i.e. `{message}` only. **Terminal** — retrying can never succeed while the flag is on.

Filed backend-side as `mongoose:MONGOOSE-BUG-059` to add a real discriminator. Until that lands,
absence of `current_updated_at` is the only available signal — leave a comment saying so, and saying
what to switch to once the backend ships a `code` key.

---

## Task 2 (P1) — converge the `catalogServerId` index across migration paths

### Problem

`master`'s `MIGRATION_30_31` creates `index_offline_equipment_catalogServerId`, and master's entity
**declares** `Index(value = ["catalogServerId"])` (`OfflineEntities.kt:533` on master). This branch's
entity **dropped that declaration** (branch `OfflineEntities.kt:527-535` lists uuid, projectId,
roomId, serverId, syncStatus, projectId+isDeleted, roomId+isDeleted — no catalogServerId), and the new
`MIGRATION_31_32` neither creates nor drops the index.

Result: a master-derived v31 device now migrates **successfully** (that is Task-0's fix working) and
lands with an index the entity does not declare → Room schema-validation mismatch when the DB is
opened. Before the fix that device crashed earlier on the duplicate column, so this was unreachable.

The entity change is pre-existing branch state, **not** introduced by the fix — but the fix makes it
reachable, so it must be resolved in the same change.

### Required change

First answer the question, do not guess: **was dropping the index intentional?** `catalogServerId` is
a reconcile lookup column (`EquipmentDto.toEntity` maps it; pull paths match on it), so losing the
index is plausibly a performance regression rather than a cleanup.

- If it should exist (**recommended**): restore `Index(value = ["catalogServerId"])` to
  `OfflineEquipmentEntity` and add `CREATE INDEX IF NOT EXISTS index_offline_equipment_catalogServerId ON offline_equipment(catalogServerId)`
  to `MIGRATION_31_32`. All three paths then converge on the same schema.
- If it should not: add `DROP INDEX IF EXISTS index_offline_equipment_catalogServerId` to
  `MIGRATION_31_32` and note in the commit why the index was removed.

Either way the three upgrade paths must end identically: fresh install (`createAllTables`), v30 →
31 → 32, and master-derived v31 → 32.

---

## Task 3 (P2) — two cleanups in the RP-BUG-365 fix

1. **`resp.data.first()` throws on an empty collection** — `EquipmentPushHandler.kt:284` and `:317`.
   `POST /api/rooms/{room}/equipment` returns a *collection*
   (`RoomEquipmentRoomController::store` → `EquipmentResource::collection`), including on the
   idempotent-replay branch where the match set could in principle be empty. Use `firstOrNull()` and
   return an explicit `SKIP` (with a remote WARN) rather than letting `NoSuchElementException`
   escape into the generic catch.

2. **Delete the now-unreachable duplicated 409 block** — `EquipmentPushHandler.kt` retry path. With
   `updateEquipmentRoom` returning `Response<Unit>`, Retrofit delivers non-2xx as a `Response` and
   never throws, so the `catch (e is HttpException && e.code() == 409)` branch cannot execute; the
   live path is the `resp.code() == 409` branch. ~40 lines are duplicated across the two. Delete the
   dead copy. **Note:** the existing 409 test was updated to
   `coEvery { … } throws PushHandlerTestFixtures.create409WithUpdatedAt(...)`, so it currently
   exercises the *dead* branch — it must be rewritten against `Response.error(409, …)` as part of
   Task 4, or deleting the dead code will turn it red.

---

## Task 4 (P2) — tests, because there are currently none

The four P1 fixes added **zero** tests; the test diff is mechanical signature adaptation only (618
tests before and after). Required coverage:

1. **`current_updated_at` parsing** (`RP-BUG-367`) — this is what silently regressed in the first
   place and can regress identically again. Test `parse409`/`extractUpdatedAt` against the documented
   body `{message, current_updated_at, resource_id}`, plus `data.current_updated_at`, plus the legacy
   `updated_at` fallbacks, plus unparseable/empty.
2. **Mode rejection end-to-end** (`RP-BUG-374`) — a handler test where the API returns a real
   `Response.error(409, '{"message":"Equipment serialization is enabled…"}')` and the expected
   outcome is `DROP`. **This test must fail against the current working-tree code** — if it passes
   before Task 1, the test is wrong. Add one per affected handler, or one parameterised test.
3. **Live 409 branch on the 204-update path** — `Response.error(409, …)` (not `throws`), asserting
   `CONFLICT_PENDING` and that a conflict row is recorded.
4. **204 success path** — `Response.success(Unit)` asserting the row is marked `SYNCED` from the local
   entity with no DTO adoption.
5. **Migration from a master-derived v31** — build a v31 `offline_equipment` **with**
   `catalogServerId`/`catalogUuid` and **without** the serialized tables (mirroring master), run
   `MIGRATION_31_32`, assert no exception, both tables exist, and index state matches the Task-2
   decision. The existing `OfflineDatabaseMigrationTest.kt:139-188` builds a v31 table *without* those
   columns, which is why the original bug passed CI.

Related, do not fix here: `RP-HD-014` tracks the absence of `validateMigration`/`MigrationTestHelper`
(`exportSchema=false`), which is why no test compares migrated SQL against the `@Entity` definitions
at all. Task 4.5 above is a targeted substitute, not a replacement for that ticket.

---

## Acceptance criteria

- `isModeRejection` (or its replacement) returns `true` for a real mode-rejection 409 in every
  handler, proven by a test that fails before the change.
- Exactly one `.string()` read per 409 body, anywhere in the flow. `RP-CD-005` satisfied.
- The three upgrade paths converge on an identical schema, incl. index state.
- No unreachable 409 handling remains in `EquipmentPushHandler`.
- `./gradlew compileDevStandardDebugKotlin` and `./gradlew testDevStandardDebugUnitTest` both green
  (run in the background per `CLAUDE.md`), with a **higher test count than 618**.
- Tracker updated: `RP-BUG-374` → `fixed` with `fixed_in`; `RP-BUG-365`/`-366`/`-367` annotated with
  their follow-ups resolved; cite the RP-CD rules touched (RP-CD-004, RP-CD-005) per `CLAUDE.md`.

## Out of scope

- `MONGOOSE-BUG-057..062` — filed in the backend repo; do not attempt client-side workarounds for
  the attach endpoint dropping `uuid`/`date_in`/`quantity`.
- The stale-`serverUpdatedAt`-after-204 round-trip cost. It is self-healing (409 → recover) and the
  real fix is backend `MONGOOSE-BUG-058` returning the resource. Note it, do not paper over it.
- `RP-BUG-368` is **refuted** — the backend eager-loads `placements` on every move/check-out success
  path. Do not "fix" it.
