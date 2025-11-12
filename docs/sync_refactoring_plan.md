# Sync Refactoring Plan: Modular Functions

## Goal
Break `syncProjectGraph()` (253 lines) into composable functions that can be called independently based on priority.

---

## Current Problem

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

## Proposed Architecture

### Data Ownership Model

To avoid duplicate network calls and conflicting DB writes, each function owns specific data:

| Data Type | Owner Function | Source | Notes |
|-----------|---------------|---------|-------|
| Project entity | `syncProjectCore` | `/api/projects/{id}` | Single source of truth |
| Locations (snapshot) | `syncProjectCore` | Embedded in project detail | Preview only |
| Locations (authoritative) | `syncProjectStructure` | `/api/properties/{id}/levels` | Overwrites snapshot |
| Rooms (snapshot) | `syncProjectCore` | Embedded in project detail | Preview only |
| Rooms (authoritative) | `syncProjectStructure` | `/api/locations/{id}/rooms` | Overwrites snapshot |
| Room photos | `syncRoomPhotos` | `/api/rooms/{id}/photos` | Per-room basis |
| Project-level photos | `syncProjectLevelPhotos` | `/api/projects/{id}/{type}-photos` | Background only |
| Notes, users, equipment | `syncProjectMetadata` | Dedicated endpoints | Never from detail embed |

**Contract:**
- Core gives "fast preview" from 1 API call
- Structure refreshes with authoritative property data
- Metadata hits dedicated endpoints (no duplicates)

### New Modular Functions

```kotlin
// 1. CORE: Project metadata + immediate data
suspend fun syncProjectCore(projectId: Long): SyncResult

// 2. STRUCTURE: Property, locations, rooms list
suspend fun syncProjectStructure(projectId: Long): SyncResult

// 3. ROOM PHOTOS: Single room's photos (high priority)
suspend fun syncRoomPhotos(projectId: Long, roomId: Long): SyncResult

// 4. ALL ROOM PHOTOS: Bulk sync (for background)
suspend fun syncAllRoomPhotos(projectId: Long, roomIds: List<Long>): SyncResult

// 5. PROJECT PHOTOS: Floor/location/unit photos (low priority)
suspend fun syncProjectLevelPhotos(projectId: Long): SyncResult

// 6. METADATA: Equipment, damages, notes, albums
suspend fun syncProjectMetadata(projectId: Long): SyncResult

// 7. LEGACY: Keep existing behavior
suspend fun syncProjectGraph(projectId: Long) {
    syncProjectCore(projectId)
    syncProjectStructure(projectId)
    val roomIds = localDataService.getRoomIdsForProject(projectId)
    syncAllRoomPhotos(projectId, roomIds)
    syncProjectLevelPhotos(projectId)
    syncProjectMetadata(projectId)
}
```

### SyncResult Data Class

