⏺ Perfect! Now I have a complete understanding. Let me create an optimization plan:

  🎯 Optimization Plan: Priority Room Photo Loading

  Current Problem

  From the logs, when you click into a room (Bathroom), the app is still loading page 12 of project-level photos (/api/projects/4970/location-photos?page=12) in the background. This is
  happening because there's a background sync continuing from a previous screen that wasn't cancelled when entering the Room Detail screen.

  Current Architecture (What We Found)

  1. Room Detail loads photos from local database - This is actually GOOD! It's fast.
  2. On resume, it refreshes room photos via refreshRoomPhotos() - This is room-specific, also GOOD.
  3. But project-level syncs continue in background - This is the PROBLEM we saw in logs.

  ---
  📋 Optimization Strategy

  Phase 1: Stop Background Project Syncs When Entering Room Detail

  Goal: Pause ongoing project-level photo pagination when user navigates into a specific room

  Changes needed:
  1. Add sync cancellation in RoomDetailFragment
    - Cancel any ongoing syncProjectGraph() jobs when entering room
    - Use coroutine Job tracking in OfflineSyncRepository
  2. Make project sync jobs cancellable
    - Store reference to sync job in OfflineSyncRepository
    - Add cancelProjectSync(projectId) method
    - Cancel on Room Detail navigation

  Files to modify:
  - RoomDetailFragment.kt:96 - Add sync cancellation in onViewCreated()
  - OfflineSyncRepository.kt - Add job tracking and cancellation

  ---
  Phase 2: Prioritize Current Room Photos

  Goal: Load current room photos immediately, defer other rooms

  Changes needed:
  1. Modify refreshRoomPhotos() to be higher priority
    - Use withContext(Dispatchers.IO) with higher priority
    - Add loading state to show user photos are refreshing
  2. Add priority queue for room photo loading
    - Current room = HIGH priority (load immediately)
    - Other rooms = LOW priority (load in background)

  Files to modify:
  - RoomDetailViewModel.kt:126-161 - Add priority loading state
  - OfflineSyncRepository.kt:335-357 - Add dispatcher priority

  ---
  Phase 3: Resume Background Sync After Room Photos Load

  Goal: Once current room photos are loaded, resume background project sync

  Changes needed:
  1. Add callback when room photos finish loading
    - Listen to photoPagingData first load complete
    - Resume project sync after initial room photos rendered
  2. Add smart sync resumption
    - Track which pages were already loaded
    - Resume from last page instead of restarting

  Files to modify:
  - RoomDetailFragment.kt:237-240 - Add load state listener
  - OfflineSyncRepository.kt - Add sync resumption with page tracking

  ---
  Phase 4: Optimize Database Queries

  Goal: Faster room photo queries with proper indexing

  Changes needed:
  1. Add database index on roomId column
    - Speeds up WHERE roomId = :roomId queries
    - Especially important for projects with many photos

  Files to modify:
  - Add migration in database schema with index creation

  ---
  📊 Detailed Implementation Plan

  Task 1: Cancel Background Project Syncs ⚡ (Highest Priority)

  Effort: Medium | Impact: High

  // RoomDetailFragment.kt - onViewCreated()
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
      super.onViewCreated(view, savedInstanceState)

      // NEW: Cancel any ongoing project syncs
      lifecycleScope.launch {
          offlineSyncRepository.cancelProjectSync(args.projectId)
      }

      bindViews(view)
      // ... rest of setup
  }

  // OfflineSyncRepository.kt
  private val projectSyncJobs = mutableMapOf<Long, Job>()

  suspend fun cancelProjectSync(projectId: Long) {
      projectSyncJobs[projectId]?.cancel()
      projectSyncJobs.remove(projectId)
  }

  suspend fun syncProjectGraph(...) {
      val job = coroutineScope {
          launch {
              // existing sync logic
          }
      }
      projectSyncJobs[projectId] = job
  }

  ---
  Task 2: Add Loading Priority to Room Photos ⚡

  Effort: Small | Impact: Medium

  // RoomDetailViewModel.kt
  private val _roomPhotosLoading = MutableStateFlow(false)
  val roomPhotosLoading: StateFlow<Boolean> = _roomPhotosLoading.asStateFlow()

  fun ensureRoomPhotosFresh(force: Boolean = false) {
      val now = System.currentTimeMillis()
      if (!force && (now - lastRefreshMs) < ROOM_REFRESH_INTERVAL_MS) return

      viewModelScope.launch {
          _roomPhotosLoading.value = true
          try {
              withContext(Dispatchers.IO) {
                  // HIGH PRIORITY: Load current room first
                  offlineSyncRepository.refreshRoomPhotos(projectId, remoteRoomId)
              }
          } finally {
              _roomPhotosLoading.value = false
              lastRefreshMs = now
          }
      }
  }

  ---
  Task 3: Resume Background Sync After Room Loads ⏱️

  Effort: Medium | Impact: Medium

  // RoomDetailFragment.kt
  private var hasResumedBackgroundSync = false

  photoPagingAdapter.addLoadStateListener { loadStates ->
      val isInitialLoad = loadStates.refresh is LoadState.NotLoading
      val hasItems = photoPagingAdapter.itemCount > 0

      if (isInitialLoad && hasItems && !hasResumedBackgroundSync) {
          hasResumedBackgroundSync = true
          // Resume background project sync AFTER room photos loaded
          lifecycleScope.launch {
              delay(1000) // Small delay to ensure UI is smooth
              offlineSyncRepository.resumeProjectSync(args.projectId)
          }
      }
  }

  ---
  Task 4: Add Database Index 📊

  Effort: Small | Impact: Medium

  // Add to database migration
  @Query("""
      CREATE INDEX IF NOT EXISTS index_offline_photos_roomId 
      ON offline_photos(roomId)
  """)

  ---
  🎯 Expected Performance Improvements

  Before:
  - Room Detail opens → Project sync page 12 still loading (432ms per page)
  - User sees loading state while background downloads continue
  - Network congestion from concurrent requests
  - Slower room photo rendering due to shared resources

  After:
  - Room Detail opens → Cancel ongoing project sync immediately
  - Prioritize current room photos (load in <200ms from local DB)
  - Resume background sync after room photos visible
  - Smoother UI, faster perceived performance

  ---
  ✅ Testing Plan

  1. Test cancellation: Navigate to room mid-sync, verify logs show sync cancelled
  2. Test priority: Measure time from navigation to first photo visible
  3. Test resumption: Verify background sync resumes after room loads
  4. Test edge cases:
    - Navigate between rooms quickly
    - Navigate back during room refresh
    - Network offline scenarios

  ---
  Would you like me to start implementing this plan? I recommend starting with Task 1 (Cancel Background Syncs) as it will have the immediate highest impact on the issue you're seeing in
  the logs.

  ---
  ## Flow Chart: Background Sync + Room Priority

  ```
  App login / Projects screen shown
           |
           v
  ProjectsViewModel.ensureInitialSync()
           |
           v
  SyncQueueManager.enqueue(EnsureUserContext)
           |
           v
  EnsureUserContext completes ──► enqueue(SyncProjects)
           |
           v
  SyncProjects job
     ├─ refresh auth context if needed
     ├─ syncRepository.syncUserProjects()/syncCompanyProjects()
     ├─ localDataService.getAllProjects()
     └─ enqueue SyncProjectGraph(projectId, prio = 2 + index)
           |
           v
  Queue loop picks next SyncProjectGraph
           |
           v
  scope.launch { activeProjectSyncJobs[projectId] = Job }
           |
           v
  syncRepository.syncProjectGraph(projectId)
     ├─ syncProjectEssentials
     ├─ syncProjectMetadata
     ├─ syncAllRoomPhotos
     └─ syncProjectLevelPhotos
        (ensureActive() all along for cancellation)
           |
           v
  Job finishes ──► remove activeProjectSyncJobs entry ──► PhotoCacheScheduler.schedulePrefetch()
  ```

  Room-detail overlay:

  ```
  User navigates to RoomDetailFragment
           |
           v
  onResume → RoomDetailViewModel.onRoomVisible()
           |
           ├─ syncQueueManager.cancelProjectSync(projectId)
           └─ ensureRoomPhotosFresh() → offlineSyncRepository.refreshRoomPhotos()
           |
           v
  Room photos render from local DB without project-level contention
           |
           v
  onPause → RoomDetailViewModel.onRoomHidden()
           |
           └─ syncQueueManager.prioritizeProject(projectId)  (re-enqueue at priority 0)
           |
           v
  Queue resumes project sync via the tracked job path above
  ```
