**Bug ID(s):** RP-BUG-279
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Plan](./plan_rp_bug_279_equipment_writesync_pivot_realignment_2026-07-09.md) · Review: pending · Prerequisite for: [RP-FR-018](./plan_rp_fr_018_equipment_move_and_tracking_2026-07-09.md) · Backend spec: `mongoose:MONGOOSE-FR-014` §8 · **Backend prerequisite: `mongoose:MONGOOSE-BUG-036`** (see [Backend dependency](#backend-dependency-mongoose-bug-036--this-ticket-is-not-android-only))

# Fix Plan: [RP-BUG-279] Realign offline equipment write-sync to the real pivot (equipment_room) contract

**Bug ID(s):** RP-BUG-279
**Author:** Claude
**Date:** 2026-07-09 (registered 2026-07-09 15:12:04 PDT)
**State:** planned
**Priority:** P1
**Classification:** new_code_bug · **Release:** unreleased

---

## Summary

Android's offline equipment write-sync targets a backend equipment contract that **does not exist**. Every offline equipment create/edit/delete either silently drops fields, 422-drops, or no-ops — so equipment written on Android **never round-trips to the server's placement model**, and iOS/webapp (which use the real pivot endpoints) never see Android's equipment. This code is on `master` (shipped in 1.29/1.30).

The backend models equipment as **two things**: a per-project **catalog type** (`equipment` row: `name`, `is_standard`, `catalog_uuid`, NOT-NULL `project_id`) plus an **`equipment_room` pivot** placement (its own `id`, `quantity`, `duration`, `date_in`, `date_out`, `number`, `uuid`). Android instead models a single flat `OfflineEquipmentEntity` with a mutable `roomId` (`data/local/entity/OfflineEntities.kt:538`), conflating catalog and placement, and pushes it to routes the server never defined.

This fix realigns Android's write path (and the currently-unused read path) to the real pivot endpoints **before** the Move/Tracking feature (RP-FR-018) is layered on. It is independent of the backend Move work (MONGOOSE-FR-014 §5). The pivot endpoints it targets already exist in production, **but they are not yet sufficient for offline UUID reconciliation** — the attach endpoint does not accept a client pivot `uuid`/`date_in`/`quantity` and the room-equipment read returns `uuid: null`. RP-BUG-279 therefore carries a **hard backend prerequisite, `mongoose:MONGOOSE-BUG-036`** — see [Backend dependency](#backend-dependency-mongoose-bug-036--this-ticket-is-not-android-only) below. This is **not a pure-Android ticket**.

## Backend dependency (MONGOOSE-BUG-036) — this ticket is NOT Android-only

RP-BUG-279 has a **hard backend prerequisite**: the room-equipment **attach** and **read** contract must round-trip the pivot `uuid` (plus `date_in` + `quantity`). The pivot endpoints exist in production, but today's production contract does **not** carry the pivot `uuid` (verified in the backend):

- `StoreRoomEquipmentRoomRequest` accepts only `idempotency_key` + `equipment_ids[]`. It does **not** accept a client pivot `uuid`, `date_in`, or `quantity`.
- `RoomEquipmentRoomController@store` writes only `equipment_id`, `room_id`, `number`, `idempotency_key` — `quantity` falls to the DB default of `1`, and the client pivot `uuid` is dropped.
- `Room::equipment()` `withPivot([...])` does **not** include `uuid`, so `EquipmentResource` emits `uuid: null` on the room-equipment read (`GET /api/rooms/{roomId}/equipment`).

**Why this blocks the Android fix:** the whole offline model reconciles placements by client-minted `uuid` (Step 2 sends `uuid` on attach; Step 5 reconciles pulled pivots by `uuid` first). With today's contract the attach call **drops** the client `uuid` so the server-assigned pivot can't be matched back to the local offline row, and the read returns `uuid: null` so the UUID-first reconcile has nothing to match on. **UUID-based offline reconciliation of placements is therefore not possible** on the current endpoints — Android cannot reliably persist or reconcile offline-created placements by UUID.

**Prerequisite ticket:** `mongoose:MONGOOSE-BUG-036` — *"Room equipment attach endpoint accepts client pivot `uuid`/`date_in`/`quantity`; add `uuid` to `Room::equipment()` withPivot so reads return it."* Backend prerequisites are also captured as a dedicated section in `mongoose:docs/plans/MONGOOSE-FR-014_equipment_move_and_tracking_2026-07-09.md`.

**Coordination:** RP-BUG-279 is **not purely an Android change** — it requires the backend work in MONGOOSE-BUG-036 to ship first (or land alongside). The Android write-path rewrite below can be built against the corrected contract, but **cannot round-trip or be verified end-to-end** until MONGOOSE-BUG-036 is deployed. Every place below that assumes the attach body carries `uuid`/`date_in`/`quantity` or that the read returns a non-null pivot `uuid` is gated on this prerequisite.

## The divergence (verified — cite file:line)

The Retrofit surface targets non-existent routes:

| Site | Call | Reality |
|------|------|---------|
| `data/api/OfflineSyncApi.kt:490` | `updateEquipment` → `PUT /api/equipment/{equipmentId}` | **No such route.** |
| `data/api/OfflineSyncApi.kt:496` | `deleteEquipment` → `DELETE /api/equipment/{equipmentId}` | **No such route.** |
| `data/api/OfflineSyncApi.kt:484` | `createProjectEquipment` → `POST /api/projects/{projectId}/equipment` | Route exists but `StoreProjectEquipmentRequest` reads **only** `name` + `idempotency_key`; `room_id`, `quantity`, dates, `status`, brand/model/serial silently dropped. Standard type names collide on the `unique` name rule → 422. |
| `data/api/OfflineSyncApi.kt:479` | `getRoomEquipment` → `GET /api/rooms/{roomId}/equipment` | Route exists (returns real pivots) but is **unused** — Android reads the catalog via `getProjectEquipment` (`:474`) instead of `equipment_room` placements. |

Runtime trace (in `data/repository/sync/handlers/EquipmentPushHandler.kt`):
- **update:** `pushPendingEquipmentUpsert` calls `updateEquipment` (`EquipmentPushHandler.kt:214`) → 404 → `isMissingOnServer()` (`SyncHandlerUtils.kt:74`, matches 404/410) true → recreate via `createProjectEquipment` (`EquipmentPushHandler.kt:229`) → duplicate-name **422** → `isValidationError()` → `OperationOutcome.DROP` (`EquipmentPushHandler.kt:68-74`). Op silently dropped.
- **delete:** `handleDelete` calls `deleteEquipment` (`EquipmentPushHandler.kt:97`) → 404 → `resolveDeleteWithStaleRetry` (`SyncHandlerUtils.kt:36` maps 404/410 → `null` = success) → silent local-only no-op.
- **create:** `createProjectEquipment` (`EquipmentPushHandler.kt:212`) sends the full `EquipmentRequest` (`data/model/offline/OfflineDtos.kt:201`) but the server keeps only `name`; placement fields (`room_id`, `quantity`, `start_date`/`end_date`, `status`) are dropped.

**Net:** equipment created/edited/deleted on Android does not reach the server's placement model.

## The real backend contract Android must adopt (the pivot model — exists today)

| Intent | Call |
|--------|------|
| Place / bulk-attach a catalog type into a room (server assigns `number`) | `POST /api/rooms/{roomId}/equipment` body `{ equipment_ids: [int], uuid, idempotency_key, room_uuid?, date_in?, quantity? }` — **`uuid`/`date_in`/`quantity` are accepted only after `mongoose:MONGOOSE-BUG-036`; today's `StoreRoomEquipmentRoomRequest` takes only `equipment_ids` + `idempotency_key`** (see [Backend dependency](#backend-dependency-mongoose-bug-036--this-ticket-is-not-android-only)). |
| Create a custom catalog name (catalog row only, if the type doesn't exist) | `POST /api/projects/{projectId}/equipment` body `{ name, idempotency_key }` |
| Update a placement | `PUT /api/equipment-rooms/{id}` body `{ quantity, duration, date_in, date_out, updated_at }` |
| Delete a placement | `DELETE /api/equipment-rooms/{id}` (optional `updated_at`) |
| Read placements for a room | `GET /api/rooms/{roomId}/equipment` → resource collection wrapped as `{ "data": [ … ] }` (unpaginated — `RoomEquipmentRoomController@index` returns `EquipmentResource::collection(...)`, no `withoutWrapping`). Each item is an `EquipmentRoom` pivot (`id`=pivot id, `equipment_id`, `room_id`, `quantity`, `duration`, `date_in`, `date_out`, `number`, `uuid`, `name`, `display_name`). **Caveat:** `uuid` is `null` on today's contract — it only becomes non-null after `mongoose:MONGOOSE-BUG-036` adds `uuid` to `Room::equipment()` withPivot (see [Backend dependency](#backend-dependency-mongoose-bug-036--this-ticket-is-not-android-only)). |

**Model shift:** equipment is a per-project **catalog type** + an **`equipment_room` pivot** placement (the pivot has its own `id`), NOT a flat row with a mutable `room_id`. The `id` used for update/delete is the **pivot id**, not the catalog id; the catalog id is what you attach.

## Affected Code

| File:Line | Change |
|-----------|--------|
| `data/api/OfflineSyncApi.kt:479` (`getRoomEquipment`) | **Fix the response shape.** The endpoint returns a Laravel resource collection wrapped as `{ "data": [...] }` (no `withoutWrapping`; `RoomEquipmentRoomController@index` returns `EquipmentResource::collection(...)`, unpaginated). The current declaration `List<EquipmentDto>` will **not** deserialize the wrapped body. Change the return type to a `{data:[...]}` wrapper — use `SingleDataResponse<List<EquipmentDto>>` (the repo's plain data-only wrapper). This endpoint is **NOT paginated**, so do not use `PaginatedResponse<T>` for its semantics — though note the sibling `getProjectEquipment` (`:474`) already types a similar `{data:[...]}` body as `PaginatedResponse<EquipmentDto>` and deserializes fine because that wrapper's `links`/`meta` are nullable. Read `.data` at the call site. |
| `data/api/OfflineSyncApi.kt:479-500` | Remove `updateEquipment` (`PUT /api/equipment/{id}`) and `deleteEquipment` (`DELETE /api/equipment/{id}`). Add `attachRoomEquipment` (`POST /api/rooms/{roomId}/equipment`, body of `equipment_ids` + uuid + idempotency), `updateEquipmentRoom` (`PUT /api/equipment-rooms/{id}`), `deleteEquipmentRoom` (`DELETE /api/equipment-rooms/{id}`). Keep `createProjectEquipment` but scope it to **catalog-name creation only**. Wire `getRoomEquipment` (`:479`) into the read path. |
| `data/model/offline/OfflineDtos.kt:201` (`EquipmentRequest`) | Split into (a) a catalog-create request (`{ name, idempotency_key }`), (b) a room-attach request (`{ equipment_ids, uuid, room_uuid?, idempotency_key, date_in? }`), (c) a pivot-update request (`{ quantity, duration, date_in, date_out, updated_at }`). |
| `data/model/offline/OfflineDtos.kt:653` (`EquipmentDto`) | Model the pivot read shape from `GET /api/rooms/{roomId}/equipment`: pivot `id`, `equipment_id`, `room_id`, `quantity`, `duration`, `date_in`, `date_out`, `number`, `uuid`, `name`, `display_name`. Gson-lenient — keep unknown-field tolerance. |
| `data/local/entity/OfflineEntities.kt:538` (`OfflineEquipmentEntity`) | Represent the catalog + pivot split (see Step 1). At minimum add a pivot `serverId` (for `equipment-rooms/{id}` update/delete) distinct from the **catalog** id (for attach), plus `catalogUuid`. Room migration required. |
| `data/repository/sync/handlers/EquipmentPushHandler.kt` | Rewrite `handleUpsert` / `handleDelete` / `pushPendingEquipmentUpsert` / `handle409Conflict` to the catalog-then-attach create flow, `equipment-rooms/{id}` update, and `equipment-rooms/{id}` delete. |
| `data/repository/mapper/SyncEntityMappers.kt` | `toRequest`/`toEntity` mappers for the new request/DTO shapes; carry the pivot id and catalog id/uuid through. |
| `data/local/dao/OfflineDao.kt:801` (`migrateEquipmentRoomIds`) + read queries `:795-799` | Reads still key on `roomId` (fine); ensure the pivot serverId is populated on save so update/delete resolve it. |

## Implementation Notes

### Step 1: Model the catalog/pivot split locally

`OfflineEquipmentEntity` (`OfflineEntities.kt:538`) currently has one `serverId` and treats `roomId` as mutable placement. Introduce a clear separation:

```kotlin
data class OfflineEquipmentEntity(
    @PrimaryKey(autoGenerate = true) val equipmentId: Long = 0,
    // pivot placement identity (equipment_room.id) — used for PUT/DELETE /api/equipment-rooms/{id}
    val serverId: Long? = null,          // REINTERPRET: this is the PIVOT id, not the catalog id
    // catalog identity — used to attach via POST /api/rooms/{roomId}/equipment { equipment_ids }
    val catalogServerId: Long? = null,   // NEW — equipment.id (the catalog type)
    val catalogUuid: String? = null,     // NEW — stable catalog identity (catalog_uuid)
    val uuid: String,                    // pivot uuid (offline id sent on attach)
    val projectId: Long,
    val roomId: Long? = null,            // current placement (unchanged semantics)
    // ...quantity/status/dates/brand/model/serial unchanged...
)
```

- `serverId` becomes the **pivot** id (`equipment_room.id`). This is the id used for update/delete. Migrate existing rows: their current `serverId` was a catalog id under the broken contract and cannot be trusted as a pivot id — on next pull, reconcile from `GET /api/rooms/{roomId}/equipment` by `uuid`/`equipment_id` (see Step 5). Prefer nulling stale `serverId` at migration so the next write re-attaches rather than PUTs a wrong pivot id.
- Add a Room migration (bump `OfflineDatabase` version; follow the RP-BUG-032 nullable-column pattern — `ADD COLUMN catalogServerId INTEGER`, `ADD COLUMN catalogUuid TEXT`). Nullable so existing rows backfill as NULL.

### Step 2: Create flow = ensure catalog type, then attach placement

Replace the broken "create catalog with full payload" call. For an offline-created placement whose `catalogServerId` is unknown:

1. Resolve the catalog type in the project's catalog (equipment is auto-seeded per project by the backend `GenerateEquipmentForProject`). If a matching standard type exists, use its `equipment_id`. Only if the user typed a genuinely custom name that isn't in the catalog, `POST /api/projects/{projectId}/equipment { name, idempotency_key }` to mint a catalog row, then read back its id.
2. `POST /api/rooms/{roomServerId}/equipment` with `{ equipment_ids: [catalogServerId], uuid, room_uuid?, idempotency_key, date_in? }`. The server creates the pivot and assigns `number`.
3. Persist the returned pivot `id` into `serverId`, and `catalogServerId`/`catalogUuid` for future updates.

This removes the duplicate-name 422 (the current failure) — standard names are attached by id, never re-created.

### Step 3: Update flow = PUT the pivot

Once `serverId` (pivot id) is known, edits (`quantity`, dates, `duration`) go to `PUT /api/equipment-rooms/{serverId}` with the optimistic-lock `updated_at`. Keep the existing 409 recovery (`handle409Conflict`, `extractUpdatedAt` at `SyncHandlerUtils.kt:83`) — it already targets a body-embedded `updated_at`, which the `equipment-rooms` update contract returns. **Do not** fall back to "recreate on 404" the way the current code does (`EquipmentPushHandler.kt:228`); a 404 on a pivot means the placement was deleted server-side — resolve as a delete/skip, not a re-create, to avoid re-spawning placements.

### Step 4: Delete flow = DELETE the pivot

`DELETE /api/equipment-rooms/{serverId}` (optional `updated_at`). Keep `resolveDeleteWithStaleRetry` (`SyncHandlerUtils.kt:28`) — 409 stale-lock retry-without-lock and 404/410 → success semantics are still correct for a real pivot. The current `handleDelete` for `serverId == null` (never reached server) resolving locally (`EquipmentPushHandler.kt:87-91`) stays valid, but "never reached server" now means "no pivot id yet".

### Step 5: Read placements from the pivot, not the catalog

Wire `getRoomEquipment` (`OfflineSyncApi.kt:479`, currently unused) into the pull path so local equipment reflects real `equipment_room` placements (with pivot `id`, `number`, `quantity`). First fix its return type: the endpoint returns `{ "data": [...] }`, so declare it as `SingleDataResponse<List<EquipmentDto>>` (a plain, non-paginated data wrapper) and read `.data` — the current `List<EquipmentDto>` cannot deserialize the wrapped body (see Affected Code). Reconcile pulled pivots against local rows by `uuid` first, then `equipment_id`+`room_id` (mirrors the RP-BUG-037/038 serverId-reconcile approach), backfilling `serverId` (pivot id), `catalogServerId`, `catalogUuid`. This is also what repairs the Step-1 migration's nulled `serverId`. **Prerequisite:** the `uuid`-first reconcile only works once `mongoose:MONGOOSE-BUG-036` makes the read return a non-null pivot `uuid`; until then only the `equipment_id`+`room_id` fallback is available (see [Backend dependency](#backend-dependency-mongoose-bug-036--this-ticket-is-not-android-only)). Keep the catalog read (`getProjectEquipment`) only as the source of the **type picker** list, not as the placement source.

### Step 6: Parent-gating unchanged, but gate on room pivot attach

The existing waits — project not synced (`EquipmentPushHandler.kt:28-40`) and room not synced (`:42-56`) — remain necessary: attach needs the **server** `roomId` and the placement needs the project's catalog. Keep the `SKIP`-until-parent-synced behavior.

## Observability

- Remote WARN when a create cannot resolve a catalog type (would previously have 422-dropped): category `equipment_catalog_unresolved`, fields `equipmentUuid`, `projectId`, `typeName`.
- Remote WARN when an update/delete has no pivot `serverId` after pull reconcile (surfaces Step-5 gaps): `equipment_pivot_id_missing`.
- Keep the existing 409 conflict WARN (`EquipmentPushHandler.kt:135`). Prefer local `Log.debug` for the per-op happy-path trace; reserve remote logs for terminal/actionable states (tracker remote-logging guidance).

## Test Plan

- [ ] Unit: create flow attaches a standard type by id (`POST /api/rooms/{id}/equipment`) — no `POST /api/projects/{id}/equipment` call, no 422.
- [ ] Unit: custom-name create mints a catalog row then attaches; pivot id persisted to `serverId`.
- [ ] Unit: update goes to `PUT /api/equipment-rooms/{pivotId}` with `updated_at`; 409 → existing conflict recovery; a 404 does NOT re-create.
- [ ] Unit: delete goes to `DELETE /api/equipment-rooms/{pivotId}`; 409 stale → retry without lock; 404/410 → success.
- [ ] Unit: `getRoomEquipment` deserializes the wrapped `{ "data": [...] }` body (regression on the `List<EquipmentDto>` → `SingleDataResponse<List<EquipmentDto>>` return-type fix, `OfflineSyncApi.kt:479`).
- [ ] Unit: pull maps `GET /api/rooms/{id}/equipment` pivots and reconciles by `uuid`/`equipment_id` (no duplicate local rows — RP-BUG-037/038 parity). Requires MONGOOSE-BUG-036 for the `uuid`-first path; assert the `equipment_id`+`room_id` fallback independently.
- [ ] Migration test: open pre-migration DB with equipment rows → migrate → `catalogServerId`/`catalogUuid` NULL, rows survive.
- [ ] Manual QA: offline-add equipment to a room on Android → sync → the placement appears on iOS/webapp with correct quantity + room; edit quantity offline → sync → reflected everywhere; delete offline → sync → gone everywhere.

## Rollback Plan

Schema change involved (new nullable columns + version bump): prefer rolling forward. Reverting before release is clean (drop the migration/fields and restore the old Retrofit methods). Because the old path never round-tripped writes correctly, there is no server-side state to unwind on rollback. Note the API-surface change (removed `PUT`/`DELETE /api/equipment/{id}`) must land atomically with the handler rewrite.

## Dependencies

- **Requires (backend prerequisite):** `mongoose:MONGOOSE-BUG-036` — the room-equipment attach endpoint must accept the client pivot `uuid`/`date_in`/`quantity`, and `Room::equipment()` withPivot must include `uuid` so the read returns it. Without this, offline UUID reconciliation of placements is not possible (see [Backend dependency](#backend-dependency-mongoose-bug-036--this-ticket-is-not-android-only)). **This makes RP-BUG-279 a coordinated Android + backend ticket, not Android-only.**
- **Requires (already exist):** the pivot endpoints themselves (`POST /api/rooms/{id}/equipment`, `PUT`/`DELETE /api/equipment-rooms/{id}`, `GET /api/rooms/{id}/equipment`) exist in production today; this fix does NOT depend on the MONGOOSE-FR-014 Move work. The **contract extension** for those endpoints is what MONGOOSE-BUG-036 supplies.
- **Blocking:** prerequisite for **RP-FR-018** (Equipment Move & Tracking) — move ops are layered on the pivot model this fix establishes.
- **Related:** RP-FR-002 (409 body-drain, `EquipmentPushHandler`), RP-BUG-037/038 (serverId reconcile on pull), RP-BUG-040 (delete stale-timestamp 409). All currently `unreleased`; this rewrite preserves their fixes and must not regress them.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-279] Equipment added, edited, or removed offline on Android now syncs to the server correctly and is visible on iOS and the web app. Previously these changes targeted API routes that don't exist and were silently dropped.
```
