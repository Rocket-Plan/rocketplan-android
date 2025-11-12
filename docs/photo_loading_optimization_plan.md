# Photo Loading Optimization Plan - Revised Architecture

## Problem Statement
When navigating into Room Detail, background project-level photo syncs continue running (visible in logs: `/api/projects/4970/location-photos?page=12`), causing:
- Network congestion
- Delayed room photo rendering
- Wasted bandwidth loading photos user doesn't need yet

## Core Issues with Initial Plan

### 1. Fragment-Repository Coupling
**Problem:** Direct repository calls from fragments bypass ViewModel layer and don't survive configuration changes.
**Solution:** Introduce `ProjectSyncCoordinator` owned by Activity/Application scope.

### 2. Job Tracking Won't Compile
**Problem:** `coroutineScope { launch {...} }` returns Unit, not Job. No mutex protection for concurrent access.
**Solution:** Properly capture Job from caller's launch, use Mutex or ConcurrentHashMap.

### 3. Incomplete Lifecycle Handling
**Problem:** Only cancels on `onViewCreated`, doesn't resume when leaving room.
**Solution:** Add symmetric hooks in `onDestroyView` via shared ViewModel.

### 4. False Priority Claims
**Problem:** `withContext(Dispatchers.IO)` doesn't change priority - same dispatcher.
**Solution:** Use limited dispatcher or explicit queue with priority levels.

### 5. LoadState Listener Leaks
**Problem:** Listener never removed, causes multiple resume attempts.
**Solution:** Use `viewLifecycleOwner.lifecycleScope.repeatOnLifecycle` + proper cleanup.

### 6. Undefined Resume Logic
**Problem:** No spec for persisting/tracking pagination state.
**Solution:** Define persistence strategy (DB table for sync state).

### 7. Invalid Room Migration
**Problem:** `@Query` annotation can't be used in migration.
**Solution:** Use proper `Migration` class with `execSQL`.

### 8. Weak Testing Plan
**Problem:** Only manual happy-path tests, no regression coverage.
**Solution:** Add instrumented tests for cancellation, resumption, process death.

---

## Revised Architecture

### Component 1: ProjectSyncCoordinator (Application-Scoped)
**Responsibility:** Centralized sync job management with lifecycle awareness

```kotlin
// New file: app/src/main/java/com/example/rocketplan_android/data/sync/ProjectSyncCoordinator.kt

class ProjectSyncCoordinator @Inject constructor(
    private val offlineSyncRepository: OfflineSyncRepository,
    private val syncStateDao: SyncStateDao,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val projectSyncJobs = ConcurrentHashMap<Long, Job>()
    private val roomSyncJobs = ConcurrentHashMap<Pair<Long, Long>, Job>() // (projectId, roomId)

    // Dedicated dispatchers for true priority separation
    private val highPriorityDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val normalPriorityDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val lowPriorityDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Start or resume project-level sync.
     * Returns Job that can be cancelled.
     */
    fun startProjectSync(
        projectId: Long,
        priority: SyncPriority = SyncPriority.NORMAL
    ): Job {
        // Cancel existing job for this project
        projectSyncJobs[projectId]?.cancel()

        val job = scope.launch(dispatcherForPriority(priority)) {
            try {
                // Load last sync state
                val lastPage = syncStateDao.getProjectSyncState(projectId)?.lastCompletedPage ?: 0

                val result = offlineSyncRepository.syncProjectGraph(
                    projectId = projectId,
                    resumeFromPage = lastPage
                )

                // Handle sync result
                result.onFailure { error ->
                    // Log error, update state to FAILED
                    syncStateDao.upsertProjectSyncState(
                        ProjectSyncStateEntity(
                            projectId = projectId,
                            lastCompletedPage = lastPage,
                            totalPages = null,
                            lastSyncTimestamp = System.currentTimeMillis(),
                            syncStatus = "FAILED"
                        )
                    )
                }
            } finally {
                projectSyncJobs.remove(projectId)
            }
        }

        projectSyncJobs[projectId] = job
        return job
    }

    /**
     * Start high-priority room-specific sync.
     * Non-blocking - queues pause operation but doesn't wait.
     */
    fun startRoomSync(
        projectId: Long,
        roomId: Long,
        pauseProjectSync: Boolean = true
    ): Job {
        if (pauseProjectSync) {
            // Non-blocking pause - just cancel and queue state save
            scope.launch(Dispatchers.IO) {
                pauseProjectSyncInternal(projectId)
            }
        }

        // Cancel existing room sync for this room
        roomSyncJobs[Pair(projectId, roomId)]?.cancel()

        val job = scope.launch(highPriorityDispatcher) { // Use dedicated high-priority dispatcher
            try {
                offlineSyncRepository.refreshRoomPhotos(projectId, roomId)
            } finally {
                roomSyncJobs.remove(Pair(projectId, roomId))
            }
        }

        roomSyncJobs[Pair(projectId, roomId)] = job
        return job
    }

    /**
     * Internal pause logic - suspending version.
     * Called from background coroutine to avoid blocking caller.
     */
    private suspend fun pauseProjectSyncInternal(projectId: Long) {
        projectSyncJobs[projectId]?.let { job ->
            // Request state snapshot from repository before cancelling
            val currentState = offlineSyncRepository.requestSyncStateSnapshot(projectId)

            // Cancel the job
            job.cancel()
            projectSyncJobs.remove(projectId)

            // Save state after cancellation completes
            if (currentState != null) {
                syncStateDao.upsertProjectSyncState(currentState)
            }
        }
    }

    /**
     * Resume project sync from where it was paused.
     * Starts at LOW priority to not interfere with room viewing.
     */
    fun resumeProjectSync(projectId: Long): Job {
        return startProjectSync(projectId, priority = SyncPriority.LOW)
    }

    /**
     * Cancel all syncs for a project (cleanup on project exit).
     */
    fun cancelAllSyncsForProject(projectId: Long) {
        projectSyncJobs[projectId]?.cancel()
        projectSyncJobs.remove(projectId)

        // Cancel all room syncs for this project
        roomSyncJobs.keys
            .filter { it.first == projectId }
            .forEach { key ->
                roomSyncJobs[key]?.cancel()
                roomSyncJobs.remove(key)
            }
    }

    private fun dispatcherForPriority(priority: SyncPriority): CoroutineDispatcher {
        return when (priority) {
            SyncPriority.HIGH -> highPriorityDispatcher
            SyncPriority.NORMAL -> normalPriorityDispatcher
            SyncPriority.LOW -> lowPriorityDispatcher
        }
    }
}

enum class SyncPriority {
    HIGH,    // Room photos user is viewing
    NORMAL,  // Initial project sync
    LOW      // Background sync after user entered room
}
```

