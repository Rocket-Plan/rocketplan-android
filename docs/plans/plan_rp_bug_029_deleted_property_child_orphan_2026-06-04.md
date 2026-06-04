**Bug ID(s):** RP-BUG-029
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-029_deleted_property_omitted_child_location_orphan.md) · [Plan](./plan_rp_bug_029_deleted_property_child_orphan_2026-06-04.md) · Review: pending

# Fix Plan: [RP-BUG-029] Reconcile child locations/rooms when a deleted property omits its cascade children

**Bug ID(s):** RP-BUG-029
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-04
**State:** draft

---

## Summary

When `/api/sync/deleted` reports a deleted property but omits its cascade-deleted child location/room ids (backend MONGOOSE-BUG-013), Android marks the property deleted but leaves the children live in Room. `DeletedRecordsSyncService.applyDeletedRecords()` / `applyProjectChildDeletions()` apply `markPropertiesDeleted` / `markRoomsDeleted` / `markLocationsDeleted` independently — there is no property→location→room cascade. Orphaned locations/rooms then cause intermittent 404s and stale UI.

This is **defense-in-depth** (no confirmed Android field incident; the backend fix clears the known case). A naive cascade is unsafe today because `OfflineLocationEntity` stores `projectId`/`parentLocationId` but **not** property identity — so "property deleted → delete all project locations" could delete a live replacement property's locations. The fix establishes the missing identity, then cascades only clean synced descendants.

## Affected Code

| File | Change |
|------|--------|
| `data/local/entity/OfflineEntities.kt` | Add `propertyServerId: Long?` to `OfflineLocationEntity` **plus an `Index(["propertyServerId"])`** (cascade lookups would otherwise table-scan). |
| `data/local/OfflineDatabase.kt` | Migration 29→30: add the column **and create the index**. |
| `data/repository/mapper/SyncEntityMappers.kt` | `LocationDto.toEntity()` gains a `propertyServerId: Long? = null` **param** — `LocationDto` has no `property_id`, so it's supplied by the call site, not the DTO. |
| `data/api/.../*SyncService` (property-locations pull) | At the `/api/properties/{propertyId}/locations` call site, pass `propertyServerId = propertyId` into `toEntity()`. |
| `data/local/dao/OfflineDao.kt` | Query: clean synced locations under a `propertyServerId` (returns local `locationId` PKs); cascade rooms by `offline_rooms.locationId IN (:localLocationIds)`. |
| `data/local/LocalDataService.kt` | New `cascadePropertyDeletion(...)` wrapped in one `database.withTransaction {}`. |
| `data/repository/sync/DeletedRecordsSyncService.kt` | Call the cascade in both apply paths. |
| `docs/architecture/ARCHITECTURE.md` | Bump the documented DB version (29 → 30) — it currently records the version. |

## Implementation Notes

> **Sequencing:** this depends on the same dirty-preservation discipline as [RP-FR-003]. Land FR-003 first or share its "clean synced only" predicate, so the cascade never deletes dirty/local rows.

### Step 1: Persist property identity on locations (Option 1 from the investigation)

```kotlin
@Entity(
    tableName = "offline_locations",
    indices = [ /* …existing… */ Index(value = ["propertyServerId"]) ]   // NEW index
)
data class OfflineLocationEntity(
    // …existing…
    val propertyServerId: Long? = null,   // NEW — supplied by the call site, not the DTO
)
```

Migration 29→30 (column **and** index):

```kotlin
private val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE offline_locations ADD COLUMN propertyServerId INTEGER")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_offline_locations_propertyServerId ON offline_locations(propertyServerId)")
    }
}
```

**Source of the value:** `LocationDto` carries `project_id`/`parent_location_id` but **no `property_id`**, so the mapper cannot derive it. Extend the existing mapper signature (it already takes `defaultProjectId`/`existing`) with a `propertyServerId` param, and have the `/api/properties/{propertyId}/locations` pull pass `propertyServerId = propertyId`:

