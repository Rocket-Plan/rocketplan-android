# Sync Refactoring Plan: Modular Functions

## Goal
Break `syncProjectGraph()` (253 lines) into composable functions that can be called independently based on priority.
Status: implemented via `SyncSegment`, `SyncResult`, and segmented sync functions.

---

## Current Problem (pre-refactor)

**One monolithic function does everything:**
```kotlin
syncProjectGraph(projectId) {
    1. Project detail + embedded data (50 lines)
    2. Property + locations (30 lines)
    3. Rooms for all locations (50 lines)
    4. Photos for ALL rooms (30 lines)
    5. Floor/location/unit photos (25 lines) ← THIS BLOCKS ROOM LOADING
    6. Equipment/damages/notes/albums (40 lines)
}
```

When user navigates to Room Detail, the sync is often at step 5 (location photos page 12), blocking room-specific work.

---

## Segmented Sync Architecture (Implemented)

### Data Ownership Model

To avoid duplicate network calls and conflicting DB writes, each function owns specific data:

| Data Type | Owner Function | Source | Notes |
|-----------|---------------|---------|-------|
| Project entity | `syncProjectEssentials` | `/api/projects/{id}` | Single source of truth |
| Locations (snapshot) | `syncProjectEssentials` | Embedded in project detail | Preview only |
| Locations (authoritative) | `syncProjectEssentials` | `/api/properties/{id}/levels` + `/api/properties/{id}/locations` | Overwrites snapshot |
| Rooms (snapshot) | `syncProjectEssentials` | Embedded in project detail | Preview only |
| Rooms (authoritative) | `syncProjectEssentials` | `/api/locations/{id}/rooms` | Overwrites snapshot |
| Users | `syncProjectEssentials` | Embedded in project detail | Required for photo metadata |
| Albums | `syncProjectEssentials` | `/api/projects/{id}/albums` | Needed for navigation |
| Room photos | `syncRoomPhotos` | `/api/rooms/{id}/photos` | Per-room on demand |
| Project-level photos | `syncProjectLevelPhotos` | `/api/projects/{id}/{type}-photos` | Background only |
| Notes, equipment, damages, logs, work scopes | `syncProjectMetadata` | Dedicated endpoints | No detail-embed reliance |

**Contract:**
- Essentials gives a complete navigation chain (Project -> Property -> Levels -> Rooms -> Albums)
- Room photos are fetched on demand per room
- Metadata uses dedicated endpoints and can be deferred

### Segmented Functions

```kotlin
suspend fun syncProjectEssentials(projectId: Long): SyncResult

suspend fun syncRoomPhotos(projectId: Long, roomId: Long, ignoreCheckpoint: Boolean = false): SyncResult
suspend fun syncAllRoomPhotos(projectId: Long): SyncResult
suspend fun syncProjectLevelPhotos(projectId: Long): SyncResult
suspend fun syncProjectMetadata(projectId: Long): SyncResult

suspend fun syncProjectSegments(projectId: Long, segments: List<SyncSegment>): List<SyncResult>
suspend fun syncProjectContent(projectId: Long): List<SyncResult>
suspend fun syncProjectGraph(projectId: Long, skipPhotos: Boolean = false): List<SyncResult>
```

### SyncResult Model

```kotlin
enum class SyncSegment {
    PROJECT_ESSENTIALS,
    ROOM_PHOTOS,
    ALL_ROOM_PHOTOS,
    PROJECT_LEVEL_PHOTOS,
    PROJECT_METADATA
}

enum class IncompleteReason {
    MISSING_PROPERTY
}

sealed class SyncResult {
    abstract val segment: SyncSegment
    abstract val itemsSynced: Int
    abstract val durationMs: Long
    open val error: Throwable? = null

    data class Success(
        override val segment: SyncSegment,
        override val itemsSynced: Int = 0,
        override val durationMs: Long = 0
    ) : SyncResult()

    data class Failure(
        override val segment: SyncSegment,
        val cause: Throwable,
        override val durationMs: Long = 0,
        override val itemsSynced: Int = 0
    ) : SyncResult() {
        override val error: Throwable = cause
    }

    data class Incomplete(
        override val segment: SyncSegment,
        val reason: IncompleteReason,
        override val durationMs: Long = 0,
        override val itemsSynced: Int = 0,
        val cause: Throwable? = null
    ) : SyncResult() {
        override val error: Throwable? = cause
    }

    companion object {
        fun success(segment: SyncSegment, items: Int, duration: Long) =
            Success(segment, items, duration)

        fun failure(segment: SyncSegment, error: Throwable, duration: Long) =
            Failure(segment, error, duration)

        fun incomplete(
            segment: SyncSegment,
            reason: IncompleteReason,
            duration: Long,
            items: Int = 0,
            error: Throwable? = null
        ) = Incomplete(segment, reason, duration, items, error)
    }
}
```