---

### Component 2: Sync State Persistence
**Responsibility:** Track pagination state across app restarts

```kotlin
// New entity: app/src/main/java/com/example/rocketplan_android/data/local/entity/ProjectSyncStateEntity.kt

@Entity(tableName = "project_sync_state")
data class ProjectSyncStateEntity(
    @PrimaryKey
    val projectId: Long,
    val lastCompletedPage: Int,
    val totalPages: Int?,
    val lastSyncTimestamp: Long,
    val syncStatus: String // "IN_PROGRESS", "PAUSED", "COMPLETED", "FAILED"
)

// DAO methods to add to OfflineDao.kt:
@Dao
interface SyncStateDao {
    @Query("SELECT * FROM project_sync_state WHERE projectId = :projectId")
    suspend fun getProjectSyncState(projectId: Long): ProjectSyncStateEntity?

    @Upsert
    suspend fun upsertProjectSyncState(state: ProjectSyncStateEntity)

    @Query("DELETE FROM project_sync_state WHERE projectId = :projectId")
    suspend fun deleteProjectSyncState(projectId: Long)
}
```

---

### Component 3: Modified OfflineSyncRepository
**Responsibility:** Support resumable syncs and expose current state

```kotlin
// Modifications to OfflineSyncRepository.kt

class OfflineSyncRepository @Inject constructor(
    private val offlineApi: OfflineSyncApi,
    private val offlineDao: OfflineDao,
    private val syncStateDao: SyncStateDao, // NEW: Added dependency
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // NEW: Track current sync state in memory with thread-safe access
    private val _currentSyncStates = ConcurrentHashMap<Long, AtomicReference<ProjectSyncStateEntity>>()
    private val stateMutex = Mutex()

    /**
     * Modified to support resumption from specific page.
     */
    suspend fun syncProjectGraph(
        projectId: Long,
        resumeFromPage: Int = 0
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            // Initialize sync state with atomic reference for thread-safe updates
            val syncStateRef = AtomicReference(
                ProjectSyncStateEntity(
                    projectId = projectId,
                    lastCompletedPage = resumeFromPage,
                    totalPages = null,
                    lastSyncTimestamp = System.currentTimeMillis(),
                    syncStatus = "IN_PROGRESS"
                )
            )
            _currentSyncStates[projectId] = syncStateRef

            // Existing sync logic, but modified:
            var currentPage = resumeFromPage
            var hasMorePages = true

            while (hasMorePages) {
                // Check for cancellation between pages
                ensureActive()

                // Fetch page (existing logic)
                val pageResult = fetchRoomPhotoPages(projectId, currentPage)

                // Update state atomically after each page
                syncStateRef.updateAndGet { current ->
                    current.copy(
                        lastCompletedPage = currentPage,
                        totalPages = pageResult.totalPages,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                }

                hasMorePages = pageResult.hasMore
                currentPage++
            }

            // On completion: update status
            val finalState = syncStateRef.updateAndGet { it.copy(syncStatus = "COMPLETED") }
            syncStateDao.upsertProjectSyncState(finalState)

            Result.success(Unit)
        } catch (e: CancellationException) {
            // On cancellation: mark as PAUSED (state already has latest page)
            _currentSyncStates[projectId]?.get()?.copy(syncStatus = "PAUSED")?.let { state ->
                syncStateDao.upsertProjectSyncState(state)
            }
            throw e
        } catch (e: Exception) {
            // On error: mark as FAILED
            _currentSyncStates[projectId]?.get()?.copy(syncStatus = "FAILED")?.let { state ->
                syncStateDao.upsertProjectSyncState(state)
            }
            Result.failure(e)
        } finally {
            _currentSyncStates.remove(projectId)
        }
    }

    /**
     * Request a snapshot of current sync state.
     * Thread-safe - can be called from coordinator during cancellation.
     */
    suspend fun requestSyncStateSnapshot(projectId: Long): ProjectSyncStateEntity? {
        return stateMutex.withLock {
            _currentSyncStates[projectId]?.get()
        }
    }
}
```

