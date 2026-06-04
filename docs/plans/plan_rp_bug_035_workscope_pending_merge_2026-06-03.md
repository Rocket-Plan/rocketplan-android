**Bug ID(s):** RP-BUG-035
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-035_workscope_pending_create_merge_gap.md) · [Plan](./plan_rp_bug_035_workscope_pending_merge_2026-06-03.md) · Review: pending

# Fix Plan: [RP-BUG-035] Merge pending local work scopes during fetch

**Bug ID(s):** RP-BUG-035
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-03
**State:** draft

---

## Summary

`WorkScopeSyncService.syncRoomWorkScopes()` fetches work scopes from the API and saves them directly via `localDataService.saveWorkScopes()` (an upsert of only the fetched rows). Locally-created work scopes (`workScopeId < 0`, `serverId == null`, `syncStatus = PENDING`, `isDirty = true`) are never fetched or merged. They are not deleted by the upsert, but any UI that re-derives its list from the fetched set, or a later reconciliation that treats absence as deletion, makes them appear to vanish on refresh.

Rooms solve this with a resolve-existing-pending merge (`RoomSyncService.resolveExistingRoomForSync` + `toEntity(existing = …)`). Work scopes have no equivalent, and `LocalDataService` has no `getPendingWorkScopes*` accessor. The fix adds the missing pending-query plumbing and merges pending creates into the saved set, preserving them across refresh.

## Affected Code

| File | Change |
|------|--------|
| `data/local/dao/OfflineDao.kt` | Add `getPendingWorkScopesForRoom(roomId)` query. |
| `data/local/LocalDataService.kt` | Expose `getPendingWorkScopesForRoom(roomId)`. |
| `data/repository/sync/WorkScopeSyncService.kt` (≈80–96) | Fetch pending creates, merge with API entities (server-id keyed), save the union. |

## Implementation Notes

### Step 1: DAO query

```kotlin
@Query("""
    SELECT * FROM offline_work_scopes
    WHERE roomId = :roomId AND isDirty = 1 AND syncStatus = 'PENDING'
""")
suspend fun getPendingWorkScopesForRoom(roomId: Long): List<OfflineWorkScopeEntity>
```

(Confirm the stored `syncStatus` value/spelling against `OfflineTypeConverters` — match how PENDING is persisted, e.g. enum name.)

### Step 2: LocalDataService passthrough

```kotlin
suspend fun getPendingWorkScopesForRoom(roomId: Long): List<OfflineWorkScopeEntity> =
    offlineDao.getPendingWorkScopesForRoom(roomId)
```

### Step 3: Merge in syncRoomWorkScopes

```kotlin
val entities = response.data.mapNotNull { it.toEntity(defaultProjectId = projectId, defaultRoomId = roomId) }
val pending = localDataService.getPendingWorkScopesForRoom(roomId)
// keep pending creates the server doesn't yet know about (no matching serverId in the fetch)
val fetchedServerIds = entities.mapNotNull { it.serverId }.toSet()
val merged = entities + pending.filter { p ->
    p.serverId == null || p.serverId !in fetchedServerIds
}
if (merged.isNotEmpty()) localDataService.saveWorkScopes(merged)
```

Keying on `serverId` avoids a pending create (which has `serverId == null`) being dropped, and avoids duplicating a pending row that has since been assigned a server id and is also in the fetch.

### Step 4: Note the sibling gap (do not fix here)

`ProjectMetadataSyncService.syncRoomDamages` and `syncRoomMoistureLogs` have the same fetch-without-merge shape but are not yet filed. Add a `// TODO RP-BUG-035 sibling` comment and surface them for a separate ticket rather than expanding scope.

## Observability

- Console log `workscope_pending_creates_merged` (`project_id`, `room_id`, `pending_count`, `fetched_count`) so the merge is visible in logs.

## Test Plan

- [ ] Unit: with a pending local work scope and an API response excluding it, the merged save contains both the fetched scopes and the pending one.
- [ ] Unit: a pending scope that now matches a fetched `serverId` is not duplicated.
- [ ] Manual QA:
  1. Create a work scope offline in a room.
  2. Trigger a room refresh / re-fetch.
  3. Expected: the locally-created work scope is still present after refresh and still pending.

## Rollback Plan

Revert the three edits. The DAO/LocalDataService additions are new methods (no removals), so reverting is clean. No schema change.

## Dependencies

- Requires: none.
- Blocking: none. Sibling merge gaps (`syncRoomDamages`, `syncRoomMoistureLogs`) tracked separately.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-035] Work scopes created offline are no longer lost when a room refreshes; pending local creates are merged with the server response instead of being overwritten.
```
