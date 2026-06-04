---
bug_id: RP-BUG-035
aliases: []
title: WorkScopeSyncService.syncRoomWorkScopes fetches from API without merging pending local creates — locally-staged work scopes vanish on refresh
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_035_workscope_pending_merge_2026-06-03.md
related_review: null
related_test: null
last_updated: 2026-06-03
---

# Investigation: WorkScopeSyncService does not merge pending local creates during fetch

## Symptom

When `syncRoomWorkScopes` is called, it fetches work scopes from the API and saves them directly via `localDataService.saveWorkScopes()`. Any locally-pending work scopes (with `isDirty=true` and `syncStatus=PENDING`) are **never fetched, merged, or preserved**. Locally-authored work scopes disappear on refresh.

## Discovery

- **Source:** Parity review vs iOS RP-BUG-027
- **Evidence:** Code analysis confirmed bug
- **Found during:** iOS parity investigation 2026-06-03

## Root Cause (Confirmed)

### The Merge Gap in syncRoomWorkScopes

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/sync/WorkScopeSyncService.kt`
**Lines:** 80-96

```kotlin
suspend fun syncRoomWorkScopes(projectId: Long, roomId: Long): Int = withContext(ioDispatcher) {
    val startTime = System.currentTimeMillis()
    val response = runCatching { api.getRoomWorkScope(roomId) }
        .onFailure { Log.e(TAG, "[syncRoomWorkScopes] Failed for roomId=$roomId (projectId=$projectId)", it) }
        .getOrNull() ?: return@withContext 0

    val entities = response.data.mapNotNull { it.toEntity(defaultProjectId = projectId, defaultRoomId = roomId) }
    if (entities.isNotEmpty()) {
        localDataService.saveWorkScopes(entities)   // LINE 88: Direct save, NO merge with pending
    }
    ...
}
```

**The bug:** Line 88 calls `saveWorkScopes(entities)` which uses `dao.upsertWorkScopes()`. This only updates/inserts the **API-fetched entities**. Any locally-pending work scopes (with `isDirty=true` and `syncStatus=PENDING`) are **never fetched, merged, or preserved**.

### How Rooms Handle the Merge (for comparison)

**File:** `OfflineSyncRepository.kt`
**Lines:** 736-762

```kotlin
val existing = roomSyncService.resolveExistingRoomForSync(projectId, room)
room.toEntity(
    existing = existing,    // <-- Existing pending room passed here
    projectId = projectId,
    locationId = room.locationId ?: locationId
)
```

**File:** `RoomSyncService.kt`
**Lines:** 44-75

```kotlin
val pendingCount = localDataService.countPendingRoomsForProjectTitle(projectId, title)
when {
    pendingCount == 1 -> localDataService.getPendingRoomForProject(projectId, title)
    pendingCount > 1 -> { ...skip... }
    else -> null
}
```

**This merge pattern is completely absent from WorkScopeSyncService.**

### LocalDataService Missing Methods

**There is NO method to get pending work scopes for a room or project.**

| Method | Line | For Entity |
|--------|------|------------|
| `getPendingRoomForProject` | 401 | Rooms |
| `getPendingNotes` | 592 | Notes |
| `getPendingEquipment` | 1030 | Equipment |
| `getPendingMoistureLogs` | 596 | MoistureLogs |
| `getPendingWorkScopes` | **MISSING** | WorkScopes |

### How Local Work Scopes Are Created (the "pending" side)

**File:** `app/src/main/java/com/example/rocketplan_android/ui/projects/RoomDetailViewModel.kt`
**Lines:** 550-575

```kotlin
val scope = OfflineWorkScopeEntity(
    workScopeId = -System.currentTimeMillis(),   // Negative ID = local-only
    uuid = UuidUtils.generateUuidV7(),
    projectId = projectId,
    roomId = lookupRoomId,
    name = name.trim(),
    // ...
    syncStatus = SyncStatus.PENDING,    // <-- Marked pending
    isDirty = true                     // <-- Marked dirty
)
localDataService.saveWorkScopes(listOf(scope))
```

These locally-created scopes have:
- Negative `workScopeId`
- `syncStatus = PENDING`
- `isDirty = true`
- `serverId = null`

### Entity Schema

**File:** `OfflineEntities.kt`
**Lines:** 695-720

```kotlin
data class OfflineWorkScopeEntity(
    val workScopeId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long,
    val roomId: Long? = null,
    val name: String,
    val description: String? = null,
    // ...
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val isDirty: Boolean = false,
    val isDeleted: Boolean = false
)
```

## Other Sync Services with Same Pattern

These services also fetch from API and save without merging pending creates:

| Service | Method | File:Line |
|---------|--------|-----------|
| **WorkScopeSyncService** | `syncRoomWorkScopes` | `WorkScopeSyncService.kt:80-96` |
| **ProjectMetadataSyncService** | `syncRoomDamages` | `ProjectMetadataSyncService.kt:218-246` |
| **ProjectMetadataSyncService** | `syncRoomMoistureLogs` | `ProjectMetadataSyncService.kt:248-344` |
| **NoteSyncService** | (create-only, no fetch) | `NoteSyncService.kt` |

The same merge gap likely exists for damages and moisture logs but hasn't been separately filed.

## Affected Code

| Location | Lines | Issue |
|----------|-------|-------|
| `WorkScopeSyncService.kt` | 80-96 | `syncRoomWorkScopes` doesn't merge pending creates |
| `WorkScopeSyncService.kt` | 88 | Direct save without merge |
| `LocalDataService.kt` | — | No `getPendingWorkScopesForRoom` method |
| `OfflineDao.kt` | — | No DAO query for pending work scopes |

## Proposed Fix Approach

1. **Add DAO query** for pending work scopes:
   ```kotlin
   @Query("SELECT * FROM offline_work_scopes WHERE roomId = :roomId AND isDirty = 1 AND syncStatus = 'PENDING'")
   suspend fun getPendingWorkScopesForRoom(roomId: Long): List<OfflineWorkScopeEntity>
   ```

2. **Add LocalDataService method**:
   ```kotlin
   suspend fun getPendingWorkScopesForRoom(roomId: Long): List<OfflineWorkScopeEntity>
   ```

3. **Modify syncRoomWorkScopes** to merge:
   ```kotlin
   val pendingWorkScopes = localDataService.getPendingWorkScopesForRoom(roomId)
   val mergedEntities = entities + pendingWorkScopes.filter { pending ->
       entities.none { it.serverId != null && it.serverId == pending.serverId }
   }
   localDataService.saveWorkScopes(mergedEntities)
   ```

4. **Check same pattern in** `syncRoomDamages` and `syncRoomMoistureLogs`

## Observability

### Current Signals
- Local console logs: `workscope_sync_started`, `workscope_sync_completed`
- Remote logs: None currently
- Sentry: None yet observed

### Gaps
- Pending local work scopes not merged with server response
- User-authored content disappears on refresh

### Proposed Instrumentation
- Local debug logs: `workscope_pending_creates_merged`, `workscope_refresh_local_items`
- Remote logs: Info category `workscope_sync_merge_count`
- Key fields: `project_id`, `room_id`, `pending_count`, `fetched_count`

---

## Related

- iOS counterpart: `RP-BUG-027` (fetchRoomsFromAPI skips workscope pending-create merge)
- Same pattern: `ProjectMetadataSyncService` (syncRoomDamages, syncRoomMoistureLogs) — not yet filed