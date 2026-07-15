# RP-FR-019 — Serialized Equipment (Android client)

**Status:** implementation complete (data / sync / gating / UI) + 8 review rounds + self-review; **pending on-device enabled-company verification** · **Branch:** `feat/RP-FR-019-serialized-equipment` (PR #8, draft, stacked on cleanup PR #7) · **Supersedes:** RP-FR-018 · **RP-BUG-279:** DESCOPED — still open (the legacy flag-OFF write-sync remains unfixed; this plan does not address it)

> **Status update (2026-07-14).** Everything below landed and is unit-tested (APK builds clean): the contract, persistence + `MIGRATION_30_31`, the full sync spine (register/update/retire/deploy/move/check-out) with the authoritative pull + two-axis metadata/lifecycle merge, per-company **observable** mode + write-boundary gate, the **legacy-wide RocketDry cutover gate** (EquipmentRoom + TotalEquipment write guards + RocketDry tab hide), and the **register-catalog + move-room pickers**. What remains is external/verification only: on-device enabled-company E2E, instrumented Fragment/nav transition tests, and a backend company-scoped mode endpoint / push (currently a 60s foreground poll). NOTE: the "re-fix RP-BUG-279" goal in the Context below was **descoped** — RP-FR-019 replaces legacy equipment only for flag-ON companies; the legacy (flag-OFF) pivot write-sync is still broken and RP-BUG-279 stays open as its own item.
**Backend:** `mongoose` (branch `dev`), `MONGOOSE-FR-014` / `WEBAPP-FR-007` (timeline). Contract verified against controllers/resources/form-requests 2026-07-13.

## Context

The prior count-based move/transfer work (RP-FR-018) + its RP-BUG-279 pivot fix were reverted (`dea4ff3`, archived at `backup/android-count-equipment-move-transfer-2026-07-13`) because they targeted a backend contract that no longer exists. This is a **greenfield** rebuild: no shipped production users, no field data to migrate or cut over.

Serialized equipment tracks each unit as an individual **asset** in a company pool, with a **placement** history (deploy → move → check-out). It is a **separate subsystem** from the legacy count-based `OfflineEquipmentEntity` — new entities, DAO methods, DTOs, sync handlers, and UI. Legacy count code stays for the flag-OFF path (and gets the RP-BUG-279 corrected pivot fix).

## Backend contract (authoritative summary)

**Feature flag `serializedEquipment`** — company-scoped, **fails closed**. Read at `GET /api/auth/user/feature-flags` → JSON path `data.values.serializedEquipment` (bool). Resolved server-side against the caller's *active* company. When OFF: serialized list endpoints return empty page, serialized write endpoints 403. When ON: legacy write endpoints (`POST rooms/{r}/equipment`, `PUT/DELETE equipment-rooms/{id}`) 409.

**Idempotency** — header `Idempotency-Key` OR body `idempotency_key` (≤64). Replay-success flag key differs: **`idempotency`** (check-in) vs **`idempotent`** (register/move/check-out). Client uses the local `uuid` as the key.

**Optimistic lock** — `updated_at` required on asset update, move, check-out; 409 `{message, current_updated_at, resource_id}` on mismatch (second precision).

**Wrappers** — singular = `{data:{...}}`; company `index`/`timeline` = Laravel paginated (`{data:[],links,meta}`, timeline has `meta` only, no `links`); placement/room lists = hand-wrapped `{data:[...]}`. Decimals serialize as **strings** (`"1899.00"`). Dates = microsecond ISO8601.

### Endpoints
| # | Method / path | Req body (required**) | Returns |
|---|---|---|---|
| 1 | `GET /companies/{c}/equipment-assets` | query: status, catalog_uuid, search, per_page, page | paginated `EquipmentAssetResource` |
| 2 | `POST /companies/{c}/equipment-assets` | **catalog_uuid, name**; optional manufacturer/model/is_standard/serial_number/asset_tag/purchase_date/purchase_price/vendor/warranty_expires_at/rental_day_rate/note/idempotency_key | 201 `{data:asset}` / 200 `{data,idempotent}` |
| 3 | `GET /companies/{c}/equipment-asset-timeline` | query: page, per_page | `{data:[{project,bars[]}], meta}` |
| 4 | `GET /equipment-assets/{id}` | — | `{data:asset}` (+company, current_placement, placements) |
| 5 | `PUT /equipment-assets/{id}` | **updated_at**; optional manufacturer/model/serial_number/asset_tag/vendor/note/status(in:available,maintenance)/purchase_date/warranty_expires_at/purchase_price/rental_day_rate | `{data:asset}`; 409 stale; 422 status-change w/ open placement |
| 6 | `DELETE /equipment-assets/{id}` (retire) | — | 204; 422 already-retired / open placement |
| 7 | `GET /equipment-assets/{id}/placements` | — | `{data:[placement]}` desc by date_in |
| 8 | `POST /equipment-assets/{id}/placements` (deploy/check-in) | **room_id**; optional date_in/note/idempotency_key; date_out **prohibited** | 201 `{data:placement}` / 200 `{data,idempotency}`; 403 cross-company; 422 room-not-on-project/already-deployed/overlap |
| 9 | `POST /equipment-assets/{id}/move` | **to_room_id, updated_at**; optional moved_at/note/idempotency_key | 201 `{data:asset}` / 200 `{data,idempotent}`; 422 no-open/same-room |
| 10 | `POST /equipment-assets/{id}/check-out` | **updated_at**; optional date_out/idempotency_key | 200 `{data:asset}` / `{data,idempotent}`; 422 no-open/date-before |
| 11 | `GET /rooms/{room}/equipment-assets` | — | `{data:[asset]}` (open placement in room); 403 flag-off/cross-company |

### DTO field lists
**EquipmentAssetResource:** id(int), uuid, company_id(int), catalog_uuid, name, manufacturer?, model?, is_standard(bool), serial_number?, asset_tag?, status, current_placement_id?, purchase_date?, purchase_price?(string), vendor?, warranty_expires_at?, rental_day_rate?(string), idempotency_key?, note?, created_at, updated_at; conditional: company, current_placement, placements[], room, project. Required: id, uuid, company_id, catalog_uuid, name, is_standard, status, created_at, updated_at.
**EquipmentAssetPlacementResource:** id, uuid, equipment_asset_id, room_id, project_id, date_in?, date_out?, placed_by_user_id?, note?, idempotency_key?, created_at, updated_at, is_open(bool); conditional asset/room/project/placed_by.
Statuses: `available|deployed|maintenance|retired`.

## Implementation layers

### Phase 1 — offline-first spine (this session)
- **A. Contract:** `EquipmentAssetDtos.kt` (asset/placement/timeline DTOs + 5 request DTOs, `idempotent`/`idempotency` replay flags), `EquipmentAssetApi.kt` (11 endpoints). Golden fixtures under `app/src/test/resources/fixtures/equipment_assets/` derived from backend `tests/Schemas/*` + Resource shapes; parse tests asserting each DTO deserializes list/single/timeline/attach/move/checkout.
- **B. Persistence:** `OfflineEquipmentAssetEntity` + `OfflineEquipmentPlacementEntity` (standard syncable shape: local PK + serverId? + unique uuid + sync bookkeeping). DAO methods in `OfflineDao.kt` (upsert, observe-by-room/company filtered isDeleted, by-uuid, by-serverIds, pending). Migration `MIGRATION_30_31` (CREATE TABLE IF NOT EXISTS + indexes), bump `version=31`, register. Migration test mirroring `OfflineDatabaseMigrationTest`.
- **C. Mappers + save:** `EquipmentAssetDto.toEntity(existing)` / `.toRequest()` in a new mapper file; `LocalDataService.saveEquipmentAssets(..., preserveDirty)` + placements, reusing `mergePulledRowsByServerId` (adopt local identity to avoid RP-BUG-037 dupes).
- **D. Sync:** entityType strings `"equipment_asset"` / `"equipment_asset_placement"` (reuse CREATE/UPDATE/DELETE op types). `EquipmentAssetPushHandler` (register/update/retire) + `EquipmentAssetPlacementPushHandler` (deploy/move/checkout). Parent-id SKIP-until-ready (company/room/asset serverId), 409 conflict recovery via `extractUpdatedAt`, idempotency via uuid, optimistic lock. Wire into `SyncQueueProcessor` dispatch + `isEntityDeleted`; add enqueuer methods. `EquipmentAssetSyncService` write orchestrator.
- **E. Flag:** add `@SerializedName("serialized_equipment") serializedEquipment: Boolean?` to `FeatureFlagValues`; `SERIALIZED_EQUIPMENT_KEY` (+ save/get/clear, scoped alongside `company_id`) in `SecureStorage`; fetch+cache in `AuthRepository` on user-context refresh. Expose a mode reader with three states: **ON / OFF / UNKNOWN** (flag never fetched or fetch failed).

### Phase 2 — UI (next session, flag-gated)
Room asset list + company pool; register/deploy/move/check-out/history/retire flows. Mount rules: ON → serialized UI only; OFF → legacy count UI only; UNKNOWN → mount neither write system, show retryable state (never default to legacy). Flag defaults **closed**, so until Phase 2 ships nothing serialized mounts — safe to land Phase 1 alone.

## RP-CD rules touched
- RP-CD-002 (pull-sync must not clobber dirty rows) → `preserveDirty` + `mergePulledRowsByServerId`.
- RP-CD-004 (handlers return `OperationOutcome`, don't throw).
- RP-CD-005 (don't drain 409 body before `extractUpdatedAt`).
- RP-BUG-037 (reconcile by serverId, adopt local identity) → save paths.
- API Contract Discipline (CLAUDE.md) → golden-fixture parse tests, wrapper-type matching, nullable-except-guaranteed.

## Testing (plan step 9)
Flag ON/OFF/UNKNOWN mounting; multi-company isolation; golden-fixture deserialize; stable idempotency keys across retries; offline register/check-in/move/checkout; cross-project same-company move; cross-company rejection (403); optimistic-lock 409 refresh; migration test; process-death/offline-restart. (UI-mount tests land with Phase 2.)
