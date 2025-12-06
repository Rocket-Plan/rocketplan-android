package com.example.rocketplan_android.ui.projects

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.example.rocketplan_android.RocketPlanApplication
import com.example.rocketplan_android.data.local.PhotoCacheStatus
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomPhotoSnapshotEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.logging.LogLevel
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.example.rocketplan_android.data.model.offline.WorkScopeItemRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import kotlin.collections.buildSet
import kotlin.collections.eachCount

import com.example.rocketplan_android.data.local.entity.preferredImageSource
import com.example.rocketplan_android.data.local.entity.preferredThumbnailSource

@OptIn(ExperimentalCoroutinesApi::class)
class RoomDetailViewModel(
    application: Application,
    private val projectId: Long,
    private val roomId: Long
) : AndroidViewModel(application) {

    private val rocketPlanApp = application as RocketPlanApplication
    private val localDataService = rocketPlanApp.localDataService
    private val offlineSyncRepository = rocketPlanApp.offlineSyncRepository
    private val remoteLogger = rocketPlanApp.remoteLogger
    private val imageProcessorRepository = rocketPlanApp.imageProcessorRepository
    private val imageProcessorQueueManager = rocketPlanApp.imageProcessorQueueManager
    private val authRepository = rocketPlanApp.authRepository
    private val dateFormatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MM/dd/yyyy", Locale.US)
    }
    private val damageDateFormatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MMM d, yyyy", Locale.US)
    }

    private val _uiState = MutableStateFlow<RoomDetailUiState>(RoomDetailUiState.Loading)
    val uiState: StateFlow<RoomDetailUiState> = _uiState

    private val _selectedTab = MutableStateFlow(RoomDetailTab.PHOTOS)
    val selectedTab: StateFlow<RoomDetailTab> = _selectedTab

    private val _isAwaitingRealtimePhotos = MutableStateFlow(false)
    val isAwaitingRealtimePhotos: StateFlow<Boolean> = _isAwaitingRealtimePhotos.asStateFlow()

    private var lastRefreshAt = 0L
    private var isRefreshing = false
    private var lastSyncedServerRoomId: Long? = null
    private var lastScopeSyncedRoomId: Long? = null
    private var lastScopeSyncAt = 0L
    private var currentPhotoLookupRoomId: Long? = null
    private var lastSnapshotRoomId: Long? = null
    private val _resolvedRoom = MutableStateFlow<OfflineRoomEntity?>(null)
    private val snapshotRefreshMutex = Mutex()
    private val _isSnapshotRefreshing = MutableStateFlow(false)
    val isSnapshotRefreshing: StateFlow<Boolean> = _isSnapshotRefreshing.asStateFlow()
    private val pendingAssemblyIds = mutableSetOf<String>()
    private val processedAssemblyIds = mutableSetOf<String>()
    private var assemblyWatcherJob: Job? = null
    private val scopeCatalogCache = MutableStateFlow<List<ScopeCatalogItem>>(emptyList())
    private var scopeCatalogCompanyId: Long? = null
    private val photoNoteCounts: Flow<Map<Long, Int>> =
        localDataService.observeNotes(projectId)
            .map { notes ->
                notes
                    .mapNotNull { it.photoId }
                    .groupingBy { it }
                    .eachCount()
            }
    val roomDamages: StateFlow<List<RoomDamageItem>> =
        combine(_resolvedRoom, localDataService.observeDamages(projectId)) { room, damages ->
            val resolvedRoom = room ?: return@combine emptyList()
            val formatter = requireNotNull(damageDateFormatter.get())
            val roomIds = buildSet {
                add(resolvedRoom.roomId)
                resolvedRoom.serverId?.let { add(it) }
            }
            damages
                .filter { damage -> damage.roomId != null && damage.roomId in roomIds }
                .sortedByDescending { it.updatedAt }
                .map { damage ->
                    val updatedAt = damage.updatedAt ?: damage.createdAt
                    RoomDamageItem(
                        id = damage.damageId,
                        title = damage.title,
                        description = damage.description,
                        severity = damage.severity,
                        updatedOn = updatedAt?.let { formatter.format(it) }
                    )
                }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val roomScopes: StateFlow<List<RoomScopeItem>> =
        combine(_resolvedRoom, localDataService.observeWorkScopes(projectId)) { room, scopes ->
            val resolvedRoom = room ?: return@combine emptyList()
            val formatter = requireNotNull(damageDateFormatter.get())
            val roomIds = buildSet {
                add(resolvedRoom.roomId)
                resolvedRoom.serverId?.let { add(it) }
            }
            scopes
                .filter { scope -> scope.roomId != null && scope.roomId in roomIds }
                .sortedByDescending { it.updatedAt }
                .map { scope ->
                    val updatedAt = scope.updatedAt ?: scope.createdAt
                    RoomScopeItem(
                        id = scope.workScopeId,
                        title = scope.name,
                        description = scope.description,
                        updatedOn = updatedAt?.let { formatter.format(it) }
                    )
                }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    init {
        Log.d(TAG, "📦 init(projectId=$projectId, roomId=$roomId)")
        viewModelScope.launch {
            localDataService.observeRooms(projectId)
                .map { rooms ->
                    rooms.firstOrNull { it.roomId == roomId || it.serverId == roomId }
                        ?.also { room ->
                            Log.d(
                                TAG,
                                "🏠 Resolved room: title='${room.title}', localId=${room.roomId}, serverId=${room.serverId}, navArg=$roomId"
                            )
                        }
                }
                .distinctUntilChanged()
                .collect { resolved ->
                    if (resolved == null) {
                        Log.d(TAG, "⏳ Room resolution pending for navArg=$roomId (projectId=$projectId)")
                        if (_resolvedRoom.value != null) {
                            _resolvedRoom.value = null
                        }
                        return@collect
                    }

                    if (shouldUpdateResolvedRoom(_resolvedRoom.value, resolved)) {
                        _resolvedRoom.value = resolved
                    } else {
                        Log.v(
                            TAG,
                            "🚫 Skipping redundant room emission; ids/title unchanged (roomId=${resolved.roomId}, serverId=${resolved.serverId})"
                        )
                    }
                }
        }

        viewModelScope.launch {
            _resolvedRoom.collectLatest { room ->
                if (room == null) {
                    Log.d(TAG, "⚠️ Room $roomId not yet available; emitting Loading")
                    _uiState.value = RoomDetailUiState.Loading
                    return@collectLatest
                }

                val localRoomId = room.roomId
                // Photos and albums are persisted with server room ID when available
                val photoLookupRoomId = room.serverId ?: room.roomId
                currentPhotoLookupRoomId = photoLookupRoomId
                pendingAssemblyIds.clear()
                processedAssemblyIds.clear()
                _isAwaitingRealtimePhotos.value = false
                assemblyWatcherJob?.cancel()
                assemblyWatcherJob = viewModelScope.launch {
                    observeAssembliesForRoom(localRoomId, photoLookupRoomId)
                }
                if (lastSnapshotRoomId != photoLookupRoomId) {
                    Log.d(TAG, "🗂 Refreshing photo snapshot for roomId=$photoLookupRoomId")
                    refreshSnapshot(photoLookupRoomId)
                } else {
                    Log.d(TAG, "🗂 Snapshot already fresh for roomId=$photoLookupRoomId; skipping refresh")
                }
                if (room.serverId != null && room.serverId != room.roomId) {
                    Log.d(TAG, "🧹 Clearing legacy snapshot for localRoomId=${room.roomId}")
                    localDataService.clearRoomPhotoSnapshot(room.roomId)
                }
                combine(
                    localDataService.observeNotes(projectId),
                    localDataService.observeAlbumsForRoom(photoLookupRoomId),
                    localDataService.observePhotoCountForRoom(photoLookupRoomId)
                ) { notes, albums, photoCount ->
                    val noteRoomIds = buildSet {
                        add(localRoomId)
                        room.serverId?.let { add(it) }
                    }
                    val roomNotes = notes.filter { note ->
                        val noteRoomId = note.roomId
                        noteRoomId != null && noteRoomId in noteRoomIds
                    }
                    Log.d(
                        TAG,
                        "✅ Room ready: '${room.title}', localRoomId=$localRoomId, serverId=${room.serverId}, photoLookupId=$photoLookupRoomId, photoCount=$photoCount, albumCount=${albums.size}, noteCount=${roomNotes.size}"
                    )
                    RoomDetailUiState.Ready(
                        header = room.toHeader(roomNotes),
                        albums = albums.toAlbumItems(),
                        photoCount = photoCount
                    )
                }.collect { state ->
                    _uiState.value = state
                }

                val serverId = room.serverId
                if (serverId != null && serverId != lastSyncedServerRoomId) {
                    lastSyncedServerRoomId = serverId
                    Log.d(TAG, "⚡️ Server room id resolved ($serverId); forcing photo refresh")
                    ensureRoomPhotosFresh(force = true)
                    ensureWorkScopesFresh(serverId, force = true)
                }
            }
        }
    }

    fun selectTab(tab: RoomDetailTab) {
        if (_selectedTab.value != tab) {
            _selectedTab.value = tab
        }
    }

    fun refreshWorkScopesIfStale() {
        val serverRoomId = _resolvedRoom.value?.serverId ?: return
        ensureWorkScopesFresh(serverRoomId)
    }

    private fun ensureWorkScopesFresh(serverRoomId: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && serverRoomId == lastScopeSyncedRoomId && now - lastScopeSyncAt < ROOM_REFRESH_INTERVAL_MS) {
            return
        }

        lastScopeSyncedRoomId = serverRoomId
        lastScopeSyncAt = now
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { offlineSyncRepository.syncRoomWorkScopes(projectId, serverRoomId) }
                .onFailure { error ->
                    Log.w(TAG, "⚠️ Failed to sync work scopes for room $serverRoomId", error)
                }
        }
    }

    fun addScopeItem(name: String, description: String?) {
        val room = _resolvedRoom.value
        if (room == null) {
            Log.w(TAG, "⚠️ Cannot add scope; room is not resolved yet")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val now = Date()
            val lookupRoomId = room.serverId ?: room.roomId
            val scope = OfflineWorkScopeEntity(
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = lookupRoomId,
                name = name.trim(),
                description = description?.trim().takeUnless { it.isNullOrBlank() },
                createdAt = now,
                updatedAt = now,
                syncStatus = SyncStatus.PENDING,
                isDirty = true
            )
            localDataService.saveWorkScopes(listOf(scope))
            Log.d(TAG, "📝 Added scope item '${scope.name}' for roomId=$lookupRoomId")
        }
    }

    suspend fun loadSavedScopeOptions(): List<ScopeTemplateOption> = withContext(Dispatchers.IO) {
        val room = _resolvedRoom.value ?: return@withContext emptyList()
        val roomIds = currentRoomIds(room)
        val scopes = localDataService.observeWorkScopes(projectId).first()
        val existingNames = scopes
            .filter { it.roomId != null && it.roomId in roomIds }
            .mapNotNull { scope ->
                scope.name.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
            }
            .toSet()

        scopes
            .filter { scope ->
                val name = scope.name.trim()
                name.isNotEmpty() &&
                    (scope.roomId == null || scope.roomId !in roomIds) &&
                    name.lowercase(Locale.US) !in existingNames
            }
            .map { scope ->
                ScopeTemplateOption(
                    id = scope.workScopeId.takeIf { it != 0L } ?: scope.serverId,
                    title = scope.name,
                    description = scope.description
                )
            }
    }

    suspend fun loadScopeCatalog(): List<ScopeCatalogItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "📋 loadScopeCatalog() starting")
        val companyId = resolveCompanyId() ?: run {
            Log.w(TAG, "⚠️ Cannot load scope catalog: missing companyId after refresh")
            return@withContext emptyList()
        }
        if (scopeCatalogCompanyId == companyId && scopeCatalogCache.value.isNotEmpty()) {
            Log.d(TAG, "📋 Returning cached scope catalog for companyId=$companyId (${scopeCatalogCache.value.size} items)")
            return@withContext scopeCatalogCache.value
        }
        val sheets = offlineSyncRepository.fetchWorkScopeCatalog(companyId)
        if (sheets.isEmpty()) {
            Log.w(TAG, "⚠️ Scope catalog fetch returned empty list for companyId=$companyId")
        }
        val catalogItems = sheets.flatMap { sheet ->
            sheet.workScopeItems.map { item ->
                ScopeCatalogItem(
                    id = item.id,
                    sheetId = sheet.id,
                    tabName = sheet.tabName,
                    category = item.category,
                    codePart1 = item.codePart1.orEmpty(),
                    codePart2 = item.codePart2.orEmpty(),
                    description = item.description,
                    unit = item.unit,
                    rate = item.rate
                )
            }
        }
        scopeCatalogCompanyId = companyId
        scopeCatalogCache.value = catalogItems
        Log.d(TAG, "📋 Cached ${catalogItems.size} scope catalog items for companyId=$companyId (sheets=${sheets.size})")
        catalogItems
    }

    private suspend fun resolveCompanyId(): Long? {
        authRepository.getStoredCompanyId()?.let {
            Log.d(TAG, "🏢 Using stored companyId=$it")
            return it
        }
        localDataService.getProject(projectId)?.companyId?.let {
            Log.d(TAG, "🏢 Using project companyId=$it for projectId=$projectId")
            return it
        }

        runCatching { authRepository.ensureUserContext() }
            .onFailure { Log.w(TAG, "⚠️ Failed to ensure user context for company lookup", it) }
        authRepository.getStoredCompanyId()?.let {
            Log.d(TAG, "🏢 Using refreshed companyId=$it")
            return it
        }

        val companyId = authRepository.getUserCompanies()
            .getOrNull()
            ?.firstOrNull()
            ?.id
        if (companyId != null) {
            authRepository.setActiveCompany(companyId)
            Log.d(TAG, "🏢 Selected first available companyId=$companyId from user companies")
            return companyId
        }
        Log.w(TAG, "⚠️ No companyId found in user companies")
        return null
    }

    suspend fun addCatalogItems(options: List<ScopeCatalogItem>): Boolean = withContext(Dispatchers.IO) {
        val room = _resolvedRoom.value
        if (room == null) {
            Log.w(TAG, "⚠️ Cannot add catalog scopes; room is not resolved yet")
            return@withContext false
        }
        val serverRoomId = room.serverId
        if (serverRoomId == null) {
            Log.w(TAG, "⚠️ Cannot add catalog scopes; room has no serverId yet (roomId=${room.roomId})")
            return@withContext false
        }

        val requestItems = options.map { option ->
            WorkScopeItemRequest(
                sheetId = option.sheetId,
                description = option.description,
                quantity = 1.0,
                category = option.category,
                codePart1 = option.codePart1,
                codePart2 = option.codePart2,
                unit = option.unit,
                rate = option.rate?.toDoubleOrNull() ?: 0.0
            )
        }

        val success = offlineSyncRepository.addWorkScopeItems(projectId, serverRoomId, requestItems)
        if (success) {
            runCatching { offlineSyncRepository.syncRoomWorkScopes(projectId, serverRoomId) }
                .onFailure { Log.w(TAG, "⚠️ Failed to refresh room work scopes after add", it) }
            Log.d(TAG, "🧾 Added catalog scopes via API for roomId=$serverRoomId (projectId=$projectId)")
        }
        success
    }

    fun addSavedScopeItems(options: List<ScopeTemplateOption>) {
        val room = _resolvedRoom.value
        if (room == null) {
            Log.w(TAG, "⚠️ Cannot add saved scopes; room is not resolved yet")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val roomIds = currentRoomIds(room)
            val existingNames = localDataService.observeWorkScopes(projectId).first()
                .filter { it.roomId != null && it.roomId in roomIds }
                .mapNotNull { it.name.trim().takeIf { name -> name.isNotEmpty() }?.lowercase(Locale.US) }
                .toSet()
            val now = Date()
            val lookupRoomId = room.serverId ?: room.roomId
            val newEntities = options
                .filter { it.title.isNotBlank() }
                .filter { option -> option.title.trim().lowercase(Locale.US) !in existingNames }
                .map { option ->
                    OfflineWorkScopeEntity(
                        uuid = UUID.randomUUID().toString(),
                        projectId = projectId,
                        roomId = lookupRoomId,
                        name = option.title.trim(),
                        description = option.description?.takeIf { it.isNotBlank() },
                        createdAt = now,
                        updatedAt = now,
                        syncStatus = SyncStatus.PENDING,
                        isDirty = true
                    )
                }
            if (newEntities.isNotEmpty()) {
                localDataService.saveWorkScopes(newEntities)
                Log.d(TAG, "🧾 Added ${newEntities.size} saved scope items for roomId=$lookupRoomId")
            } else {
                Log.d(TAG, "ℹ️ No new scope items to add for roomId=$lookupRoomId")
            }
        }
    }

    private fun currentRoomIds(room: OfflineRoomEntity): Set<Long> = buildSet {
        add(room.roomId)
        room.serverId?.let { add(it) }
    }

    fun ensureRoomPhotosFresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAt < ROOM_REFRESH_INTERVAL_MS) {
            return
        }
        if (isRefreshing) {
            return
        }

        val remoteRoomId = _resolvedRoom.value?.serverId
        if (remoteRoomId == null) {
            Log.d(TAG, "⏭️ Skipping remote photo refresh; room $roomId has no serverId yet")
            return
        }
        isRefreshing = true
        viewModelScope.launch {
            try {
                Log.d(
                    TAG,
                    "🔄 ensureRoomPhotosFresh(force=$force) -> syncRoomPhotos(projectId=$projectId, remoteRoomId=$remoteRoomId)"
                )
                val result = offlineSyncRepository.syncRoomPhotos(projectId, remoteRoomId)
                if (!result.success) {
                    Log.w(TAG, "⚠️ Room photo sync failed for roomId=$remoteRoomId", result.error)
                }
                Log.d(TAG, "🗂 Sync complete; refreshing snapshot for roomId=$remoteRoomId")
                refreshSnapshot(remoteRoomId)
            } catch (t: Throwable) {
                Log.e(TAG, "❌ Failed to refresh photos for remoteRoomId=$remoteRoomId", t)
                remoteLogger.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Failed to refresh room photos",
                    metadata = mapOf(
                        "projectId" to projectId.toString(),
                        "roomId" to remoteRoomId.toString()
                    )
                )
            } finally {
                lastRefreshAt = SystemClock.elapsedRealtime()
                isRefreshing = false
                Log.d(TAG, "✅ ensureRoomPhotosFresh done; lastRefreshAt=$lastRefreshAt")
            }
        }
    }

    val photoPagingData: Flow<PagingData<RoomPhotoItem>> =
        _resolvedRoom
            .map { room -> Pair(room?.roomId, room?.serverId ?: room?.roomId) }
            .distinctUntilChanged()
            .flatMapLatest { pair ->
                val localId = pair.first
                val lookupId = pair.second
                if (localId == null || lookupId == null) {
                    flowOf(PagingData.empty())
                } else {
                    Log.d(TAG, "📸 Setting up snapshot paging for room: localId=$localId, snapshotRoomId=$lookupId")
                    val formatter = requireNotNull(dateFormatter.get())
                    val snapshotFlow = localDataService
                        .pagedPhotoSnapshotsForRoom(lookupId)
                        .map { pagingData ->
                            pagingData.map { snapshot -> snapshot.toPhotoItem(formatter) }
                        }
                        .cachedIn(viewModelScope)

                    combine(snapshotFlow, photoNoteCounts) { pagingData, noteCounts ->
                        pagingData.map { item ->
                            item.copy(noteCount = noteCounts[item.id] ?: 0)
                        }
                    }
                }
            }
            .cachedIn(viewModelScope)

    private fun OfflineRoomEntity.toHeader(notes: List<OfflineNoteEntity>): RoomDetailHeader {
        val noteCount = notes.size
        val summary = when (noteCount) {
            0 -> "No Notes"
            1 -> "1 Note"
            else -> "$noteCount Notes"
        }
        return RoomDetailHeader(
            title = title,
            noteSummary = summary
        )
    }

    private fun OfflineRoomPhotoSnapshotEntity.toPhotoItem(formatter: SimpleDateFormat): RoomPhotoItem =
        RoomPhotoItem(
            id = photoId,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            capturedOn = capturedOn?.let { formatter.format(it) }
        )

    private fun shouldUpdateResolvedRoom(
        current: OfflineRoomEntity?,
        next: OfflineRoomEntity
    ): Boolean {
        if (current == null) return true
        return current.roomId != next.roomId ||
            current.serverId != next.serverId ||
            current.title != next.title
    }

    private suspend fun refreshSnapshot(roomId: Long) {
        snapshotRefreshMutex.withLock {
            _isSnapshotRefreshing.value = true
            try {
                localDataService.refreshRoomPhotoSnapshot(roomId)
                lastSnapshotRoomId = roomId
            } finally {
                _isSnapshotRefreshing.value = false
            }
        }
    }

    private fun OfflinePhotoEntity.toPhotoItem(formatter: SimpleDateFormat): RoomPhotoItem =
        RoomPhotoItem(
            id = photoId,
            imageUrl = preferredImageSource(),
            thumbnailUrl = preferredThumbnailSource(),
            capturedOn = capturedAt?.let { formatter.format(it) }
        )

    private fun List<OfflineAlbumEntity>.toAlbumItems(): List<RoomAlbumItem> {
        return this.map { album ->
            RoomAlbumItem(
                id = album.albumId,
                name = album.name,
                photoCount = album.photoCount,
                thumbnailUrl = album.thumbnailUrl
            )
        }
    }

    fun onPhotosAdded(result: PhotosAddedResult?) {
        if (result == null) return
        result.assemblyId?.let { pendingAssemblyIds.add(it) }
        _isAwaitingRealtimePhotos.value = true
        Log.d(TAG, "🪢 Photos added: count=${result.addedCount}, assembly=${result.assemblyId}")
    }

    private suspend fun observeAssembliesForRoom(localRoomId: Long, snapshotRoomId: Long) {
        val terminalStatuses = setOf(
            AssemblyStatus.COMPLETED.value,
            AssemblyStatus.FAILED.value,
            AssemblyStatus.CANCELLED.value
        )

        imageProcessorRepository.observeAssembliesByRoom(localRoomId).collectLatest { assemblies ->
            val statusById = assemblies.associateBy { it.assemblyId }
            val activeAssemblies = assemblies.filterNot { terminalStatuses.contains(it.status) }

            val newlyCompleted = assemblies
                .filter { it.status == AssemblyStatus.COMPLETED.value }
                .filter { processedAssemblyIds.add(it.assemblyId) }

            val finishedPending = pendingAssemblyIds.filter { id ->
                val status = statusById[id]?.status
                status != null && terminalStatuses.contains(status)
            }
            pendingAssemblyIds.removeAll(finishedPending.toSet())

            if (newlyCompleted.isNotEmpty()) {
                Log.d(
                    TAG,
                    "✅ Assemblies completed for room $localRoomId: ${newlyCompleted.map { it.assemblyId }}, refreshing snapshot"
                )
                refreshSnapshot(snapshotRoomId)
            }

            val awaiting = activeAssemblies.isNotEmpty() || pendingAssemblyIds.isNotEmpty()
            _isAwaitingRealtimePhotos.value = awaiting
            Log.d(
                TAG,
                "⏱️ Assembly watch: active=${activeAssemblies.size}, pendingIds=${pendingAssemblyIds.size}, awaiting=$awaiting"
            )
        }
    }

    fun onLocalPhotoCaptured(photoFile: File, mimeType: String, albumId: Long? = null) {
        val room = _resolvedRoom.value
        if (room == null) {
            Log.w(TAG, "⚠️ Ignoring captured photo because room is not resolved yet")
            remoteLogger.log(
                level = LogLevel.WARN,
                tag = TAG,
                message = "Ignoring captured photo because room is not resolved yet."
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val timestamp = Date()
            val lookupRoomId = room.serverId ?: room.roomId
            Log.d(TAG, "📸 Photo captured: file=${photoFile.name}, size=${photoFile.length()} bytes, roomId=$lookupRoomId, projectId=$projectId")

            val entity = OfflinePhotoEntity(
                uuid = UUID.randomUUID().toString(),
                projectId = projectId,
                roomId = lookupRoomId,
                albumId = albumId,
                fileName = photoFile.name,
                localPath = photoFile.absolutePath,
                mimeType = mimeType,
                fileSize = photoFile.length(),
                capturedAt = timestamp,
                createdAt = timestamp,
                updatedAt = timestamp,
                uploadStatus = "local_pending",
                syncStatus = SyncStatus.PENDING,
                isDirty = true,
                cacheStatus = PhotoCacheStatus.READY,
                cachedOriginalPath = photoFile.absolutePath,
                cachedThumbnailPath = null,
                lastAccessedAt = timestamp
            )
            localDataService.savePhotos(listOf(entity))
            Log.d(TAG, "♻️ Refreshing snapshot after local capture for roomId=$lookupRoomId")
            refreshSnapshot(lookupRoomId)
            Log.d(TAG, "✅ Photo saved to local database: uuid=${entity.uuid}, isDirty=true, syncStatus=PENDING")

            remoteLogger.log(
                level = LogLevel.INFO,
                tag = TAG,
                message = "Captured photo saved locally",
                metadata = mapOf(
                    "projectId" to projectId.toString(),
                    "roomId" to lookupRoomId.toString(),
                    "fileName" to photoFile.name
                )
            )
        }
    }

    companion object {
        private const val TAG = "RoomDetailVM"
        private const val ROOM_REFRESH_INTERVAL_MS = 10_000L

        fun provideFactory(
            application: Application,
            projectId: Long,
            roomId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RoomDetailViewModel::class.java)) {
                    "Unknown ViewModel class"
                }
                return RoomDetailViewModel(application, projectId, roomId) as T
            }
        }
    }
}

