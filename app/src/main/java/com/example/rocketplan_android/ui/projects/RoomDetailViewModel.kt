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
import com.example.rocketplan_android.data.local.SyncStatus
import com.example.rocketplan_android.data.local.entity.AssemblyStatus
import com.example.rocketplan_android.data.local.entity.PhotoStatus
import com.example.rocketplan_android.data.local.entity.OfflineAlbumEntity
import com.example.rocketplan_android.data.local.entity.OfflineNoteEntity
import com.example.rocketplan_android.data.local.entity.OfflinePhotoEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomEntity
import com.example.rocketplan_android.data.local.entity.OfflineRoomPhotoSnapshotEntity
import com.example.rocketplan_android.data.local.entity.OfflineWorkScopeEntity
import com.example.rocketplan_android.data.model.CategoryAlbums
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.ui.projects.addroom.RoomTypeCatalog
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()
    private val _events = MutableSharedFlow<RoomDetailEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<RoomDetailEvent> = _events.asSharedFlow()

    private var lastRefreshAt = 0L
    private var isRefreshing = false
    private var lastSyncedServerRoomId: Long? = null
    private var lastScopeSyncedRoomId: Long? = null
    private var lastScopeSyncAt = 0L
    private var lastDamagesSyncedRoomId: Long? = null
    private var lastDamagesSyncAt = 0L
    private var currentPhotoLookupRoomId: Long? = null
    private var lastSnapshotRoomId: Long? = null
    private val _resolvedRoom = MutableStateFlow<OfflineRoomEntity?>(null)
    private val snapshotRefreshMutex = Mutex()
    private val _isSnapshotRefreshing = MutableStateFlow(false)
    val isSnapshotRefreshing: StateFlow<Boolean> = _isSnapshotRefreshing.asStateFlow()
    private val _isPhotoRefreshInProgress = MutableStateFlow(false)
    val isPhotoRefreshInProgress: StateFlow<Boolean> = _isPhotoRefreshInProgress.asStateFlow()
    private val pendingAssemblyIds = mutableSetOf<String>()
    private val processedAssemblyIds = mutableSetOf<String>()
    private val _inFlightAssembly = MutableStateFlow<InFlightAssemblyState?>(null)
    val inFlightAssembly: StateFlow<InFlightAssemblyState?> = _inFlightAssembly.asStateFlow()
    private var inFlightAssemblyJob: Job? = null
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
            Log.d(TAG, "🔍 roomDamages: totalDamages=${damages.size}, roomIds=$roomIds")
            val filtered = damages.filter { damage -> damage.roomId != null && damage.roomId in roomIds }
            Log.d(TAG, "🔍 roomDamages: filteredCount=${filtered.size}, damageRoomIds=${damages.take(5).map { it.roomId }}")
            filtered
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
    val roomDamageSections: StateFlow<List<RoomDamageSection>> =
        combine(
            localDataService.observeRooms(projectId),
            localDataService.observeNotes(projectId),
            localDataService.observeWorkScopes(projectId),
            localDataService.observeDamages(projectId)
        ) { rooms, notes, scopes, damages ->
            val formatter = requireNotNull(damageDateFormatter.get())
            rooms.map { room ->
                val roomIds = buildSet {
                    add(room.roomId)
                    room.serverId?.let { add(it) }
                }
                val noteCount = notes.count { note ->
                    val noteRoomId = note.roomId
                    noteRoomId != null && noteRoomId in roomIds
                }
                val damageItems = damages
                    .filter { damage -> damage.roomId != null && damage.roomId in roomIds }
                    .sortedByDescending { it.updatedAt ?: it.createdAt }
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

                val scopeItems = scopes
                    .filter { scope -> scope.roomId != null && scope.roomId in roomIds }
                    .sortedByDescending { it.updatedAt ?: it.createdAt }
                    .map { scope ->
                        val updatedAt = scope.updatedAt ?: scope.createdAt
                        val code = listOfNotNull(scope.codePart1?.trim(), scope.codePart2?.trim())
                            .joinToString(separator = "")
                            .takeIf { it.isNotBlank() }
                        val lineTotal = scope.lineTotal ?: scope.rate?.let { rate ->
                            val qty = scope.quantity ?: 1.0
                            rate * qty
                        }
                        val groupTitle = scope.tabName?.takeIf { it.isNotBlank() }
                            ?: scope.category?.takeIf { it.isNotBlank() }
                            ?: scope.description?.takeIf { it.isNotBlank() }
                            ?: scope.name.takeIf { it.isNotBlank() }
                            ?: "Work Scope"
                        val lineTitle = scope.description?.takeIf { it.isNotBlank() }
                            ?: scope.name.takeIf { it.isNotBlank() }
                            ?: groupTitle
                        RoomScopeItem(
                            id = scope.workScopeId,
                            title = lineTitle,
                            description = scope.name
                                ?.takeIf { it.isNotBlank() && it != lineTitle },
                            updatedOn = updatedAt?.let { formatter.format(it) },
                            tabName = scope.tabName?.takeIf { it.isNotBlank() },
                            category = scope.category?.takeIf { it.isNotBlank() },
                            code = code,
                            quantity = scope.quantity,
                            unit = scope.unit,
                            rate = scope.rate,
                            lineTotal = lineTotal,
                            updatedAtMillis = updatedAt?.time,
                            groupTitle = groupTitle
                        )
                    }

                val scopeGroups = scopeItems
                    .groupBy { item -> item.groupTitle }
                    .map { (title, groupedItems) ->
                        val total = groupedItems.mapNotNull { item ->
                            item.lineTotal ?: item.rate?.let { rate ->
                                val qty = item.quantity ?: 1.0
                                rate * qty
                            }
                        }.takeIf { it.isNotEmpty() }?.sum()
                        val groupId = "${title.lowercase(Locale.US)}-${groupedItems.joinToString { it.id.toString() }.hashCode()}"
                        RoomScopeGroup(
                            id = groupId,
                            title = title,
                            total = total,
                            itemCount = groupedItems.size,
                            items = groupedItems.sortedByDescending { it.updatedAtMillis ?: 0L }
                        )
                    }
                    .sortedBy { it.title.lowercase(Locale.US) }

                RoomDamageSection(
                    roomId = room.roomId,
                    serverRoomId = room.serverId,
                    title = room.title,
                    noteSummary = noteSummaryForCount(noteCount),
                    iconRes = RoomTypeCatalog.resolveIconRes(
                        application,
                        typeId = room.roomTypeId,
                        iconName = room.roomType ?: room.title
                    ),
                    scopeGroups = scopeGroups,
                    damageItems = damageItems
                )
            }.sortedBy { it.title.lowercase(Locale.US) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
    val roomScopeGroups: StateFlow<List<RoomScopeGroup>> =
        combine(_resolvedRoom, localDataService.observeWorkScopes(projectId)) { room, scopes ->
            val resolvedRoom = room ?: return@combine emptyList()
            val formatter = requireNotNull(damageDateFormatter.get())
            val roomIds = buildSet {
                add(resolvedRoom.roomId)
                resolvedRoom.serverId?.let { add(it) }
            }
            Log.d(TAG, "🔍 roomScopes: totalScopes=${scopes.size}, roomIds=$roomIds")
            val filtered = scopes.filter { scope -> scope.roomId != null && scope.roomId in roomIds }
            Log.d(TAG, "🔍 roomScopes: filteredCount=${filtered.size}, scopeRoomIds=${scopes.take(5).map { it.roomId }}")
            val items = filtered
                .sortedByDescending { it.updatedAt ?: it.createdAt }
                .map { scope ->
                    val updatedAt = scope.updatedAt ?: scope.createdAt
                    val code = listOfNotNull(scope.codePart1?.trim(), scope.codePart2?.trim())
                        .joinToString(separator = "")
                        .takeIf { it.isNotBlank() }
                    val lineTotal = scope.lineTotal ?: scope.rate?.let { rate ->
                        val qty = scope.quantity ?: 1.0
                        rate * qty
                    }
                    val groupTitle = scope.tabName?.takeIf { it.isNotBlank() }
                        ?: scope.category?.takeIf { it.isNotBlank() }
                        ?: scope.description?.takeIf { it.isNotBlank() }
                        ?: scope.name.takeIf { it.isNotBlank() }
                        ?: "Work Scope"
                    val lineTitle = scope.description?.takeIf { it.isNotBlank() }
                        ?: scope.name.takeIf { it.isNotBlank() }
                        ?: groupTitle
                    RoomScopeItem(
                        id = scope.workScopeId,
                        title = lineTitle,
                        description = scope.name
                            ?.takeIf { it.isNotBlank() && it != lineTitle },
                        updatedOn = updatedAt?.let { formatter.format(it) },
                        tabName = scope.tabName?.takeIf { it.isNotBlank() },
                        category = scope.category?.takeIf { it.isNotBlank() },
                        code = code,
                        quantity = scope.quantity,
                        unit = scope.unit,
                        rate = scope.rate,
                        lineTotal = lineTotal,
                        updatedAtMillis = updatedAt?.time,
                        groupTitle = groupTitle
                    )
                }

            items
                .groupBy { item -> item.groupTitle }
                .map { (title, groupedItems) ->
                    val total = groupedItems.mapNotNull { item ->
                        item.lineTotal ?: item.rate?.let { rate ->
                            val qty = item.quantity ?: 1.0
                            rate * qty
                        }
                    }.takeIf { it.isNotEmpty() }?.sum()
                    val groupId = "${title.lowercase(Locale.US)}-${groupedItems.joinToString { it.id.toString() }.hashCode()}"
                    RoomScopeGroup(
                        id = groupId,
                        title = title,
                        total = total,
                        itemCount = groupedItems.size,
                        items = groupedItems.sortedByDescending { it.updatedAtMillis ?: 0L }
                    )
                }
                .sortedBy { it.title.lowercase(Locale.US) }
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
                    ensureDamagesFresh(serverId, force = true)
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

    fun refreshDamagesIfStale() {
        val serverRoomId = _resolvedRoom.value?.serverId ?: return
        ensureDamagesFresh(serverRoomId)
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

    private fun ensureDamagesFresh(serverRoomId: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && serverRoomId == lastDamagesSyncedRoomId && now - lastDamagesSyncAt < ROOM_REFRESH_INTERVAL_MS) {
            return
        }

        lastDamagesSyncedRoomId = serverRoomId
        lastDamagesSyncAt = now
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { offlineSyncRepository.syncRoomDamages(projectId, serverRoomId) }
                .onFailure { error ->
                    Log.w(TAG, "⚠️ Failed to sync damages for room $serverRoomId", error)
                    remoteLogger.log(
                        level = LogLevel.WARN,
                        tag = TAG,
                        message = "Failed to sync damages for roomId=$serverRoomId projectId=$projectId: ${error.message}"
                    )
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

    suspend fun addCatalogItems(options: List<ScopeCatalogSelection>): Boolean = withContext(Dispatchers.IO) {
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
            val quantity = if (option.quantity > 0) option.quantity else 1.0
            WorkScopeItemRequest(
                sheetId = option.item.sheetId,
                description = option.item.description,
                quantity = quantity,
                category = option.item.category,
                codePart1 = option.item.codePart1,
                codePart2 = option.item.codePart2,
                unit = option.item.unit,
                rate = option.item.rate?.toDoubleOrNull() ?: 0.0
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

    fun updateScopeItem(
        itemId: Long,
        title: String,
        description: String?,
        quantity: Double?,
        unit: String?,
        rate: Double?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val scope = localDataService.getWorkScopeById(itemId)
            if (scope == null) {
                Log.w(TAG, "⚠️ Cannot update scope; item $itemId not found")
                return@launch
            }

            val normalizedTitle = title.trim()
            if (normalizedTitle.isBlank()) {
                Log.w(TAG, "⚠️ Cannot update scope; blank title provided for item $itemId")
                return@launch
            }

            val now = Date()
            val normalizedDescription = description?.trim().takeUnless { it.isNullOrBlank() }
            val normalizedUnit = unit?.trim().takeUnless { it.isNullOrBlank() }
            val normalizedQuantity = quantity?.takeIf { it > 0 }
            val normalizedRate = rate?.takeIf { it >= 0 }
            val resolvedQuantity = normalizedQuantity ?: scope.quantity
            val resolvedRate = normalizedRate ?: scope.rate
            val computedLineTotal = resolvedRate?.let { rateValue ->
                val qty = resolvedQuantity ?: 1.0
                rateValue * qty
            } ?: scope.lineTotal

            val updated = scope.copy(
                name = normalizedTitle,
                description = normalizedDescription,
                quantity = normalizedQuantity ?: scope.quantity,
                unit = normalizedUnit,
                rate = resolvedRate,
                lineTotal = computedLineTotal,
                updatedAt = now,
                isDirty = true,
                syncStatus = SyncStatus.PENDING
            )
            localDataService.saveWorkScopes(listOf(updated))
            Log.d(TAG, "✏️ Updated scope item id=$itemId for roomId=${scope.roomId}")
        }
    }

    fun deleteScopeItem(itemId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val scope = localDataService.getWorkScopeById(itemId)
            if (scope == null) {
                Log.w(TAG, "⚠️ Cannot delete scope; item $itemId not found")
                return@launch
            }

            val now = Date()
            val deleted = scope.copy(
                isDeleted = true,
                isDirty = true,
                syncStatus = SyncStatus.PENDING,
                updatedAt = now
            )
            localDataService.saveWorkScopes(listOf(deleted))
            Log.d(TAG, "🗑️ Marked scope item id=$itemId deleted for roomId=${scope.roomId}")
        }
    }

    fun deleteRoom() {
        if (_isDeleting.value) return
        val room = _resolvedRoom.value
        if (room == null) {
            viewModelScope.launch {
                _events.emit(RoomDetailEvent.Error("Room not found"))
            }
            return
        }

        _isDeleting.value = true
        viewModelScope.launch {
            try {
                val result = offlineSyncRepository.deleteRoom(projectId, room.roomId)
                _events.emit(RoomDetailEvent.RoomDeleted(result.synced))
            } catch (t: Throwable) {
                Log.e(TAG, "❌ Failed to delete room ${room.roomId}", t)
                remoteLogger.log(
                    level = LogLevel.ERROR,
                    tag = TAG,
                    message = "Failed to delete room",
                    metadata = mapOf(
                        "projectId" to projectId.toString(),
                        "roomId" to room.roomId.toString(),
                        "serverRoomId" to (room.serverId?.toString() ?: "null")
                    )
                )
                _events.emit(RoomDetailEvent.Error(t.message ?: "Unable to delete room"))
            } finally {
                _isDeleting.value = false
            }
        }
    }

    private fun currentRoomIds(room: OfflineRoomEntity): Set<Long> = buildSet {
        add(room.roomId)
        room.serverId?.let { add(it) }
    }

    fun ensureRoomPhotosFresh(
        force: Boolean = false,
        notifyRefresh: Boolean = false,
        ignoreCheckpoint: Boolean = false
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshAt < ROOM_REFRESH_INTERVAL_MS) {
            return false
        }
        if (isRefreshing) {
            return false
        }

        val remoteRoomId = _resolvedRoom.value?.serverId
        if (remoteRoomId == null) {
            Log.d(TAG, "⏭️ Skipping remote photo refresh; room $roomId has no serverId yet")
            return false
        }
        isRefreshing = true
        if (notifyRefresh) {
            _isPhotoRefreshInProgress.value = true
        }
        viewModelScope.launch {
            try {
                Log.d(
                    TAG,
                    "🔄 ensureRoomPhotosFresh(force=$force, ignoreCheckpoint=$ignoreCheckpoint) -> " +
                        "syncRoomPhotos(projectId=$projectId, remoteRoomId=$remoteRoomId)"
                )
                val result = offlineSyncRepository.syncRoomPhotos(
                    projectId,
                    remoteRoomId,
                    ignoreCheckpoint = ignoreCheckpoint,
                    source = "RoomDetailFragment"
                )
                if (!result.success) {
                    Log.w(TAG, "⚠️ Room photo sync failed for roomId=$remoteRoomId", result.error)
                }
                imageProcessorQueueManager.reconcileProcessingAssemblies(source = "room_pull_to_refresh")
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
                _isPhotoRefreshInProgress.value = false
                Log.d(TAG, "✅ ensureRoomPhotosFresh done; lastRefreshAt=$lastRefreshAt")
            }
        }
        return true
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
        return RoomDetailHeader(
            title = title,
            noteSummary = noteSummaryForCount(notes.size),
            iconRes = RoomTypeCatalog.resolveIconRes(
                getApplication(),
                typeId = roomTypeId,
                iconName = roomType ?: title
            )
        )
    }

    private fun noteSummaryForCount(count: Int): String {
        return when (count) {
            0 -> "No Notes"
            1 -> "1 Note"
            else -> "$count Notes"
        }
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
        return this
            .filterNot { album ->
                val isCategoryAlbum = CategoryAlbums.isCategory(album.name)
                if (isCategoryAlbum) {
                    Log.d(TAG, "🚫 Filtering category album ${album.name} (${album.albumId})")
                }
                isCategoryAlbum
            }
            .map { album ->
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
                    "✅ Assemblies completed for room $localRoomId: ${newlyCompleted.map { it.assemblyId }}, syncing photos from server"
                )
                ensureRoomPhotosFresh(force = true)
            }

            val awaiting = activeAssemblies.isNotEmpty() || pendingAssemblyIds.isNotEmpty()
            _isAwaitingRealtimePhotos.value = awaiting
            Log.d(
                TAG,
                "⏱️ Assembly watch: active=${activeAssemblies.size}, pendingIds=${pendingAssemblyIds.size}, awaiting=$awaiting"
            )

            if (activeAssemblies.isNotEmpty()) {
                val active = activeAssemblies.first()
                inFlightAssemblyJob?.cancel()
                inFlightAssemblyJob = viewModelScope.launch {
                    imageProcessorRepository.observePhotosByAssemblyLocalId(active.id)
                        .collectLatest { photos ->
                            val processed = photos.count { it.status == PhotoStatus.COMPLETED.value }
                            _inFlightAssembly.value = InFlightAssemblyState(
                                assemblyId = active.assemblyId,
                                processedCount = processed,
                                totalCount = active.totalFiles
                            )
                        }
                }
            } else {
                inFlightAssemblyJob?.cancel()
                inFlightAssemblyJob = null
                _inFlightAssembly.value = null
            }
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

        val lookupRoomId = room.serverId ?: room.roomId
        Log.d(
            TAG,
            "📸 Photo captured (deferred): file=${photoFile.name}, size=${photoFile.length()} bytes, roomId=$lookupRoomId, projectId=$projectId. " +
                "Skipping local placeholder until image processor returns processed assets."
        )
        val message = "Photo will be added after processing finishes. No local placeholder saved."
        remoteLogger.log(
            level = LogLevel.WARN,
            tag = TAG,
            message = message,
            metadata = mapOf(
                "projectId" to projectId.toString(),
                "roomId" to lookupRoomId.toString(),
                "fileName" to photoFile.name
            )
        )
        viewModelScope.launch {
            _events.emit(RoomDetailEvent.Error(message))
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

sealed class RoomDetailEvent {
    data class RoomDeleted(val synced: Boolean) : RoomDetailEvent()
    data class Error(val message: String) : RoomDetailEvent()
}

data class RoomDetailHeader(
    val title: String,
    val noteSummary: String,
    val iconRes: Int
)

data class RoomPhotoItem(
    val id: Long,
    val imageUrl: String,
    val thumbnailUrl: String,
    val capturedOn: String?,
    val noteCount: Int = 0
)

data class InFlightAssemblyState(
    val assemblyId: String,
    val processedCount: Int,
    val totalCount: Int
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
    val updatedOn: String?,
    val tabName: String? = null,
    val category: String? = null,
    val code: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val rate: Double? = null,
    val lineTotal: Double? = null,
    val updatedAtMillis: Long? = null,
    val groupTitle: String
)

data class RoomScopeGroup(
    val id: String,
    val title: String,
    val total: Double?,
    val itemCount: Int,
    val items: List<RoomScopeItem>
)

data class RoomDamageSection(
    val roomId: Long,
    val serverRoomId: Long?,
    val title: String,
    val noteSummary: String,
    val iconRes: Int,
    val scopeGroups: List<RoomScopeGroup>,
    val damageItems: List<RoomDamageItem>
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

data class ScopeCatalogSelection(
    val item: ScopeCatalogItem,
    val quantity: Double
)

enum class RoomDetailTab {
    PHOTOS, DAMAGES, SCOPE
}
