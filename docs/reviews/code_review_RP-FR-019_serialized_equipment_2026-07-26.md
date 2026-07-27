# Code Review — RP-FR-019 serialized equipment branch + tracker reconciliation

- **Reviewer:** Claude (Opus 5)
- **Review started:** 2026-07-26 13:15:05 PDT
- **Branch:** `feat/RP-FR-019-serialized-equipment`
- **Commit range:** `master`…`77a9f2a` (154 files, +13429/−3550; ~40 are docs)
- **Status:** final
- **Scope:** (1) verification of every tracker row not in state `fixed`/`closed`, against current source; (2) full code review of the branch diff, split sync/data · UI · tests+gates; (3) the uncommitted working tree; (4) cross-repo verification of every contract-dependent finding against the backend at `/Users/kilka/GitHub/mongoose.rocketplantech.com`.
- **Backend reference:** working tree of `mongoose.rocketplantech.com` as of 2026-07-26 (`docs/openapi.yaml` dated Jul 17). Contract findings cite route files and controllers because the equipment endpoints are **not in the OpenAPI spec** — see `RP-HD-020`.

### Uncommitted files at review start (`git status --porcelain`)

```
 M app/src/main/java/com/example/rocketplan_android/ui/projects/ProjectsViewModel.kt
 M app/src/main/java/com/example/rocketplan_android/ui/rocketdry/RocketDryFragment.kt
 M app/src/main/java/com/example/rocketplan_android/ui/rocketdry/SerializedAssetDetailFragment.kt
 M app/src/main/java/com/example/rocketplan_android/ui/rocketdry/SerializedAssetEditFragment.kt
 M app/src/main/java/com/example/rocketplan_android/ui/rocketdry/SerializedRoomEquipmentFragment.kt
 M app/src/main/res/layout/fragment_project_landing.xml
 M app/src/main/res/layout/fragment_rocket_dry.xml
 M app/src/main/res/layout/fragment_serialized_asset_edit.xml
 M app/src/main/res/navigation/mobile_navigation.xml
 M app/src/main/res/values/strings.xml
```

### Gates

| Gate | Result |
|---|---|
| `./gradlew compileDevStandardDebugKotlin` | BUILD SUCCESSFUL |
| `./gradlew testDevStandardDebugUnitTest` | BUILD SUCCESSFUL — **618 tests, 0 failures, 0 errors, 0 skipped** |

Gates ran against the tree as-is (uncommitted files are all under `src/main`, so they were included). **Green gates did not catch any of the four P1s below.** Two reasons worth internalising: the migration test hand-builds a schema master never shipped (`RP-BUG-366`), and the handler tests stub the API with the response shape the code *expects* rather than the one the server sends (`RP-BUG-365`). A test suite that mocks its own misunderstanding of the contract cannot detect a contract bug — which is why the backend cross-check, not the gates, found these.

---

## Part 1 — Tracker verification

Every row not in state `fixed`/`closed` was re-verified against source. Result: **four genuinely open, one already fixed, and one row of prose that was actively misleading.**

| Row | Tracker said | Verified | Correction applied |
|---|---|---|---|
| `RP-BUG-046` | investigating | **STILL OPEN — accurate** | none; note added on what landed vs outstanding |
| `RP-BUG-276` | planned | **STILL OPEN** | title reworded — `isApproved` *is* parsed, just unused |
| `RP-BUG-277` | planned | **STILL OPEN** (app side) | note: parser already handles https; verification is the blocker; ops step unverifiable from this repo |
| `RP-BUG-341` | open | **STILL OPEN** | scope corrected — no queued path exists to attach a company to |
| `RP-BUG-279` | fixed | **routes fixed, round-trip still broken** | note added; blocked by new `RP-BUG-365` |
| `RP-FR-019` prose | "Does NOT fix RP-BUG-279 … stays open" | contradicted 279's own `fixed` state | prose corrected |

Notes on individual verifications:

- **RP-BUG-046** — diagnostics and data-loss are genuinely closed: the 422 body is drained on both the initial-upsert (`MoistureLogPushHandler.kt:49-50`) and 409→retry (`:178-179`) paths; 422 now returns `SKIP` (`:52,64,181,193`) bounded by `SyncQueueProcessor.kt:256-297`; a startup sweep re-enqueues orphans (`LocalDataService.kt:1800-1843`, wired at `RocketPlanApplication.kt:350-362`). The **rejecting rule itself is still unidentified** — no client guard for drying-eligibility or `damage_material_room` readiness exists. Part 2 needs a fresh device repro. The surviving DROP branch also has no `markRowError` → filed as `RP-BUG-371`.
- **RP-BUG-276** — a repo-wide grep for `approved` returns exactly two lines (`AuthModels.kt:85-86`). Not on `OfflineCompanyEntity` (`OfflineEntities.kt:19-31`), never read, no pending-approval destination. `MainActivity.checkAuthAndNavigate` (`:355-512`) gates only on SMS-verified (`:390`), pending invite (`:412`), and `companyId == null` (`:384,476`), then falls through to `nav_projects` (`:508`); the invite-join branch does the same at `:449`.
- **RP-BUG-277** — `AndroidManifest.xml:111` is the only https filter, still `autoVerify="false"`; no filter anywhere sets it true and there is only one manifest in the tree. `InviteLink.kt:12-39` already parses https paths, so the parser is not the blocker — App Links verification is. Publishing `/.well-known/assetlinks.json` on the three `/invite-redirect` hosts is a backend/ops action and **cannot be closed from Android code alone**.
- **RP-BUG-341** — both cited lines unchanged (`RetrofitClient.kt:82`, `PdfFormRepository.kt:191`). No `@Header("X-Company-Id")` on any Retrofit method. `OfflineSyncQueueEntity` (`OfflineEntities.kt:840-858`) has no `companyId`, and there is **no PDF-form or e-signature push handler** among the 16 in `handlers/` — these writes are online-only direct calls. The remediation therefore needs either a per-request header override through `PdfFormApi` or a new queued path; "persist the record's company with the queued action" presumes a queue that does not exist.
- **RP-BUG-279** — the pivot routes are live **and called**: `EquipmentPushHandler.kt:224` (update, 409 retry at `:148`), `:240/:272` (attach), `:93` (delete). Zero remaining `/api/equipment/{id}` references. Pivot identity is properly modelled (`serverId` = pivot id, new `catalogServerId`/`catalogUuid`, `OfflineEntities.kt:542`), re-landed in `f2f7cf7`. **But** the write responses cannot deserialize (`RP-BUG-365`), so equipment writes still fail to round-trip — the original user-facing symptom persists via a new mechanism. **Corrected 2026-07-26:** `origin/master` is `9ed6c87` (PR #8 merged 2026-07-14), not `9f6b540` — the earlier note read a stale local ref. `origin/master` carries the **original dead routes** `PUT`/`DELETE /api/equipment/{equipmentId}` (`OfflineSyncApi.kt:503,509` on that ref), since PR #7 reverted the pivot work and PR #8 did not re-include it. So RP-BUG-279 is genuinely still live on master and this branch is what fixes it. End-to-end round-trip remains gated on backend `MONGOOSE-BUG-057` — the attach endpoint discards the client's `uuid`/`date_in`/`quantity`. (The previously-cited `MONGOOSE-BUG-036` was never filed in the backend repo; that is now `MONGOOSE-BUG-057`.)

---

### Backend cross-check outcomes

Six findings had premises that this repo cannot settle. All were checked against the backend working tree:

| Finding | Premise | Outcome |
|---|---|---|
| `RP-BUG-365` | equipment write responses are `data`-wrapped | **confirmed and widened** — 3 defects, incl. a 204-no-body update |
| `RP-BUG-367` | 409 body carries `current_updated_at` | **confirmed** — `HandlesOptimisticLocking.php:64` + spec `:36087` |
| `RP-BUG-372` | catalog index returns `id`, not `equipment_id` | **confirmed** — `EquipmentResource` catalog branch omits `equipment_id` |
| `RP-BUG-368` | move/check-out may omit `placements` | **refuted** — backend eager-loads it on every success path |
| `RP-BUG-374` | — | **new** — 409 overloaded: mode rejection vs optimistic lock |
| `RP-HD-020` | — | **new** — equipment routes absent from `openapi.yaml` |

One in six filed findings was wrong, and the cross-check surfaced two that no amount of Android-side reading would have found. Contract-dependent claims should not be filed on Android evidence alone.

---

## Part 2 — Code review findings

### P1 — `RP-BUG-365` · every legacy equipment write endpoint declares the wrong response type (3 distinct defects)

`app/src/main/java/com/example/rocketplan_android/data/api/OfflineSyncApi.kt:502-518`

**Verified against the backend repo** (`/Users/kilka/GitHub/mongoose.rocketplantech.com`), which is authoritative here because these routes are absent from `docs/openapi.yaml` (see `RP-HD-020`). There is no `withoutWrapping` anywhere in the backend, so Laravel's default `data` wrapping applies.

| Endpoint | Backend actually returns | Android declares | |
|---|---|---|---|
| `POST /api/projects/{p}/equipment` | `EquipmentResource::make()` → `{"data":{…}}` | bare `EquipmentDto` | wrapped |
| `POST /api/rooms/{r}/equipment` | `EquipmentResource::collection()` → `{"data":[…]}` | bare `EquipmentDto` | wrapped **and a list** |
| `PUT /api/equipment-rooms/{id}` | `response()->noContent()` → **204, empty body** | non-null `EquipmentDto` | **no body at all** |
| `DELETE /api/equipment-rooms/{id}` | 204 | `Response<Unit>` | correct |
| `GET /api/rooms/{r}/equipment` | collection, wrapped | `SingleDataResponse<List<EquipmentDto>>` | correct |

Backend evidence: `ProjectEquipmentController::store:59-92` (`EquipmentResource::make`), `RoomEquipmentRoomController::store:161-206` (`EquipmentResource::collection`, both the idempotent-replay return at `:188` and the normal return at `:205`), `EquipmentRoomController::update:100` (`return response()->noContent()`, docblock `@responseFile status=204`), `::destroy:120` (same).

**Failure scenario A — update (worst, and not silent):** `PUT /api/equipment-rooms/{id}` succeeds server-side and returns 204 empty. Retrofit cannot produce a non-null `EquipmentDto` from an empty body, so the call **throws on success**. `EquipmentPushHandler.kt:224` sees a failure, the row is never marked SYNCED, and the op retries indefinitely — re-applying an edit the server already committed, until the queue exhausts retries. The write is not lost server-side; the local row never resolves.

**Failure scenario B — attach/create (silent corruption):** Gson maps `{"data":…}` onto `EquipmentDto` → every field null, non-null `id`/`projectId` coerce to `0` → `toEntity()` (`SyncEntityMappers.kt:700`) sets `serverId = 0` → row marked SYNCED with pivot id `0` → the next edit issues `PUT /api/equipment-rooms/0`. Via `createProjectEquipment` (`:314-315`): `created.id == 0` → `catalogServerId = 0` → attach body `equipment_ids: [0]` → 422 → `DROP`, i.e. silent data loss. For the room attach the body is additionally a **list**, so even the wrapped-single fix would be wrong.

Sending a `0` server id violates **RP-CD-017**; the wrapper mismatches violate the CLAUDE.md "match wrapper types" rule.

`EquipmentPushHandlerTest` cannot catch any of it — it stubs the API with a bare `pivotDto` (`:76`, `:153`), encoding the wrong contract into the test. The branch's own fixture `equipment_room_single.json` (data-wrapped, parsed as `SingleDataResponse<EquipmentDto>` at `EquipmentDtoParseTest.kt:58-61`) is right about wrapping but describes a shape no write endpoint returns.

**Fix:** `createProjectEquipment` → `SingleDataResponse<EquipmentDto>`; `attachRoomEquipment` → `SingleDataResponse<List<EquipmentDto>>`; `updateEquipmentRoom` → `Response<Unit>` (and re-fetch or locally apply, since there is no response body to adopt). Add fixture-backed parse tests wired to the actual Retrofit return types, with fixtures copied from the backend rather than hand-written.

**This is why `RP-BUG-279` is not effectively fixed.** The dead routes were replaced correctly, but legacy equipment writes still fail to round-trip — now through response handling rather than routing.

### P1 — `RP-BUG-366` · migration renumbering crashes every device already on DB v31

`data/local/OfflineDatabase.kt:98`, `:514`, `:593-596`

`master` is `version = 31` with `MIGRATION_30_31` (`:510-520`) = the equipment catalog/pivot column split (`ALTER TABLE offline_equipment ADD COLUMN catalogServerId`/`catalogUuid` + index). This branch is `version = 32`, **redefines** `MIGRATION_30_31` as "create `offline_equipment_assets`/`offline_equipment_placements`", and moves the column-adds into a new `MIGRATION_31_32`.

**Failure scenario:** a device at `user_version = 31` from any master-based build already has `catalogServerId`. Installing this build → Room runs `MIGRATION_31_32` → `ALTER TABLE offline_equipment ADD COLUMN catalogServerId INTEGER` → `SQLiteException: duplicate column name` → throws on first DB access. `fallbackToDestructiveMigration()` does **not** rescue this: it applies only when no migration path exists, not when a migration throws. Even had the ALTER succeeded, that device would lack the two serialized tables and fail Room's schema validation.

**Severity nuance — corrected 2026-07-26.** The original claim was measured against a **stale local `master` ref** (`9f6b540`). Current `origin/master` is `9ed6c87` (PR #8 merged 2026-07-14): its `MIGRATION_30_31` creates the serialized tables and it has **no `catalogServerId` column at all**, so devices on that lineage upgrade cleanly. The duplicate-column crash only reaches the pre-revert `9f6b540` lineage — stale local `master`, `feat/RP-FR-018`, and `backup/android-count-equipment-move-transfer-2026-07-13`. Production build 35 predates `389da21`, so shipped users were never exposed.

**Follow-on defect introduced by this fix, found on-device.** Adding `Index(["catalogServerId"])` to the entity changed the Room schema identity hash. Devices already at v32 from an earlier build of this branch skip `MIGRATION_31_32` entirely, so the index was never created and the stored hash stopped matching the expected one:

```
IllegalStateException: Room cannot verify the data integrity … Expected identity hash: d6b9e911…, found: 225331a4…
```

That is a **crash on launch** for every branch-lineage dev device. Reproduced on tablet 30407ef (v32, index absent, process died on start), which is precisely why the pre-merge device check was worth doing — the unit suite was green throughout. Fixed by bumping to **version 33** with `MIGRATION_32_33` creating the index `IF NOT EXISTS`, idempotent for the v31 lineage that already created it in 31→32. Re-verified on 30407ef: `user_version=33`, index present, 46 projects / 1 equipment / 2 assets preserved, zero Room errors. Regression test: `migration 32 to 33 creates the catalogServerId index on a v32 db that lacks it`.

**Lesson:** a schema change needs its own version even when the DDL is idempotent — `exportSchema=false` (`RP-HD-014`) means nothing catches this at build time.

**Why gates missed it:** `OfflineDatabaseMigrationTest.kt:139-188` hand-builds a synthetic v31 `offline_equipment` *without* those columns, so the duplicate-column path is never exercised.

**Fix:** never reuse a shipped version number — keep the equipment split at `30→31` (as on master) and make the serialized tables `31→32`. If 30→31 must be re-pointed, make `MIGRATION_31_32` defensive (`PRAGMA table_info` guard) **and** create the serialized tables there too.

### P1 — `RP-BUG-367` · `extractUpdatedAt` no longer parses `current_updated_at`, killing 409 recovery app-wide

`data/repository/sync/handlers/SyncHandlerUtils.kt:83-90`

The diff removed the `data.attributes.updated_at`, `data.current_updated_at`, and top-level `current_updated_at` paths, leaving only `updated_at` / `data.updated_at`. The backend 409 body is documented **in this branch** as `{message, current_updated_at, resource_id}` — `docs/plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md:20`, `docs/plans/plan_rp_fr_030_placement_date_correction_2026-07-18.md:30`. The doc comment at `:81` was rewritten to match the regression rather than flag it, so the code looks self-consistent.

**Failure scenarios:** two clients edit a placement → `PUT /api/equipment-rooms/{id}` 409 → `EquipmentPushHandler.kt:136-140` gets `null` → `OperationOutcome.SKIP` on every attempt. The edit never lands and never surfaces as a conflict. Same shape in `MoistureLogPushHandler.kt:140-144`, `AtmosphericLogPushHandler.kt:125`, `NotePushHandler.kt:104`, `TimecardPushHandler.kt:131`. Serialized handlers record conflicts with `remoteVersion = {"updatedAt": null}` (`EquipmentAssetPushHandler.kt:201`, `EquipmentAssetPlacementPushHandler.kt:410/446`).

Violates **RP-CD-004** (semantic outcome lost — permanent SKIP instead of retry/conflict) and **RP-CD-005** (the single body read now yields nothing useful).

**Confirmed against the backend:** `app/Http/Controllers/Concerns/HandlesOptimisticLocking.php:64` emits `'current_updated_at' => $currentTimestamp` at the **top level** of the 409 body, and `docs/openapi.yaml` documents that shape in three places (e.g. `:36087-36099`) with `message` / `current_updated_at` / `resource_id`. So the removed parse path was the only correct one, and the surviving `updated_at` paths never match a real 409.

**Fix:** restore the `current_updated_at` branches (top-level and nested) and pin them with a unit test using the documented 409 body.

### P1 — `RP-BUG-374` · 409 is overloaded — mode rejection is indistinguishable from an optimistic-lock conflict

Backend: `EquipmentRoomController::abortIfSerialized` (`:31-40`, called from `show`/`update`/`destroy`) and `RoomEquipmentRoomController::store` (`:170-172`) both do:

```php
abort(409, 'Equipment serialization is enabled for this company; use the equipment-asset endpoints instead of legacy equipment-room placements.');
```

That body carries **no `current_updated_at`** and no `resource_id` — it is a "wrong subsystem" rejection, not a version conflict. But Android treats every 409 on these routes as optimistic locking: `EquipmentPushHandler.kt:136-140` calls `extractUpdatedAt`, gets `null`, and returns `OperationOutcome.SKIP`.

**Failure scenario:** a company has `serializedEquipment` enabled, but a device still holds queued legacy count-based equipment writes (staged before the flag flipped, or authored while the flag read UNKNOWN). Every attempt returns 409 → `null` → `SKIP` → retried on every sync pass, forever. The op can never succeed (the endpoint is permanently closed to that company) and is never dropped or migrated, so it occupies the queue indefinitely with no user-visible error. Compounded by `RP-BUG-367`, which guarantees the `null`.

Two unrelated conditions sharing one status code is the root issue; the client cannot currently tell them apart.

**Fix:** distinguish them — treat a 409 whose body has no `current_updated_at` as a terminal mode rejection (DROP + remote WARN, ideally surfacing "this equipment must be re-created as a serialized asset") rather than a retryable conflict. Ask the backend to differentiate the two cases explicitly (distinct code or an error key); filed cross-repo alongside `RP-HD-020`.

### REFUTED — `RP-BUG-368` · "move/check-out strands the local placement `PENDING`+dirty"

Filed, then **refuted against the backend**. Recorded here so it is not re-found.

The claim was that `handleMove`/`handleCheckOut` return `SUCCESS` after `applyAssetResponse`, which touches placement rows only via `assetDto.placements?.let { … }` (`EquipmentAssetPlacementPushHandler.kt:341-344`) — and since `placements` is a conditional `whenLoaded` relation (`EquipmentAssetDtos.kt:63-67`, `EquipmentAssetResource.php:37`), a response omitting it would strand the local placement `isDirty=true`/`PENDING` while the queue op was consumed as SUCCESS.

**Why it does not happen:** the backend eager-loads the relation on *every* success path of both endpoints — `EquipmentAssetPlacementController::move` at `:384` (idempotent replay) and `:448-451` (normal), and `::checkOut` at `:495` — all `load(['currentPlacement.room.roomType', 'placements'])`. `placements` is therefore always populated, and a closed check-out placement remains in history so the list is never empty.

**Residual (note only, not filed):** the Android side relies on a backend eager-load it does not assert. If a future backend change drops that `load(...)`, the failure mode above becomes real and silent. A defensive `?: fetch placements` fallback would remove the coupling; the DTO field should stay nullable regardless.

### P2 — `RP-BUG-369` · `SerializedAssetDetailFragment` is the only serialized screen with no feature-flag gate

`ui/rocketdry/SerializedAssetDetailFragment.kt:134-140` (new fast path, uncommitted) + `SerializedAssetDetailViewModel.kt:161-179`

Every sibling gates writes on owner-company mode — `SerializedRoomEquipmentViewModel.requireOn()` (`:78-84`, applied at `:210` and `:272`), and the pool's `Disabled → navigateUp()` (`SerializedEquipmentPoolFragment.kt:147`). The detail hub deliberately has none (`SerializedAssetDetailViewModel.kt:36-39`, "no mode gate") yet hosts the entire write surface: deploy, move, check-out, retire, edit.

**Failure scenario:** open room serialized equipment with mode ON → tap a unit → detail hub. Backend rolls the flag OFF, or the flags fetch starts failing (UNKNOWN). The room screen behind would have bounced to legacy or shown the retry placeholder; the detail hub keeps every write button live and stages pending sync ops for a company no longer in serialized mode. This is the RP-FR-019 "unknown/failed mode mounts NO write UI" rule and the RP-BUG-338 class.

**Fix:** observe `SerializedEquipmentModeProvider.observeMode(asset.companyId)` in the ViewModel; on non-ON emit a state hiding the action row and `popBackStack()` (mirroring `SerializedEquipmentPoolViewModel`), plus a `requireOn()`-style guard inside `runAction` so an in-flight tap can't slip through.

### P2 — `RP-BUG-370` · serialized navigation lacks the repo's destination guard (double-navigate crash)

`SerializedRoomEquipmentFragment.kt:133-142` (uncommitted), `:165`, `SerializedEquipmentPoolFragment.kt:153-158`, `SerializedAssetDetailFragment.kt:47-58`

All navigate from a click listener with no `currentDestination` check, while the repo's convention everywhere else is exactly that guard — 5 occurrences in `ui/`, e.g. `EquipmentRoomFragment.kt:124`, `TotalEquipmentFragment.kt:155/180`, `ProjectListFragment.kt:171`, `CreateProjectFragment.kt:28`. RP-FR-032 made the whole row the tap target, raising exposure.

**Repro:** in a room's equipment list or the pool, tap two rows simultaneously (two fingers) or double-tap one fast. The first commits `→ serializedAssetDetailFragment`; the second is delivered to the still-attached hierarchy → `IllegalArgumentException: navigation destination action_…_to_serializedAssetDetailFragment is unknown to this NavController`. Same on the detail hub's Edit and View-history buttons.

**Fix:** the existing idiom, e.g. `if (findNavController().currentDestination?.id == R.id.serializedRoomEquipmentFragment) { … }`.

### P2 — `RP-BUG-371` · moisture 422 DROP branch leaves the row `PENDING` with no error state, then churns forever

`MoistureLogPushHandler.kt` (surviving DROP branch) + `LocalDataService.kt:1800-1843`

The RP-BUG-046 plan's Step 1 `markRowError(log, detail)` was never implemented. On the surviving DROP branch (positive `materialId` + synced room) the queue op is deleted while the row stays `PENDING` with no error state — the exact strand RP-BUG-046 describes. And because the repair sweep is deliberately wide ("any PENDING log with no queue op, regardless of materialId sign", `LocalDataService.kt:1801`), such a row is re-enqueued at every app start → DROP → re-enqueue, **unbounded**.

Low blast radius (that branch requires a server-originated material), but it is both a lingering RP-BUG-046 strand and a repeat-push loop.

**Fix:** implement `markRowError` on DROP, and narrow the sweep to exclude rows already marked errored.

### P2 — `RP-BUG-372` · catalog fallback reads `equipment_id`, which the catalog branch never returns

`handlers/EquipmentPushHandler.kt:326-335`

`lookupProjectEquipmentByTypeName` does `resp.data.find { it.type == typeName }?.equipmentId`.

**Confirmed against the backend:** `EquipmentResource::toArray` has three branches, selected by what is loaded. The catalog branch (the one `GET /api/projects/{id}/equipment` hits) returns exactly `id`, `catalog_uuid`, `name`, `is_standard`, `count`, `created_at`, `updated_at` — **no `equipment_id`** (that key exists only on the two room-assignment branches, where it is the pivot's catalog reference). The resource even documents this: "the room-assignment branches above intentionally omit it."

So the 422-duplicate-name fallback always returns `null` → `resolveOrMintCatalog` returns null → `equipment_catalog_unresolved` WARN + `IllegalStateException` (`:265`) → `handleUpsert` maps to `RETRY` **forever** for any standard/pre-seeded equipment type. Raised from P3 to P2: this is the common path for pre-seeded equipment, not an edge case, and it never self-resolves.

**Fix:** use `?.id` (or `it.equipmentId ?: it.id`). Note the catalog branch also omits `uuid` and exposes `catalog_uuid` instead — check `EquipmentDto`'s uuid mapping against the same branch.

### P3 — `RP-BUG-373` · stale `MOVE`/`TRANSFER` queue rows silently degrade to `UPDATE`

`data/local/SyncEnums.kt:37-47`, `OfflineTypeConverters.kt:35`

`MOVE`/`TRANSFER` were removed from `SyncOperationType`, but `fromName` falls back to `UPDATE` and existing DB rows persist. A device with a queued equipment `MOVE` from a master build replays it as `equipmentHandler.handleUpsert` — the move is silently converted into a quantity/date pivot update. No error, no drop, wrong outcome.

**Fix:** purge `offline_sync_queue` rows with `operationType IN ('MOVE','TRANSFER')` in the migration, or have `fromName` return null → DROP rather than `UPDATE`.

---

## Part 3 — Hardening / test-coverage findings

### P2 — `RP-HD-020` · the equipment endpoints are absent from `docs/openapi.yaml`, so CLAUDE.md's contract rule is unsatisfiable

CLAUDE.md requires: "Before adding or changing any Retrofit call or DTO, verify the endpoint path AND response shape against the backend spec… Never call a route that isn't in the spec."

The backend's `docs/openapi.yaml` contains **89 paths and zero equipment paths** — `grep -E "^  /api.*equipment"` returns nothing, though the file mentions "equipment" 164 times in schema fragments. The real contract lives in `routes/api/equipment-rooms.php`, `routes/api/equipment-assets.php`, `routes/api/{projects,rooms}.php` and the controllers' Scribe docblocks.

**This is the mechanism behind RP-BUG-279 and RP-BUG-365.** A rule that cannot be followed produces exactly this failure twice: the first time the client invented routes, the second time it invented response shapes. Both would have been caught by a five-minute spec read if the spec covered these endpoints.

**Fix (partly cross-repo):** ask the backend to include equipment routes in the OpenAPI regen (`.github/workflows/openapi-regen.yml` exists, so the generator is presumably filtering or the annotations are missing). Android-side, until that lands, the contract-discipline rule should name the route files + controllers as the fallback authority so reviewers know where to look. Also request that the two distinct 409 conditions (`RP-BUG-374`) be documented and differentiated.

### P2 — `RP-HD-013` · `SyncQueueProcessor` entityType routing is completely untested (highest silent-data-loss risk)

Four new magic strings — `equipment_asset`, `equipment_asset_placement`, `equipment_asset_placement_correction`, `equipment_asset_placement_delete` — appear in **four independent places**: `enqueue*` (`SyncQueueProcessor.kt:992/999/1014/1044/1061/1077`), the dispatch `when` (`:422/429/440/448`), `isEntityDeleted` (`:162-168`), and the op-collapse logic in `EquipmentAssetSyncService` (with a hardcoded copy at `EquipmentAssetSyncServiceTest.kt:190`). Every service test mocks `SyncQueueEnqueuer` (`:30`), so **nothing pins enqueue-side strings to dispatch-side strings**.

The fallthrough is destructive: `:500-503` logs "Unknown operation type" and calls `localDataService.removeSyncOperation(...)` — a typo'd or unrouted entityType silently discards the user's offline register/deploy/move/check-out/correction/delete, with no retry, no conflict row, no user-visible failure. Same for the `else -> OperationOutcome.DROP` arms at `:443/451`.

**Fix:** a table-driven test that enqueues via the real `SyncQueueProcessor.enqueue*` and asserts each dispatch arm is reached; or extract the strings to shared constants.

### P3 — `RP-HD-014` · migration schema drift unvalidated

DB is v32 with `MIGRATION_30_31`/`MIGRATION_31_32`. `OfflineDatabaseMigrationTest.kt` covers 29→30, 30→31, 31→32 by hand-building the old table and asserting columns/indexes/row preservation — but because `exportSchema=false` there is no `MigrationTestHelper`/`validateMigration`, and **no test opens the DB through Room after migrating**. So migrated SQL is never checked against the `@Entity` definitions. DAO tests use `Room.inMemoryDatabaseBuilder` (fresh `createAllTables`) and never traverse the migration path.

A column type/nullability/default mismatch throws Room's "Migration didn't properly handle" on a real upgrade — and with destructive fallback in dev that wipes the offline DB **including unpushed pending ops**.

**Fix:** one test that runs 29→32 then opens `OfflineDatabase` through Room so validation fires.

### P3 — `RP-HD-015` · RP-FR-035 chunking fix has zero tests

`LocalDataService.kt:1284` (`markEquipmentAssetsDeleted`) plus ~10 sibling `serverIds.chunked(SQLITE_MAX_BIND_VARS)` sites. `grep -rln "chunk" app/src/test` → no hits. A regression re-introduces the "too many SQL variables" exception that aborts deletion sync mid-pass.

### P3 — `RP-HD-016` · `softInputMode` restored to a value that was never the app default

`SerializedAssetEditFragment.kt:56-61`, `:185-190`

There is **no `windowSoftInputMode` anywhere** in the manifest or themes (verified: 0 occurrences), so the comment at `:59` ("the window softInputMode is app-wide and other screens leave it in ADJUST_PAN") is false. `MainActivity.kt:198-206` derives `hiddenSoftInputMode` from the window's actual default and ORs in `SOFT_INPUT_STATE_ALWAYS_HIDDEN`, re-applying on every destination change (`:278-314`). Setting `SOFT_INPUT_ADJUST_PAN` in `onDestroyView` replaces the whole mode, clearing `STATE_ALWAYS_HIDDEN`.

**Repro:** detail → Edit → Back. The detail screen is now `ADJUST_PAN` with no keep-hidden guard until the next destination change; a soft keyboard pans the window (toolbar scrolls off) instead of resizing. Self-heals on next navigation, hence P3. `SetLatestAverageFragment.kt:51/163` has the same pre-existing bug — this change copied it.

**Fix:** capture `activity?.window?.attributes?.softInputMode` in `onViewCreated`, restore that exact value.

Related doc defect in the same change: the Kotlin comment (`:57-58`) says RESIZE "keeps the pinned Save button visible" while the new layout comment (`fragment_serialized_asset_edit.xml:260-262`) says Save is deliberately **inside** the scroll and not pinned. One is stale. The new root `LinearLayout` with a single `0dp`/`weight=1` child (`:1-16`) is now equivalent to the `match_parent` it replaced — leftover scaffolding from the pinned-button attempt.

### P3 — `RP-HD-017` · two serialized fragments retain their destroyed view tree on the back stack

`SerializedRoomEquipmentFragment.kt:43-55`, `SerializedEquipmentPoolFragment.kt:41-49` — `lateinit var` view fields plus fragment-scoped `SerializedEquipmentAdapter` instances, and neither overrides `onDestroyView`. Navigating to the detail hub (now the primary interaction) destroys the view while the fragment stays alive on the back stack, holding the hierarchy; the long-lived adapter keeps the destroyed `RecyclerView` alive via its data observer. `PlacementHistoryFragment.kt:118-122` does this correctly (`historyList.adapter = null`).

**Fix:** add `onDestroyView` clearing `list.adapter = null`; prefer ViewBinding as the neighbours do.

### P3 — `RP-HD-018` · pre-migration equipment rows won't auto-merge

`ProjectMetadataSyncService.kt:94-96` carries the acknowledgement in-code: "pre-migration equipment won't auto-merge (serverId=null, no catalogUuid for matching)". Rows predating migration 31→32 can linger as orphans alongside freshly pulled pivots — duplicate display risk. Called a "known gap — future cleanup pass" in code; previously untracked.

### P3 — `RP-HD-019` · serialized "Total Equipment" routes through the legacy count screen

`RocketDryFragment.kt:147-148`, `:544-550`, `fragment_rocket_dry.xml:279-296`. The serialized-only button navigates to `totalEquipmentFragment` — the legacy count screen with quantity ±, date pickers and delete — which only then forwards to the pool from `gateOnOwnerMode()` (`TotalEquipmentFragment.kt:127-161`) after an IO read of the project's `companyId`. A serialized company briefly mounts legacy write UI titled "Total Location Equipment" before being bounced.

Not a data risk: legacy writes are rejected by `legacyWritable()` (`TotalEquipmentViewModel.kt:82-93`) and the action has `popUpTo=totalEquipmentFragment` + `popUpToInclusive=true` (`mobile_navigation.xml:562-568`), so Back correctly returns to RocketDry. But `RocketDryFragment` already knows `mode == ON` when it shows the button, so the detour is avoidable — add a direct `rocketDryFragment → serializedEquipmentPoolFragment` action.

---

## Notes — verified, deliberately not filed

- `EquipmentAssetSyncService.kt:52/:115` mint local PKs as `-System.currentTimeMillis()`. Two register/deploy calls in the same millisecond collide on the PK and `@Upsert` silently overwrites the first row, losing its uuid and orphaning its queue op. User-paced flows make this improbable; a monotonic counter would remove it.
- `EquipmentAssetPullService.kt:288-291` early-returns on **zero** server placements, contradicting the "authoritative per asset (incl. empty history)" claim at `:81`; a server-side wipe leaves stale local rows.
- `handleCorrect` (`EquipmentAssetPlacementPushHandler.kt:241-256`) has no `isMissingOnServer()` branch — a 404 (placement deleted elsewhere) falls into `RETRY` until the queue exhausts retries.
- `SecureStorage.clearAllData()` (`:410-427`) does not remove per-company `serialized_equipment_<id>` keys, so the flag survives sign-out. Benign today: login/`setActiveCompany` clear-then-refetch (`AuthRepository.kt:463-470`, `:596-599`).
- **RP-CD-006 hygiene:** `CreateEquipmentCatalogRequest.name`, `AttachRoomEquipmentRequest.quantity`/`uuid`, `EquipmentRoomUpdateRequest.quantity`/`duration` (`OfflineDtos.kt:201-230`) lack `@SerializedName`. No live failure — `proguard-rules.pro:71-72` keeps `data.model.**` — but it violates the rule.
- Hardcoded strings `fragment_project_landing.xml:312` (`"RocketDry"`) and `:321` — the only two hardcoded `android:text` values in that file; the uncommitted diff moved these exact lines. Folded into `RP-HD-012` rather than filed separately.
- Envelope flags `EquipmentAssetResponse.idempotent` / `EquipmentAssetPlacementResponse.idempotency` are asserted by tests but **never read in `src/main`** — the parse tests imply replay handling production code doesn't do. Harmless; server-side `idempotency_key` does the work.
- `ProjectsViewModel.kt:92-96` reads `existing?.createdAt ?: parse(createdAt) ?: timestamp`, so a project row whose first local insert predated a populated `created_at` keeps its sync-time value forever and sorts wrong on upgraded installs without a DB wipe.
- `DeletedRecordsSyncService` carries no equipment-asset/placement keys, so server-side asset deletions reach the client only via the pool-pull reconcile (which is tested). Server→client direction, so not user-data loss. Verify against the backend `/api/sync/deleted` payload before treating it as a gap.

## Verified correct — do not re-chase

**Sync/data:** `mergePulledRowsByServerId` is used on all new pull paths with the uuid fallback and `adoptServerIdentity` (`LocalDataService.kt:1194-1220`, `:2462-2478`); pull paths pass `preserveDirty = true` (`ProjectMetadataSyncService.kt:123`, `EquipmentAssetPullService.kt:310`) or hand-roll an equivalent field merge (`adoptOrMerge`); parent-id resolution is push-time `parent.serverId` + `SKIP` throughout, with no `IdRemapService` use; the deploy→move/check-out queue-op compaction hazard from dropping `removeSyncOperationsOfType` is correctly handled (`EquipmentAssetSyncService.kt:165-181`, `:205-224`); `SQLITE_MAX_BIND_VARS` chunking is applied consistently and `markRoomsDeleted` keeps its per-chunk transaction (RP-CD-007); the new migration's CREATE TABLE column lists match the entities exactly; `api/auth/user/feature-flags` is now correctly `data`-wrapped and fully nullable. No cross-company flag leak found — per-company caching, entity-derived gating in `PushHandlerContext.serializedModeFor`, and `companyId`-scoped DAO queries all check out.

**UI:** every changed fragment collects in `viewLifecycleOwner.lifecycleScope` + `repeatOnLifecycle(STARTED)` — no bare `lifecycleScope`; all three ViewBinding fragments null `_binding` in `onDestroyView`, and `PlacementHistoryFragment.kt:118-121` touches `binding` before the null-out; the 60s mode-poll `while(true)` loops are inside `repeatOnLifecycle` so they cancel on STOP. New nav args `deployProjectId`/`deployRoomId` (`mobile_navigation.xml:824-834`) use the required `-1L` default form for `argType="long"` and match the `!= -1L` reads (`SerializedAssetDetailFragment.kt:137`). No layout/Kotlin id mismatches: `serializedTotalEquipmentButton` sits inside `equipmentContentGroup` (can't leak onto Moisture), all four swapped ids in `fragment_project_landing.xml` exist exactly once, no missing `@string` refs. The tab-switch visibility hole is closed — `showTab()` (`RocketDryFragment.kt:265-274`) re-runs `renderEquipmentContent`; UNKNOWN mode hides both entries and feeds `emptyList()`.

**Tests:** 11 golden fixtures across `fixtures/{equipment,equipment_assets}/` cover nearly every serialized endpoint (only gap: no post-lifecycle asset fixture for move/check-out, where status flips to `available` and `current_placement` nulls — low risk, shared envelope). Push-handler and sync coverage is genuinely thorough: `EquipmentAssetPushHandlerTest` (13), `EquipmentAssetPlacementPushHandlerTest` (24), `EquipmentAssetSyncServiceTest` (24), `EquipmentAssetPullServiceTest` (11), `MergePulledRowsByServerIdTest` (7). Zero `@Ignore`, zero skipped, no stub files — the earlier sweep's "skipped test files" residual does not apply here. `EquipmentAssetPullService.kt:225` deliberately calls `saveEquipmentAssets(merged)` without `preserveDirty` because it field-merges instead, and that behaviour is pinned by tests — **this area is fine, do not file work against it.**