sealed class RoomDetailUiState {
    object Loading : RoomDetailUiState()
    data class Ready(
        val header: RoomDetailHeader,
        val albums: List<RoomAlbumItem> = emptyList(),
        val photoCount: Int = 0
    ) : RoomDetailUiState()
}

data class RoomDetailHeader(
    val title: String,
    val noteSummary: String
)

data class RoomPhotoItem(
    val id: Long,
    val imageUrl: String,
    val thumbnailUrl: String,
    val capturedOn: String?,
    val noteCount: Int = 0
)

data class RoomAlbumItem(
    val id: Long,
    val name: String,
    val photoCount: Int,
    val thumbnailUrl: String?
)

data class RoomDamageItem(
    val id: Long,
    val title: String,
    val description: String?,
    val severity: String?,
    val updatedOn: String?
)

data class RoomScopeItem(
    val id: Long,
    val title: String,
    val description: String?,
    val updatedOn: String?
)

data class ScopeTemplateOption(
    val id: Long?,
    val title: String,
    val description: String?
)

data class ScopeCatalogItem(
    val id: Long,
    val sheetId: Long,
    val tabName: String,
    val category: String,
    val codePart1: String,
    val codePart2: String,
    val description: String,
    val unit: String,
    val rate: String?
)

enum class RoomDetailTab {
    PHOTOS, DAMAGES, SCOPE
}