```kotlin
enum class SyncSegment {
    PROJECT_CORE,
    PROJECT_STRUCTURE,
    ROOM_PHOTOS,
    ALL_ROOM_PHOTOS,
    PROJECT_LEVEL_PHOTOS,
    PROJECT_METADATA
}

data class SyncResult(
    val segment: SyncSegment,
    val success: Boolean,
    val itemsSynced: Int = 0,
    val error: Throwable? = null,
    val durationMs: Long = 0
) {
    companion object {
        fun success(segment: SyncSegment, items: Int, duration: Long) =
            SyncResult(segment, true, items, null, duration)

        fun failure(segment: SyncSegment, error: Throwable, duration: Long) =
            SyncResult(segment, false, 0, error, duration)
    }
}

---

## Detailed Function Breakdown

### 1. `syncProjectEssentials(projectId)` - NAVIGATION CHAIN
**What it syncs (COMPLETE NAVIGATION PATH):**
- ✅ Project detail entity
- ✅ Property (linked to project)
- ✅ Levels/Locations (linked to property)
- ✅ Rooms (linked to locations)
- ✅ Albums (project-level + room-level)
- ✅ Photos (embedded in detail, if any)
- ✅ Users (needed for photo metadata)

**What this enables:**
- Complete drill-down: Project → Property → Levels → Rooms → Albums → Photos
- All navigation works without additional API calls

**What it SKIPS** (not needed for navigation):
- ~~Notes~~ (separate feature)
- ~~Equipment, damages, work scopes~~ (not in navigation path)
- ~~Atmospheric/moisture logs~~ (separate feature)

**Why separate:** Everything needed to navigate from project to photos, nothing more.

**Design Decision:**
This is the "quick sync" for project overview + navigation. Photos themselves sync on-demand per room.

**Implementation:**
```kotlin
suspend fun syncProjectEssentials(projectId: Long): SyncResult = withContext(ioDispatcher) {
    val startTime = System.currentTimeMillis()
    Log.d("API", "🔄 [syncProjectEssentials] Starting navigation chain for project $projectId")

    val detail = runCatching { api.getProjectDetail(projectId) }
        .onFailure {
            Log.e("API", "❌ [syncProjectCore] Failed", it)
            val duration = System.currentTimeMillis() - startTime
            return@withContext SyncResult.failure(SyncSegment.PROJECT_CORE, it, duration)
        }
        .getOrNull() ?: run {
            val duration = System.currentTimeMillis() - startTime
            return@withContext SyncResult.failure(
                SyncSegment.PROJECT_CORE,
                Exception("Project detail returned null"),
                duration
            )
        }

    var itemCount = 0

    // Save project entity
    localDataService.saveProjects(listOf(detail.toEntity()))
    itemCount++
    ensureActive()

    // Save USERS (essential)
    detail.users?.let {
        localDataService.saveUsers(it.map { user -> user.toEntity() })
        itemCount += it.size
    }
    ensureActive()

    // Save embedded SNAPSHOTS (not authoritative, but fast)
    detail.locations?.let {
        localDataService.saveLocations(it.map { loc -> loc.toEntity(defaultProjectId = detail.id) })
        itemCount += it.size
    }
    ensureActive()

    detail.rooms?.let { rooms ->
        val resolvedRooms = rooms.map { room ->
            val existing = localDataService.getRoomByServerId(room.id)
                ?: room.uuid?.let { uuid -> localDataService.getRoomByUuid(uuid) }
            room.toEntity(existing, projectId = detail.id, locationId = room.locationId)
        }
        localDataService.saveRooms(resolvedRooms)
        itemCount += rooms.size
    }
    ensureActive()

    detail.photos?.let {
        if (persistPhotos(it)) itemCount += it.size
    }
    ensureActive()

    // === NAVIGATION CHAIN: Property → Levels → Rooms ===

    // 1. Property
    val property = fetchProjectProperty(projectId)
    if (property != null) {
        localDataService.saveProperty(property.toEntity())
        itemCount++
    }
    ensureActive()

    // 2. Levels (Locations from property)
    val propertyLocations = property?.id?.let { propertyId ->
        val levels = runCatching { api.getPropertyLevels(propertyId) }
            .getOrNull()?.data ?: emptyList()
        val nested = runCatching { api.getPropertyLocations(propertyId) }
            .getOrNull()?.data ?: emptyList()
        levels + nested
    } ?: emptyList()

    val locationIds = mutableSetOf<Long>()
    if (propertyLocations.isNotEmpty()) {
        localDataService.saveLocations(
            propertyLocations.map { it.toEntity(defaultProjectId = projectId) }
        )
        locationIds += propertyLocations.map { it.id }
        itemCount += propertyLocations.size
    }
    ensureActive()

    // 3. Rooms for each location
    locationIds.distinct().forEach { locationId ->
        val rooms = fetchRoomsForLocation(locationId)
        if (rooms.isNotEmpty()) {
            val resolvedRooms = rooms.map { room ->
                val existing = localDataService.getRoomByServerId(room.id)
                    ?: room.uuid?.let { uuid -> localDataService.getRoomByUuid(uuid) }
                room.toEntity(
                    existing = existing,
                    projectId = projectId,
                    locationId = room.locationId ?: locationId
                )
            }
            localDataService.saveRooms(resolvedRooms)
            itemCount += rooms.size
        }
        ensureActive()
    }

    // 4. Relink room-scoped data (ensures foreign keys are correct)
    runCatching { localDataService.relinkRoomScopedData() }
        .onFailure { Log.e("API", "❌ [syncProjectEssentials] Relink failed", it) }
    ensureActive()

    // 5. Albums (needed for photo organization)
    runCatching {
        fetchAllPages { page -> api.getProjectAlbums(projectId, page) }
    }.onSuccess { albums ->
        val albumEntities = albums.map { it.toEntity(defaultProjectId = projectId) }
        localDataService.saveAlbums(albumEntities)
        itemCount += albums.size
    }
    ensureActive()

    // NOTE: Notes, equipment, damages, logs SKIPPED (not in navigation path)

    val duration = System.currentTimeMillis() - startTime
    Log.d("API", "✅ [syncProjectEssentials] Synced $itemCount items in ${duration}ms")
    Log.d("API", "   Navigation chain complete: Property → ${locationIds.size} Levels → Rooms → Albums")
    SyncResult.success(SyncSegment.PROJECT_CORE, itemCount, duration)
}
```

---

### 2. `syncProjectStructure(projectId)`
**Lines:** 163-233 (from current code)
**What it syncs:**
- Property entity
- Property levels (locations)
- Property nested locations
- Rooms for each location
- Relinks room-scoped data

**Why separate:** Needed for project navigation, but not urgent for viewing a specific room.

**Implementation:**
```kotlin
suspend fun syncProjectStructure(projectId: Long): SyncResult = withContext(ioDispatcher) {
    Log.d("API", "🔄 [syncProjectStructure] Starting for project $projectId")

    var itemCount = 0

    // Property
    val property = fetchProjectProperty(projectId)
    if (property != null) {
        localDataService.saveProperty(property.toEntity())
        itemCount++
    }

    // Property locations
    val propertyLocations = property?.id?.let { propertyId ->
        val levels = runCatching { api.getPropertyLevels(propertyId) }
            .getOrNull()?.data ?: emptyList()
        val nested = runCatching { api.getPropertyLocations(propertyId) }
            .getOrNull()?.data ?: emptyList()
        levels + nested
    } ?: emptyList()

    val locationIds = mutableSetOf<Long>()
    if (propertyLocations.isNotEmpty()) {
        localDataService.saveLocations(
            propertyLocations.map { it.toEntity(defaultProjectId = projectId) }
        )
        locationIds += propertyLocations.map { it.id }
        itemCount += propertyLocations.size
    }

    // Rooms for each location
    locationIds.distinct().forEach { locationId ->
        val rooms = fetchRoomsForLocation(locationId)
        if (rooms.isNotEmpty()) {
            val resolvedRooms = rooms.map { room ->
                val existing = localDataService.getRoomByServerId(room.id)
                    ?: room.uuid?.let { uuid -> localDataService.getRoomByUuid(uuid) }
                room.toEntity(
                    existing = existing,
                    projectId = projectId,
                    locationId = room.locationId ?: locationId
                )
            }
            localDataService.saveRooms(resolvedRooms)
            itemCount += rooms.size
        }
    }

    // Relink room-scoped data
    runCatching { localDataService.relinkRoomScopedData() }
        .onFailure { Log.e("API", "❌ [syncProjectStructure] Relink failed", it) }

    Log.d("API", "✅ [syncProjectStructure] Synced $itemCount items")
    SyncResult(success = true, itemsSynced = itemCount)
}
```

---

### 3. `syncRoomPhotos(projectId, roomId)` ⚡ HIGH PRIORITY
**Lines:** 335-357 (existing `refreshRoomPhotos`)
**What it syncs:**
- Photos for ONE specific room (paginated)

**Why separate:** This is what Room Detail screen needs IMMEDIATELY.

**Implementation:**
```kotlin
// THIS ALREADY EXISTS! Just expose it prominently
suspend fun syncRoomPhotos(projectId: Long, roomId: Long): SyncResult = withContext(ioDispatcher) {
    Log.d("API", "🔄 [syncRoomPhotos] Starting for room $roomId")

    val photos = runCatching {
        fetchRoomPhotoPages(roomId = roomId, projectId = projectId)
    }.onFailure {
        Log.e("API", "❌ [syncRoomPhotos] Failed", it)
        return@withContext SyncResult(success = false, error = it)
    }.getOrNull() ?: return@withContext SyncResult(success = false)

    val saved = if (photos.isNotEmpty()) {
        persistPhotos(photos, defaultRoomId = roomId)
    } else {
        false
    }

    if (saved) {
        photoCacheScheduler.schedulePrefetch()
    }

    Log.d("API", "✅ [syncRoomPhotos] Synced ${photos.size} photos")
    SyncResult(success = true, itemsSynced = photos.size)
}
```

---

### 4. `syncAllRoomPhotos(projectId, roomIds)`
**Lines:** 235-266 (from current code)
**What it syncs:**
- Photos for ALL rooms in the list (bulk operation)

**Why separate:** Background operation, shouldn't block anything.

**Implementation:**
```kotlin
suspend fun syncAllRoomPhotos(projectId: Long, roomIds: List<Long>): SyncResult = withContext(ioDispatcher) {
    Log.d("API", "🔄 [syncAllRoomPhotos] Starting for ${roomIds.size} rooms")

    var totalPhotos = 0
    var didFetchAny = false

    roomIds.distinct().forEach { roomId ->
        // Check for cancellation between rooms
        ensureActive()

        val photos = runCatching {
            fetchRoomPhotoPages(roomId = roomId, projectId = projectId)
        }.onFailure { error ->
            if (error is retrofit2.HttpException && error.code() == 404) {
                Log.d("API", "INFO [syncAllRoomPhotos] Room $roomId has no photos")
            } else {
                Log.e("API", "❌ [syncAllRoomPhotos] Failed for room $roomId", error)
            }
            return@forEach
        }.getOrNull() ?: return@forEach

        if (photos.isNotEmpty()) {
            val saved = persistPhotos(photos, defaultRoomId = roomId)
            if (saved) {
                totalPhotos += photos.size
                didFetchAny = true
            }
        }
    }

    if (didFetchAny) {
        photoCacheScheduler.schedulePrefetch()
    }

    Log.d("API", "✅ [syncAllRoomPhotos] Synced $totalPhotos photos across ${roomIds.size} rooms")
    SyncResult(success = true, itemsSynced = totalPhotos)
}
```

---

### 5. `syncProjectLevelPhotos(projectId)` ⚠️ LOW PRIORITY
**Lines:** 273-295 (from current code)
**What it syncs:**
- Floor photos (paginated)
- Location photos (paginated) ← **THIS IS THE CULPRIT**
- Unit photos (paginated)

**Why separate:** These are NOT needed for room viewing. Can run in background.

**Implementation:**
```kotlin
suspend fun syncProjectLevelPhotos(projectId: Long): SyncResult = withContext(ioDispatcher) {
    Log.d("API", "🔄 [syncProjectLevelPhotos] Starting for project $projectId")

    var totalPhotos = 0
    var didFetchAny = false

    // Floor photos
    val floorPhotos = runCatching {
        fetchAllPages { page -> api.getProjectFloorPhotos(projectId, page) }
            .map { it.toPhotoDto(projectId) }
    }.getOrDefault(emptyList())
    if (persistPhotos(floorPhotos)) {
        totalPhotos += floorPhotos.size
        didFetchAny = true
    }

    // Check for cancellation
    ensureActive()

    // Location photos (THE SLOW ONE)
    val locationPhotos = runCatching {
        fetchAllPages { page -> api.getProjectLocationPhotos(projectId, page) }
            .map { it.toPhotoDto(projectId) }
    }.getOrDefault(emptyList())
    if (persistPhotos(locationPhotos)) {
        totalPhotos += locationPhotos.size
        didFetchAny = true
    }

    // Check for cancellation
    ensureActive()

    // Unit photos
    val unitPhotos = runCatching {
        fetchAllPages { page -> api.getProjectUnitPhotos(projectId, page) }
            .map { it.toPhotoDto(projectId) }
    }.getOrDefault(emptyList())
    if (persistPhotos(unitPhotos)) {
        totalPhotos += unitPhotos.size
        didFetchAny = true
    }

    if (didFetchAny) {
        photoCacheScheduler.schedulePrefetch()
    }

    Log.d("API", "✅ [syncProjectLevelPhotos] Synced $totalPhotos photos")
    SyncResult(success = true, itemsSynced = totalPhotos)
}
```

---

### 6. `syncProjectMetadata(projectId)`
**Lines:** 269-326 (from current code)
**What it syncs:**
- Project-level atmospheric logs
- Equipment
- Damage materials
- Notes
- Users
- Albums

**Why separate:** Nice-to-have data, not critical for room viewing.

**Implementation:**
```kotlin
suspend fun syncProjectMetadata(projectId: Long): SyncResult = withContext(ioDispatcher) {
    Log.d("API", "🔄 [syncProjectMetadata] Starting for project $projectId")

    var itemCount = 0

    // Atmospheric logs
    runCatching { api.getProjectAtmosphericLogs(projectId) }.onSuccess { logs ->
        localDataService.saveAtmosphericLogs(logs.map { it.toEntity(defaultRoomId = null) })
        itemCount += logs.size
    }

    // Equipment
    runCatching { api.getProjectEquipment(projectId) }.onSuccess { equipment ->
        localDataService.saveEquipment(equipment.map { it.toEntity() })
        itemCount += equipment.size
    }

    // Damages
    runCatching { api.getProjectDamageMaterials(projectId) }.onSuccess { damages ->
        localDataService.saveDamages(damages.mapNotNull { it.toEntity(defaultProjectId = projectId) })
        itemCount += damages.size
    }

    // Notes
    runCatching { api.getProjectNotes(projectId) }.onSuccess { notes ->
        localDataService.saveNotes(notes.mapNotNull { it.toEntity() })
        itemCount += notes.size
    }

    // Users
    runCatching { api.getProjectUsers(projectId) }.onSuccess { users ->
        localDataService.saveUsers(users.map { it.toEntity() })
        itemCount += users.size
    }

    // Albums
    runCatching {
        fetchAllPages { page -> api.getProjectAlbums(projectId, page) }
    }.onSuccess { albums ->
        val albumEntities = albums.map { it.toEntity(defaultProjectId = projectId) }
        localDataService.saveAlbums(albumEntities)
        itemCount += albums.size
    }

    Log.d("API", "✅ [syncProjectMetadata] Synced $itemCount items")
    SyncResult(success = true, itemsSynced = itemCount)
}
```

---

### 7. `syncProjectGraph(projectId)` - LEGACY WRAPPER

**Keep existing behavior for backward compatibility:**

```kotlin
suspend fun syncProjectGraph(projectId: Long): SyncResult = withContext(ioDispatcher) {
    Log.d("API", "🔄 [syncProjectGraph] Starting FULL sync for project $projectId")

    val results = mutableListOf<SyncResult>()

    // 1. Core data
    results += syncProjectCore(projectId)

    // 2. Structure
    results += syncProjectStructure(projectId)

    // 3. All room photos
    val roomIds = localDataService.getRoomIdsForProject(projectId)
    if (roomIds.isNotEmpty()) {
        results += syncAllRoomPhotos(projectId, roomIds)
    }

    // 4. Project-level photos
    results += syncProjectLevelPhotos(projectId)

    // 5. Metadata
    results += syncProjectMetadata(projectId)

    val totalItems = results.sumOf { it.itemsSynced }
    val allSuccess = results.all { it.success }

    Log.d("API", "✅ [syncProjectGraph] Completed - Total items: $totalItems, Success: $allSuccess")
    SyncResult(success = allSuccess, itemsSynced = totalItems)
}
```

---

## Usage Examples

### Room Detail Screen (HIGH PRIORITY)
```kotlin
// RoomDetailViewModel.kt
fun onRoomVisible() {
    viewModelScope.launch {
        // Only sync THIS room's photos
        val result = offlineSyncRepository.syncRoomPhotos(projectId, roomId)
        if (result.success) {
            Log.d("RoomDetail", "Synced ${result.itemsSynced} photos")
        }
    }
}
```

### Project List Screen (MEDIUM PRIORITY)
```kotlin
// HomeViewModel.kt
fun refreshProject(projectId: Long) {
    viewModelScope.launch {
        // Quick overview - no photos yet
        offlineSyncRepository.syncProjectCore(projectId)
        offlineSyncRepository.syncProjectStructure(projectId)

        // Photos in background (can be cancelled)
        backgroundScope.launch {
            delay(2000) // Let UI load first
            offlineSyncRepository.syncProjectLevelPhotos(projectId)
        }
    }
}
```

### Background Sync (LOW PRIORITY)
```kotlin
// WorkManager or background task
fun fullProjectSync(projectId: Long) {
    scope.launch {
        // Full sync, can take minutes
        offlineSyncRepository.syncProjectGraph(projectId)
    }
}
```

### Smart Cancellation
```kotlin
// RoomDetailFragment.kt
private var backgroundSyncJob: Job? = null