---

### Component 4: Modified RoomDetailViewModel
**Responsibility:** Coordinate room-level sync through ProjectSyncCoordinator

**Decision: Resume Strategy**
After review, we'll use **hybrid approach**:
- Pause project sync when room becomes visible (onResume)
- Resume project sync when room photos finish loading OR when user leaves room (whichever comes first)

```kotlin
// Modifications to RoomDetailViewModel.kt

class RoomDetailViewModel @Inject constructor(
    private val projectSyncCoordinator: ProjectSyncCoordinator,
    private val localDataService: LocalDataService,
    // ... other dependencies
) : ViewModel() {

    private val _roomPhotosLoading = MutableStateFlow(false)
    val roomPhotosLoading: StateFlow<Boolean> = _roomPhotosLoading.asStateFlow()

    private var roomSyncJob: Job? = null
    private var hasResumedProjectSync = false

    /**
     * Called when room screen becomes visible.
     * Pauses project sync and starts high-priority room sync.
     */
    fun onRoomVisible() {
        val remoteRoomId = _resolvedRoom.value?.serverId ?: return

        hasResumedProjectSync = false

        roomSyncJob?.cancel()
        roomSyncJob = projectSyncCoordinator.startRoomSync(
            projectId = projectId,
            roomId = remoteRoomId,
            pauseProjectSync = true // Pause project sync while syncing room
        )

        _roomPhotosLoading.value = true

        // Monitor room sync completion
        viewModelScope.launch {
            roomSyncJob?.join()
            _roomPhotosLoading.value = false

            // Resume project sync once room photos are loaded
            if (!hasResumedProjectSync) {
                hasResumedProjectSync = true
                projectSyncCoordinator.resumeProjectSync(projectId)
            }
        }
    }

    /**
     * Called when room screen becomes invisible.
     * Ensures project sync is resumed (if not already).
     */
    fun onRoomHidden() {
        roomSyncJob?.cancel()
        roomSyncJob = null

        // Resume project sync if it wasn't already resumed after room photos loaded
        if (!hasResumedProjectSync) {
            hasResumedProjectSync = true
            projectSyncCoordinator.resumeProjectSync(projectId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        roomSyncJob?.cancel()
    }
}
```

---

### Component 5: Modified RoomDetailFragment
**Responsibility:** Trigger ViewModel lifecycle methods, observe loading state