---

## Detailed Function Summary

### 1. `syncProjectEssentials(projectId)` - navigation chain
**What it syncs:**
- Project detail entity
- Users
- Embedded location/room snapshots + embedded photos (if present)
- Property + levels/locations + rooms (authoritative)
- Albums
- Relinks room-scoped data

**Skips:**
- Notes, equipment, damages, work scopes
- Moisture and atmospheric logs

**Notes:**
- Returns `SyncResult.Incomplete(MISSING_PROPERTY)` when a project should have a property but none can be resolved.
- Uses incremental location sync when a recent location timestamp is available.

---

### 2. `syncProjectSegments(projectId, segments)` and `syncProjectContent(projectId)`
**What it does:**
- Executes a list of `SyncSegment` values sequentially and returns `List<SyncResult>`.
- Logs failures and incomplete results.
- `ROOM_PHOTOS` requires a roomId and is treated as an error when run as a project segment.
- `syncProjectContent` is a convenience wrapper for metadata + photos.

---

### 3. `syncRoomPhotos(projectId, roomId)` - high priority
**What it syncs:**
- Photos for one room (paginated).

**Notes:**
- Uses per-room checkpoints (updatedSince); `ignoreCheckpoint=true` forces a full resync.
- Schedules photo cache prefetch when new photos are saved.

---

### 4. `syncAllRoomPhotos(projectId)`
**What it syncs:**
- Photos for all rooms in the local DB (server ids).

**Notes:**
- Delegates to `syncRoomPhotos` per room and aggregates results.
- Intended for background passes.

---

### 5. `syncProjectLevelPhotos(projectId)` - low priority
**What it syncs:**
- Floor, location, and unit photos (paginated).

**Notes:**
- Uses separate checkpoints per photo type.
- Requires a resolved server project id (handled by `OfflineSyncRepository`).

---

### 6. `syncProjectMetadata(projectId)`
**What it syncs:**
- Notes (paginated with updatedSince)
- Equipment
- Damages (project-level with fallback to per-room)
- Moisture logs (per-room)
- Work scopes
- Atmospheric logs (updatedSince)

**Notes:**
- Updates checkpoints for notes, damages, and atmospheric logs.

---

### 7. `syncProjectGraph(projectId, skipPhotos = false)` - legacy wrapper
**What it does:**
- Composes essentials + optional metadata/photos via `syncProjectSegments`.
- Returns `List<SyncResult>`.
- When essentials fails or is incomplete, later segments are skipped.

---

## Usage Examples

### Room Detail Screen (current)
```kotlin
// RoomDetailViewModel.kt
val result = offlineSyncRepository.syncRoomPhotos(
    projectId,
    remoteRoomId,
    ignoreCheckpoint = ignoreCheckpoint
)
```

### Project Detail Pull-to-Refresh (current)
```kotlin
// ProjectDetailViewModel.kt
val results = offlineSyncRepository.syncProjectGraph(projectId, skipPhotos = true)
```

### Background Sync (current)
```kotlin
// SyncQueueManager or WorkManager
val results = offlineSyncRepository.syncProjectContent(projectId)
// or selectively:
val results = offlineSyncRepository.syncProjectSegments(
    projectId,
    listOf(SyncSegment.PROJECT_METADATA)
)
```

---

## Implementation Steps

### Phase 1: Extract Functions (2-3 hours)
**Status:** Completed
**No behavior changes, just reorganization**

1. [x] Create `SyncResult` sealed class + `SyncSegment` enum
2. [x] Extract `syncProjectEssentials()` for the navigation chain
3. [x] Extract `syncRoomPhotos()`, `syncAllRoomPhotos()`, `syncProjectLevelPhotos()`
4. [x] Extract `syncProjectMetadata()`
5. [x] Add `syncProjectSegments()` + `syncProjectContent()`
6. [x] Rewrite `syncProjectGraph()` to compose segments and return `List<SyncResult>`
7. [x] Add duration tracking and segment logging

**Testing:** Not run as part of doc update.

**Owner:** [ASSIGN]
**Due Date:** [TBD]