override fun onResume() {
    super.onResume()

    // Cancel any background photo sync
    backgroundSyncJob?.cancel()

    // Start high-priority room sync
    viewModel.onRoomVisible()
}

override fun onPause() {
    super.onPause()

    // Resume background sync when leaving room
    backgroundSyncJob = lifecycleScope.launch {
        offlineSyncRepository.syncProjectLevelPhotos(projectId)
    }
}
```

---

## Implementation Steps

### Phase 1: Extract Functions (2-3 hours)
**Status:** 🔴 Not Started
**No behavior changes, just reorganization**

1. ⬜ Create `SyncResult` data class + `SyncSegment` enum
2. ⬜ Extract `syncProjectCore()` (lines 83-161) - LEAN version (no metadata duplication)
3. ⬜ Extract `syncProjectStructure()` (lines 163-233) - Add ensureActive() checks
4. ⬜ Rename `refreshRoomPhotos()` → `syncRoomPhotos()` (minimal change, already exists)
5. ⬜ Extract `syncAllRoomPhotos()` (lines 235-266) - Add ensureActive() between rooms
6. ⬜ Extract `syncProjectLevelPhotos()` (lines 273-295) - Add ensureActive() between types
7. ⬜ Extract `syncProjectMetadata()` (lines 269-326) - Add ensureActive() checks
8. ⬜ Rewrite `syncProjectGraph()` to call new functions
9. ⬜ Add duration tracking to all functions

**Testing:** Run existing tests, verify `syncProjectGraph()` still works identically.

**Owner:** [ASSIGN]
**Due Date:** [TBD]

---

### Phase 2: Update Callers (1 hour)
**Status:** 🔴 Not Started
**Start using selective sync**

1. ⬜ Update `RoomDetailViewModel` to use `syncRoomPhotos()` instead of full sync
2. ⬜ Update `HomeViewModel` to use `syncProjectCore()` + `syncProjectStructure()`
3. ⬜ Keep `ProjectDetailViewModel` using full `syncProjectGraph()` (no change)
4. ⬜ Add cancellation in `RoomDetailFragment.onResume()` / `onPause()`
5. ⬜ Add telemetry to track which segments are called per screen

**Testing:** Manual test - verify room loads fast, background sync doesn't interfere.

**Owner:** [ASSIGN]
**Due Date:** [TBD after Phase 1]

---

### Phase 3: Measure & Optimize (30 min)
**Status:** 🔴 Not Started
**Verify improvement**

1. ⬜ Collect baseline metrics (time-to-first-photo, API call counts)
2. ⬜ Deploy Phase 1 + 2 to test device
3. ⬜ Test on device with slow network (throttled to 3G)
4. ⬜ Verify logs show no `/location-photos` calls when entering room
5. ⬜ Measure time-to-first-photo improvement
6. ⬜ Monitor for any data inconsistencies (missing rooms, etc.)

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
- ✅ Keep `syncProjectGraph()` unchanged behavior
- ✅ All existing callers continue working
- ✅ New callers opt-in to selective sync

### Rollout
1. **Week 1:** Implement Phase 1, test in dev
2. **Week 2:** Implement Phase 2, test on staging
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
**Mitigation:** Each function is self-contained. If core data is needed, call `syncProjectCore()` first.

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

1. **Review this plan** - any concerns or questions?
2. **Start Phase 1** - extract functions (2-3 hours of focused work)
3. **Test thoroughly** - run full test suite
4. **Ship Phase 2** - update callers selectively
5. **Measure improvement** - collect metrics