```kotlin
internal fun LocationDto.toEntity(
    defaultProjectId: Long? = null,
    existing: OfflineLocationEntity? = null,
    propertyServerId: Long? = null,          // NEW
): OfflineLocationEntity { /* … propertyServerId = propertyServerId ?: existing?.propertyServerId … */ }
```

Existing rows and locations pulled from non-property endpoints backfill NULL and are simply not eligible for the surgical cascade until re-synced — acceptable for defense-in-depth.

### Step 2: Surgical cascade on property deletion (local location PKs for rooms)

`offline_rooms.locationId` stores the **local** location PK, not the location `serverId`. So the cascade resolves the deleted property's clean synced locations to their **local `locationId`s**, then cascades rooms by those:

```kotlin
suspend fun cascadePropertyDeletion(propertyServerIds: List<Long>) = database.withTransaction {
    dao.markPropertiesDeletedByServerIds(propertyServerIds)
    // local location PKs of clean synced locations under the deleted property
    val localLocationIds = dao.getCleanSyncedLocationLocalIdsForProperties(propertyServerIds)
    dao.markLocationsDeletedByLocalIds(localLocationIds)
    dao.markRoomsDeletedCleanByLocalLocationIds(localLocationIds)   // matches offline_rooms.locationId
}
```

DAO query returns local PKs and filters to clean synced rows:

```kotlin
@Query("SELECT locationId FROM offline_locations WHERE propertyServerId IN (:propertyServerIds) AND serverId IS NOT NULL AND isDirty = 0")
suspend fun getCleanSyncedLocationLocalIdsForProperties(propertyServerIds: List<Long>): List<Long>
```

Safety predicate everywhere: `serverId != null && isDirty == 0`. Dirty/local-only descendants are **preserved**, never silently deleted. The room cascade query must likewise only mark clean synced rooms.

### Step 3: Wire into both apply paths

`DeletedRecordsSyncService.applyDeletedRecords()` and `applyProjectChildDeletions()` call `cascadePropertyDeletion(response.properties)` in place of the bare `markPropertiesDeleted(response.properties)`. Explicit location/room deletions from the response still apply as today (additive).

### Step 4 (optional, follow-up): 404 hardening

The investigation's Option 3 — on a hard 404 for a cached clean synced location/room, mark it deleted and re-resolve; treat transient network errors separately. `RoomSyncService.fetchRoomsForLocation` already does a narrow version (`:404` handler). Consider broadening only if Step 1–3 prove insufficient; keep it a separate change to avoid conflating network-error handling with deletion logic.

## Observability

- WARN when the cascade deletes children the server omitted: `deleted_property_cascade_orphan_reconciled` (`property_server_id`, `location_count`, `room_count`). This is the signal that MONGOOSE-BUG-013-shaped omissions are actually occurring on Android.

## Test Plan

- [ ] Unit: payload `properties=[P], locations=[]` with a clean synced child location + room cached → after apply, the child location and room are marked deleted.
- [ ] Unit: a **dirty** or **local-only** (`serverId == null`) child under deleted property P → preserved, not deleted.
- [ ] Unit: a live replacement property in the same project keeps its locations/rooms (no over-deletion) — relies on `propertyServerId` scoping.
- [ ] Unit: explicit `locations=[L]` deletions still apply (no regression).
- [ ] Unit: room cascade matches `offline_rooms.locationId` against **local** location PKs (not serverIds) — a room whose `locationId` equals a deleted location's local PK is marked deleted; one keyed by serverId-by-coincidence is not.
- [ ] Migration test: v29 → v30 preserves rows; `propertyServerId` backfills NULL; index exists.

## Rollback Plan

Schema change (29→30): prefer rolling forward. Reverting before release is clean (drop migration, column, cascade). Reverting after install needs the destructive-migration fallback.

## Dependencies

- Requires: shares the clean-synced predicate with **RP-FR-003**; sequence after it.
- Backend: MONGOOSE-BUG-013 fix is the primary remedy; this is client defense-in-depth and can land independently.
- Blocking: none.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-029] Deleting a property now reconciles its child locations and rooms locally, so orphaned stale rows no longer cause 404s or ghost entries when the server omits cascade children.
```