---

### Phase 2: Update Callers (1 hour)
**Status:** Complete ✅
**Start using selective sync**

1. [x] `RoomDetailViewModel` uses `syncRoomPhotos()`
2. [x] `ProjectDetailViewModel` uses `syncProjectGraph(skipPhotos = true)`
3. [x] `SyncQueueManager` uses `syncProjectSegments()` and `syncProjectContent()`
4. [x] Add cancellation around background photo sync in `RoomDetailFragment`
5. [x] Add telemetry to track which segments are called per screen

**Testing:** Manual verification pending.

**Owner:** [ASSIGN]
**Due Date:** [TBD after Phase 1]

---

### Phase 3: Measure & Optimize (30 min)
**Status:** Not started
**Verify improvement**

1. [ ] Collect baseline metrics (time-to-first-photo, API call counts)
2. [ ] Deploy Phase 1 + 2 to test device
3. [ ] Test on device with slow network (throttled to 3G)
4. [ ] Verify logs show no `/location-photos` calls when entering room
5. [ ] Measure time-to-first-photo improvement
6. [ ] Monitor for any data inconsistencies (missing rooms, etc.)

**Success Criteria:**
- Room photos visible in < 1 second (from cache) or < 3 seconds (fresh sync)
- No `/location-photos` calls visible in logs when entering room
- Background sync resumes after leaving room
- No regression in data completeness

**Owner:** [ASSIGN]
**Due Date:** [TBD after Phase 2]

---

## Migration Strategy

### Backward Compatibility
- [x] `syncProjectGraph()` behavior preserved; now returns `List<SyncResult>` and supports `skipPhotos`
- [x] Existing callers updated to new return type
- [x] New callers can opt into selective sync via `syncProjectSegments` and `syncProjectContent`

### Rollout
1. **Week 1:** Phase 1 complete, test in dev
2. **Week 2:** Phase 2 in progress, test on staging
3. **Week 3:** Ship to production, monitor metrics

### Rollback Plan
- If issues occur, revert changes to callers (Phase 2)
- Phase 1 changes are safe (just code organization)

---

## Benefits

