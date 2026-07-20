---
bug_id: RP-BUG-279
aliases: []
title: "Offline equipment write-sync targets non-existent backend routes"
type: functional
classification: new_code_bug
source: internal
found_in: "1.30 (35)"
fixed_in: null
released_in: null
state: investigating
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_279_equipment_writesync_pivot_realignment_2026-07-09.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# Investigation: [RP-BUG-279] Offline equipment write-sync targets non-existent backend routes

## Symptom

Equipment created, edited, or deleted offline on Android never appears on iOS or the web app. The sync operations complete without error (no user-facing failure), but the changes silently drop.

## Discovery

- **Reported by:** internal review / backend contract audit
- **Evidence:** `OfflineSyncApi.kt:490,496` target routes that do not exist on the backend; runtime traces show 404s with silent fallback-to-no-op

## Affected Code

| File | Line | Issue |
|------|------|-------|
| `data/api/OfflineSyncApi.kt` | 490 | `updateEquipment` → `PUT /api/equipment/{equipmentId}` — **no such route** |
| `data/api/OfflineSyncApi.kt` | 496 | `deleteEquipment` → `DELETE /api/equipment/{equipmentId}` — **no such route** |
| `data/api/OfflineSyncApi.kt` | 484 | `createProjectEquipment` → `POST /api/projects/{projectId}/equipment` — route exists but `StoreProjectEquipmentRequest` drops all placement fields |
| `data/api/OfflineSyncApi.kt` | 479 | `getRoomEquipment` → `GET /api/rooms/{roomId}/equipment` — route exists but return type is wrong (`List<EquipmentDto>` vs wrapped `{data:[...]}`) and endpoint is unused |
| `data/repository/sync/handlers/EquipmentPushHandler.kt` | 214 | `updateEquipment` call → 404 → `isMissingOnServer()` → recreate via `createProjectEquipment` → 422 duplicate-name → `OperationOutcome.DROP` |
| `data/repository/sync/handlers/EquipmentPushHandler.kt` | 97 | `deleteEquipment` call → 404 → `resolveDeleteWithStaleRetry` → silent local-only no-op |
| `data/model/offline/OfflineEntities.kt` | 538 | `OfflineEquipmentEntity` conflates catalog identity with placement identity; single `serverId` used for both update/delete (pivot id) and catalog operations |
| `data/model/offline/OfflineDtos.kt` | 201 | `EquipmentRequest` bundles catalog + placement fields in a shape the backend never accepted |

## Root Cause

Android models equipment as a single flat row with a mutable `roomId` and pushes it to routes that don't exist. The backend models equipment as two separate things:

1. **Catalog type** (`equipment` row: `id`, `name`, `is_standard`, `catalog_uuid`, `project_id`) — a per-project type listing
2. **Placement pivot** (`equipment_room` row: `id`, `equipment_id`, `room_id`, `quantity`, `duration`, `date_in`, `date_out`, `number`, `uuid`) — an instance of a catalog type placed in a room

Android conflates these into `OfflineEquipmentEntity` and tries to `PUT/DELETE /api/equipment/{id}`, which doesn't exist. The server's actual placement endpoints are:

| Intent | Correct call |
|--------|-------------|
| Place catalog type into a room | `POST /api/rooms/{roomId}/equipment` body `{ equipment_ids: [int], uuid?, idempotency_key }` |
| Update a placement | `PUT /api/equipment-rooms/{pivotId}` body `{ quantity, duration, date_in, date_out, updated_at }` |
| Delete a placement | `DELETE /api/equipment-rooms/{pivotId}` |
| Read placements for a room | `GET /api/rooms/{roomId}/equipment` → `{ "data": [pivot items] }` |

**Hard backend prerequisite:** the attach endpoint must accept pivot `uuid`/`date_in`/`quantity` (for offline UUID reconciliation), and `GET /rooms/{id}/equipment` must return pivot `uuid` — both require `mongoose:MONGOOSE-BUG-036` on the backend. This makes RP-BUG-279 a coordinated Android + backend ticket.

## Fix Approach

See [plan document](../plans/plan_rp_bug_279_equipment_writesync_pivot_realignment_2026-07-09.md). Summary:

1. Split `OfflineEquipmentEntity` to carry separate `serverId` (pivot id) and `catalogServerId` (equipment id); add `catalogUuid`
2. Create flow = ensure catalog type exists (by id for standard types, mint via `POST /projects/{id}/equipment` for custom names), then attach via `POST /rooms/{roomId}/equipment`
3. Update flow = `PUT /api/equipment-rooms/{pivotId}` with pivot fields
4. Delete flow = `DELETE /api/equipment-rooms/{pivotId}`
5. Wire `GET /api/rooms/{roomId}/equipment` (fix return type to `SingleDataResponse<List<EquipmentDto>>`) into pull path for pivot reconciliation
6. Add Room migration for new nullable columns

## Observability

### Current Signals
- Local console logs: `EquipmentPushHandler` emits local `Log.debug` traces per operation
- Remote logs: none for legacy equipment failures
- Sentry: 404s from `updateEquipment`/`deleteEquipment` not captured (no remote log emission)
- Existing metrics/watchdogs: none

### Gaps
- Create 422 (duplicate-name) failures are silent — `OperationOutcome.DROP` logs nothing remotely
- Delete 404 → success (no-op) has no remote signal that a delete was attempted but silently succeeded due to missing server state
- No differentiation between "catalog not found" vs "server routing failure" on create

### Proposed Instrumentation
- Remote WARN `equipment_catalog_unresolved`: when a create cannot resolve a catalog type
- Remote WARN `equipment_pivot_id_missing`: when an update/delete has no pivot `serverId` after pull reconcile
- Keep existing 409 conflict WARN in `handle409Conflict`
- Reserve remote logs for terminal/actionable states; local `Log.debug` for happy-path trace

### Success Criteria
- Equipment added offline on Android appears in iOS/webapp after sync
- Equipment edited offline on Android reflects correctly in iOS/webapp after sync
- Equipment deleted offline on Android disappears from iOS/webapp after sync
- No silent drops: every terminal outcome (drop/retry/error) emits a remote log category

---

## Related

- Plan: `docs/plans/plan_rp_bug_279_equipment_writesync_pivot_realignment_2026-07-09.md`
- Prerequisite: `mongoose:MONGOOSE-BUG-036` (backend — must ship before Android fix can round-trip)
- Blocks: `RP-FR-018` (Equipment Move & Tracking) — hard dependency on pivot model
- Related: `RP-FR-019` (serialized equipment — separate subsystem; does NOT fix this)
- Related bugs: `RP-FR-002`, `RP-BUG-037`, `RP-BUG-038`, `RP-BUG-040` (existing equipment fixes in `EquipmentPushHandler`)