```kotlin
// Modifications to RoomDetailFragment.kt

class RoomDetailFragment : Fragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupAdapters()
        observeViewModel()

        // DON'T call repository directly - use ViewModel
        // (removed: offlineSyncRepository.cancelProjectSync(...))
    }

    override fun onResume() {
        super.onResume()
        // Trigger high-priority room photo sync
        viewModel.onRoomVisible()
    }

    override fun onPause() {
        super.onPause()
        // Resume background project sync
        viewModel.onRoomHidden()
    }

    private fun observeViewModel() {
        // Existing photo data observation
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photoPagingData.collectLatest { pagingData ->
                    photoPagingAdapter.submitData(pagingData)
                }
            }
        }

        // NEW: Observe loading state
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.roomPhotosLoading.collect { isLoading ->
                    // Update loading UI (e.g., show/hide progress indicator)
                    updateLoadingUI(isLoading)
                }
            }
        }

        // NEW: Properly cleanup load state listener
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                photoPagingAdapter.loadStateFlow.collectLatest { loadStates ->
                    handleLoadState(loadStates)
                }
            }
        }
    }

    private fun handleLoadState(loadStates: CombinedLoadStates) {
        // Update UI based on load states
        // No need to resume sync here - handled by onPause()
    }
}
```

---

### Component 6: Room Database Migration
**Responsibility:** Add sync state table and photo index

```kotlin
// Add to AppDatabase.kt

@Database(
    entities = [
        // ... existing entities ...
        ProjectSyncStateEntity::class
    ],
    version = X + 1, // Increment version
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase {
    abstract fun syncStateDao(): SyncStateDao
    // ... other DAOs ...
}

// Add migration
val MIGRATION_X_TO_X_PLUS_1 = object : Migration(X, X + 1) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create sync state table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS project_sync_state (
                projectId INTEGER PRIMARY KEY NOT NULL,
                lastCompletedPage INTEGER NOT NULL,
                totalPages INTEGER,
                lastSyncTimestamp INTEGER NOT NULL,
                syncStatus TEXT NOT NULL
            )
        """)

        // Add index on offline_photos.roomId for faster queries
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_offline_photos_roomId
            ON offline_photos(roomId)
        """)
    }
}

// Register migration in database builder
Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
    .addMigrations(MIGRATION_X_TO_X_PLUS_1)
    .build()
```

---

## Implementation Order

### Phase 1: Foundation (No User-Facing Changes)
1. Add `ProjectSyncStateEntity` and `SyncStateDao`
2. Create Room migration and test it
3. Add `ProjectSyncCoordinator` class
4. Inject coordinator into Application module

**Testing:**
- Migration test: Verify table created, index exists, data preserved
- Unit test: ProjectSyncCoordinator job tracking with mocked repository

### Phase 2: Repository Modifications
1. Modify `OfflineSyncRepository.syncProjectGraph()` to support resumption
2. Add `getCurrentSyncState()` method
3. Update sync logic to save state on cancellation

**Testing:**
- Unit test: Verify resumption from saved page
- Unit test: Verify state saved on cancellation

### Phase 3: ViewModel Integration
1. Add sync coordinator to `RoomDetailViewModel`
2. Implement `onRoomVisible()` and `onRoomHidden()`
3. Add loading state flows

**Testing:**
- Unit test: Verify coordinator methods called correctly
- Unit test: Verify loading state changes

### Phase 4: Fragment Integration
1. Remove direct repository calls from `RoomDetailFragment`
2. Call ViewModel lifecycle methods in `onResume`/`onPause`
3. Observe loading state with proper lifecycle

**Testing:**
- Instrumented test: Navigate to room, verify project sync pauses
- Instrumented test: Navigate away, verify project sync resumes
- Instrumented test: Rapid room navigation, verify no crashes

### Phase 5: Polish & Optimization
1. Tune dispatcher parallelism limits
2. Add analytics/logging for sync performance
3. Add user-visible loading indicators

**Testing:**
- Manual test: Verify smooth UI during sync operations
- Performance test: Measure time to first photo vs baseline

---

## Comprehensive Testing Strategy

### Unit Tests
```kotlin
// ProjectSyncCoordinatorTest.kt
@Test
fun `startProjectSync cancels existing job`()

@Test
fun `startRoomSync pauses project sync`()

@Test
fun `pauseProjectSync saves state`()

@Test
fun `resumeProjectSync continues from saved page`()

@Test
fun `cancelAllSyncsForProject removes all jobs`()
```

### Integration Tests
```kotlin
// RoomDetailViewModelTest.kt
@Test
fun `onRoomVisible starts room sync with high priority`()

@Test
fun `onRoomHidden resumes project sync with low priority`()

@Test
fun `configuration change preserves sync state`()
```