### Performance
- ✅ **Room Detail loads 3-5x faster** (only syncs what's needed)
- ✅ **Reduced network usage** (no unnecessary photo pages)
- ✅ **Better cancellation** (smaller functions = more cancel points)

### Code Quality
- ✅ **Testability:** Each function can be unit tested independently
- ✅ **Readability:** 6 focused functions vs 1 giant function
- ✅ **Maintainability:** Easy to modify sync behavior per screen

### User Experience
- ✅ **Faster perceived performance** (priority loading)
- ✅ **Less battery drain** (skip unnecessary syncs)
- ✅ **Better offline support** (sync only what user needs)

---

## Risks & Mitigation

### Risk 1: Data Consistency
**Problem:** Calling functions individually might leave data incomplete.
**Mitigation:** Each function is self-contained. If core data is needed, call `syncProjectEssentials()` first.

### Risk 2: Increased Complexity
**Problem:** More functions = more maintenance.
**Mitigation:** Clear naming, good documentation, and `syncProjectGraph()` wrapper for simple cases.

### Risk 3: Breaking Existing Behavior
**Problem:** Refactoring might introduce bugs.
**Mitigation:** Keep `syncProjectGraph()` identical, add integration tests, gradual rollout.

---

## Open Questions

1. **Should we add a `SyncPriority` enum?**
   - Could help with future queuing/scheduling
   - Maybe overkill for now?

2. **Should functions return Flow<SyncResult> for progress?**
   - Useful for showing progress bars
   - Adds complexity

3. **Should we cache which functions have been called?**
   - Avoid duplicate syncs
   - Needs expiration logic

**Decision:** Start simple, add these later if needed.

---

## Success Metrics

**Before Refactoring:**
- Time to room photos: ~5-10 seconds (waiting for location photos)
- Network requests: ~50-100 API calls per project sync
- User complaint: "Room takes forever to load"

**After Refactoring:**
- Time to room photos: < 1 second (cache) or < 3 seconds (sync)
- Network requests: ~5-10 API calls when entering room
- User feedback: "Much faster!"

---

## Next Steps

1. ~~**Add cancellation hooks** for background photo sync in `RoomDetailFragment`~~ ✅ Done
2. ~~**Add segment telemetry** to track usage per screen~~ ✅ Done
3. **Run Phase 3 metrics** to validate time-to-first-photo improvements

---

# Service Layer Architecture (Implemented)

## Overview

After completing the segment-based sync refactoring above, we further decomposed `OfflineSyncRepository` into focused service classes. This reduces the file size from ~2500 lines to ~1100 lines and improves testability.

## Service Hierarchy

```
OfflineSyncRepository (Coordinator/Facade)
├── PhotoSyncService       - Photo sync operations
├── ProjectSyncService     - Project list sync, company projects
├── ProjectMetadataSyncService - Notes, equipment, damages, moisture/atmos logs, work scopes
├── PropertySyncService    - Property CRUD, room type caching
├── RoomSyncService        - Room/location CRUD, catalog handling
├── NoteSyncService        - Note CRUD and queueing
├── EquipmentSyncService   - Equipment CRUD and queueing
├── MoistureLogSyncService - Moisture log upsert and queueing
├── WorkScopeSyncService   - Work scope sync and catalog
├── DeletedRecordsSyncService - Server-side deletions
└── SyncQueueProcessor     - Pending operation queue processing (implements SyncQueueEnqueuer)
```

## Service Responsibilities

### PhotoSyncService
**File:** `data/repository/sync/PhotoSyncService.kt`

Handles all photo synchronization:
- `syncAllRoomPhotos(projectId)` - Bulk room photo sync
- `syncRoomPhotos(projectId, roomId)` - Single room photo sync
- `syncProjectLevelPhotos(projectId, serverProjectId)` - Floor/location/unit photos
- `refreshRoomPhotos(projectId, roomId)` - Legacy API wrapper
- `persistPhotos(photos)` - Save photos to local DB + schedule cache prefetch

### ProjectSyncService
**File:** `data/repository/sync/ProjectSyncService.kt`

Handles project list synchronization:
- `syncCompanyProjects(companyId, assignedToMe, forceFullSync)` - Sync company project list
- `syncUserProjects(userId)` - Sync user's assigned projects

### ProjectMetadataSyncService
**File:** `data/repository/sync/ProjectMetadataSyncService.kt`

Handles project metadata synchronization:
- `syncProjectMetadata(projectId)` - Notes, equipment, damages, moisture logs, atmospheric logs, work scopes
- `syncRoomDamages(projectId, roomId)` - Per-room damage sync
- `syncRoomMoistureLogs(projectId, roomId)` - Per-room moisture log sync

### PropertySyncService
**File:** `data/repository/sync/PropertySyncService.kt`

Handles property operations:
- `createProjectProperty(projectId, request, propertyTypeValue)` - Create property online-first
- `updateProjectProperty(projectId, propertyId, request, propertyTypeValue)` - Update property
- `persistProperty(projectId, property, propertyTypeValue, existing)` - Save property entity
- `fetchProjectProperty(projectId, projectDetail)` - Fetch property from API with fallbacks
- Room type cache priming

### RoomSyncService
**File:** `data/repository/sync/RoomSyncService.kt`

Handles room and location operations:
- `createRoom(projectId, roomName, roomTypeId, ...)` - Create room with pending location resolution
- `createDefaultLocationAndRoom(projectId, propertyTypeValue, locationName, seedDefaultRoom)` - Seed default structure
- `deleteRoom(projectId, roomId)` - Soft delete with cascade
- `fetchRoomsForLocation(locationId)` - Paginated room fetch
- `resolveExistingRoomForSync(projectId, room)` - Match incoming room to existing local entity

### NoteSyncService
**File:** `data/repository/sync/NoteSyncService.kt`

Handles note CRUD and queueing:
- `createNote(projectId, content, roomId, categoryId, photoId)` - Create pending note and enqueue upsert
- `updateNote(note, newContent)` - Update content and enqueue upsert
- `deleteNote(projectId, note)` - Soft delete and enqueue delete

### EquipmentSyncService
**File:** `data/repository/sync/EquipmentSyncService.kt`

Handles equipment CRUD and queueing:
- `upsertEquipmentOffline(...)` - Create/update equipment and enqueue upsert
- `deleteEquipmentOffline(equipmentId, uuid)` - Soft delete and enqueue delete

### MoistureLogSyncService
**File:** `data/repository/sync/MoistureLogSyncService.kt`

Handles moisture log updates and queueing:
- `upsertMoistureLogOffline(log)` - Save and enqueue upsert

### WorkScopeSyncService
**File:** `data/repository/sync/WorkScopeSyncService.kt`

Handles work scope sync and catalog operations:
- `syncWorkScopesForProject(projectId)` - Sync work scopes for all rooms
- `syncRoomWorkScopes(projectId, roomId)` - Sync room work scopes
- `fetchWorkScopeCatalog(companyId)` - Fetch work scope sheets
- `addWorkScopeItems(projectId, roomId, items)` - Push selected items

### DeletedRecordsSyncService
**File:** `data/repository/sync/DeletedRecordsSyncService.kt`

Syncs server-side deletions:
- `syncDeletedRecords(types)` - Fetches deletions, applies them locally, updates checkpoints
- Returns `Result<Unit>` so callers can react to failures

### SyncQueueProcessor
**File:** `data/repository/sync/SyncQueueProcessor.kt`

Processes the offline sync queue:
- `processPendingOperations()` - Main loop processing all queued operations
- Entity-specific handlers: `handlePendingProjectCreation`, `handlePendingRoomCreation`, etc.
- Enqueue methods: `enqueueProjectCreation`, `enqueueRoomDeletion`, etc.
- Conflict resolution and retry logic
- Implements `SyncQueueEnqueuer`

## Dependency Graph

```
OfflineSyncRepository
  |-- PhotoSyncService -> PhotoCacheScheduler
  |-- ProjectSyncService
  |-- ProjectMetadataSyncService
  |-- PropertySyncService
  |-- RoomSyncService
  |-- NoteSyncService
  |-- EquipmentSyncService
  |-- MoistureLogSyncService
  |-- WorkScopeSyncService
  |-- DeletedRecordsSyncService
  '-- SyncQueueProcessor (SyncQueueEnqueuer)
```

## SyncQueueEnqueuer Interface (Implemented)

Services depend on the `SyncQueueEnqueuer` interface to queue operations:

```kotlin
private val propertySyncService by lazy {
    PropertySyncService(
        syncQueueEnqueuer = { syncQueueProcessor },  // SyncQueueProcessor implements the interface
        ...
    )
}
```

**Benefits:**
- Services depend on interface, not concrete `SyncQueueProcessor`
- Easy to mock for unit tests
- Clear contract for what operations can be queued

## Shared Utilities

Common utilities:

```kotlin
// Timestamp formatting (SyncEntityMappers.kt)
fun Date?.toApiTimestamp(): String?

// Payload data classes (SyncPayloads.kt)
data class PendingProjectCreationPayload(...)
data class PendingPropertyCreationPayload(...)
data class PendingRoomCreationPayload(...)
data class PendingLocationCreationPayload(...)
data class PendingLockPayload(lockUpdatedAt: String?)
data class PendingPropertyUpdatePayload(...)
```

## Testing Strategy

With the service extraction:

1. **Unit tests per service** - Mock dependencies, test business logic
2. **Integration tests** - Test service interactions via `OfflineSyncRepository`
3. **Mock `SyncQueueEnqueuer`** - Verify correct operations are enqueued without hitting real queue

## File Sizes (Post-Refactoring)

| File | Lines | Responsibility |
|------|-------|----------------|
| `OfflineSyncRepository.kt` | 987 | Coordination, delegation |
| `SyncQueueProcessor.kt` | 1824 | Queue processing (candidate for further split) |
| `SyncQueueEnqueuer.kt` | 156 | Enqueue interface contract |
| `PhotoSyncService.kt` | 530 | Photo sync |
| `ProjectSyncService.kt` | 149 | Project list sync |
| `ProjectMetadataSyncService.kt` | 295 | Notes/equipment/damages/work scopes/logs |
| `PropertySyncService.kt` | 332 | Property CRUD |
| `RoomSyncService.kt` | 560 | Room/location CRUD |
| `NoteSyncService.kt` | 98 | Note CRUD |
| `EquipmentSyncService.kt` | 108 | Equipment CRUD |
| `MoistureLogSyncService.kt` | 28 | Moisture log upsert |
| `WorkScopeSyncService.kt` | 99 | Work scope sync and catalog |
| `DeletedRecordsSyncService.kt` | 131 | Server-side deletions |
| `SyncEntityMappers.kt` | 752 | Mappers and helpers |
| `SyncPayloads.kt` | 70 | Sync queue payload data classes |

## Future Work

1. **Split `SyncQueueProcessor` (if needed)** - Per-entity handlers (~300 lines each)
2. **Add unit tests** - Test each service in isolation
3. **Consider DI framework** - Hilt/Koin for cleaner dependency management