### Instrumented Tests
```kotlin
// RoomDetailNavigationTest.kt
@Test
fun `navigating to room pauses background sync`() {
    // Launch app, start project sync
    // Navigate to room
    // Verify no project-level API calls in logs
    // Verify room-specific API call happens
}

@Test
fun `navigating away from room resumes sync`() {
    // Navigate to room
    // Navigate back
    // Verify project sync resumes from last page
}

@Test
fun `rapid room navigation does not leak jobs`() {
    // Navigate to room1, room2, room3 quickly
    // Verify only latest room sync is active
    // Verify no job leaks (check coordinator state)
}

@Test
fun `sync state survives process death`() {
    // Start sync, pause at page 5
    // Kill process
    // Restart app
    // Verify sync resumes from page 5
}
```

### Performance Tests
```kotlin
@Test
fun `measure time to first photo visible`() {
    // Baseline: Current implementation
    // Optimized: New implementation
    // Assert: Optimized < Baseline * 0.7 (30% improvement)
}
```

---

## Rollback Strategy

If optimization causes issues:
1. **Feature flag:** Add `ENABLE_PRIORITY_SYNC` flag
2. **Gradual rollout:** Enable for 10% → 50% → 100% of users
3. **Monitoring:** Track crash rate, ANR rate, sync success rate
4. **Quick disable:** Turn off flag if metrics degrade

---

## Open Questions to Resolve

1. **Dispatcher parallelism tuning:**
   - HIGH: 4 threads, NORMAL: 2 threads, LOW: 1 thread
   - Should we measure and adjust based on device capabilities?

2. **State persistence timing:**
   - Save on every page completion (more writes, safer)
   - Save only on pause (fewer writes, risk losing progress)

3. **Sync resumption delay:**
   - Resume immediately when leaving room
   - Wait 1-2 seconds to avoid thrashing on rapid navigation

4. **Error handling:**
   - If room sync fails, should we fall back to showing cached photos?
   - Should we retry failed syncs automatically?

---

## Success Metrics

**Primary:**
- Time to first photo visible: < 200ms (from navigation)
- Background sync paused when room visible: 100% of cases

**Secondary:**
- No increase in crash rate
- No increase in ANR rate
- Sync completion rate >= baseline
- User-perceived smoothness (measured via surveys)

---

## Architectural Decisions Log

### 1. Suspend vs Non-Suspend Coordinator API
**Decision:** `startRoomSync()` is non-suspending, queues pause operation in background.
**Rationale:** Fragment lifecycle methods (onResume/onPause) can't easily call suspend functions. Queueing the pause operation keeps the API simple while ensuring state is saved correctly.

### 2. Priority Dispatcher Implementation
**Decision:** Create dedicated dispatcher instances with `limitedParallelism()`.
**Rationale:** Ensures true priority separation. HIGH gets 4 threads, NORMAL gets 2, LOW gets 1. Room photos won't compete with background work.

### 3. State Persistence Race Prevention
**Decision:** Use `AtomicReference<ProjectSyncStateEntity>` + Mutex for snapshot requests.
**Rationale:** Repository thread updates state atomically. Coordinator thread can safely request snapshot without races. Mutex only needed for snapshot (rare), not updates (frequent).

### 4. Resume Trigger Strategy
**Decision:** Hybrid - resume when room photos load OR when user leaves, whichever comes first.
**Rationale:**
- Achieves Phase 3 goal (resume after room loads)
- Handles edge case where user leaves before room photos finish
- Simple flag (`hasResumedProjectSync`) prevents double-resumption

### 5. Result<Unit> Handling
**Decision:** Coordinator observes `Result.onFailure` and updates sync state to FAILED.
**Rationale:** Enables monitoring, retry logic, and prevents infinite resume loops on persistent failures.

### 6. Dependency Injection
**Decision:** Add `syncStateDao: SyncStateDao` to repository constructor.
**Rationale:** Repository needs to persist state on cancellation. DAO is the correct abstraction for database access.

---

## Next Steps

1. **Review this spec** with team for architectural approval
2. **Create JIRA tickets** for each phase
3. **Write migration tests** before any code changes
4. **Implement Phase 1** (foundation) with full test coverage
5. **Measure baseline performance** before optimizations
6. **Implement remaining phases** with continuous monitoring

---

## Fixed Issues from Initial Review

✅ **Suspend/async consistency**: `startRoomSync()` is non-suspending, queues pause in background
✅ **Priority dispatcher**: Dedicated dispatchers created up-front, room uses `highPriorityDispatcher`
✅ **Result handling**: Coordinator observes `Result.onFailure` and logs errors
✅ **State race prevention**: `AtomicReference` + Mutex prevents coordinator/repository races
✅ **Lifecycle clarity**: Hybrid resume strategy (load completion OR navigation away)
✅ **Missing dependency**: `syncStateDao` added to repository constructor
✅ **Migration validity**: Proper `Migration` class with `execSQL` (already correct in doc)
