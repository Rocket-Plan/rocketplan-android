# RocketPlan Android - Architecture

## Table of Contents

1. [Overview](#1-overview--app-structure)
2. [Navigation & UI Layer](#2-navigation--ui-layer)
3. [Data Layer](#3-data-layer)
4. [Networking](#4-networking)
5. [Sync Architecture](#5-sync-architecture)
6. [Push Handlers](#6-push-handlers)
7. [Image Processing & Photo System](#7-image-processing--photo-system)
8. [Realtime (Pusher)](#8-realtime-pusher)
9. [FLIR Integration](#9-flir-integration)
10. [Support Chat](#10-support-chat)
11. [Key Patterns & Conventions](#11-key-patterns--conventions)

---

## 1. Overview & App Structure

### Single-Activity Architecture

The app uses a single `MainActivity` with the Jetpack Navigation Component. All screens are Fragments managed by `NavController`. The activity handles:

- Bottom navigation (Projects, Map, Notifications, People)
- Toolbar and drawer layout
- Fullscreen mode for camera screens (batch capture, FLIR)
- OAuth deep link handling (`rocketplan://oauth2/redirect`)
- Sync status banner (offline/syncing indicator)

**File:** `ui/MainActivity.kt`

### Dependency Injection

Manual service locator via `RocketPlanApplication`. No Dagger/Hilt. The Application class constructs and exposes all services as properties:

```
RocketPlanApplication
├── localDataService: LocalDataService
├── authRepository: AuthRepository
├── syncQueueManager: SyncQueueManager
├── offlineSyncRepository: OfflineSyncRepository
├── imageProcessorQueueManager: ImageProcessorQueueManager
├── supportSyncService: SupportSyncService
├── timecardSyncService: TimecardSyncService
├── photoCacheManager: PhotoCacheManager
├── secureStorage: SecureStorage
├── remoteLogger: RemoteLogger
├── syncNetworkMonitor: SyncNetworkMonitor
├── pusherService: PusherService
├── photoSyncRealtimeManager: PhotoSyncRealtimeManager
├── projectRealtimeManager: ProjectRealtimeManager
├── notesRealtimeManager: NotesRealtimeManager
├── imageProcessorRealtimeManager: ImageProcessorRealtimeManager
└── ... (20+ services total)
```

ViewModels access services via `(requireActivity().application as RocketPlanApplication).serviceName`.

**File:** `RocketPlanApplication.kt`

### Application Initialization Order

1. Sentry crash reporting
2. FLIR SDK (if FLIR build)
3. Room database + LocalDataService
4. SecureStorage, RemoteLogger, PhotoCacheManager
5. AuthRepository
6. Retrofit API services
7. Sync services (RoomType, Support, Timecard)
8. OfflineSyncRepository + SyncQueueManager
9. Image processor infrastructure
10. WorkManager retry scheduler (every 15 min)
11. Network monitors
12. Cold-start recovery (stranded assemblies)
13. Data repair (fix mismatched photo roomIds)

### Build Variants

Two dimensions: **environment** x **device type**.

| Environment | Package suffix | API target |
|-------------|---------------|------------|
| `dev`       | `.dev`        | Development server |
| `staging`   | `.staging`    | Staging server |
| `prod`      | (none)        | Production |

| Device type | Description |
|-------------|-------------|
| `standard`  | Regular Android devices |
| `flir`      | FLIR thermal camera devices (ARM64 native libs) |

Common builds: `devStandardDebug`, `devFlirDebug`, `stagingStandardDebug`, `prodStandardRelease`.

### Package Structure

```
com.example.rocketplan_android/
├── auth/           # OAuth web view
├── config/         # AppConfig (environment detection)
├── data/
│   ├── api/        # Retrofit API interfaces, DTOs
│   ├── local/      # Room database, DAO, entities
│   ├── queue/      # ImageProcessorQueueManager
│   ├── repository/ # Repositories, sync services, push handlers, mappers
│   └── sync/       # SyncQueueManager, ProjectSyncOrchestrator, SyncJob
├── logging/        # Remote logging (Sentry + custom)
├── notifications/  # Push notification handling
├── realtime/       # Pusher service and realtime managers
├── thermal/        # FLIR thermal imaging (shared code)
├── ui/             # Fragments, ViewModels, adapters
│   ├── auth/       # Login, signup, email check
│   ├── company/    # Company info screen
│   ├── conflict/   # Sync conflict resolution UI
│   ├── debug/      # FLIR test screens
│   ├── imageprocessor/ # Assembly list/config UI
│   ├── login/      # Login fragment
│   ├── map/        # Map tab
│   ├── people/     # People tab
│   ├── projects/   # Project list, detail, room detail, photo capture
│   ├── rocketdry/  # Equipment, moisture logs, atmospheric logs
│   ├── settings/   # App settings
│   ├── support/    # Support chat
│   ├── syncstatus/ # Sync debug screen
│   └── timecard/   # Timecard management
├── util/           # DateUtils, extensions, helpers
└── work/           # WorkManager tasks (image processor retry)
```

---

## 2. Navigation & UI Layer

### Navigation Graph

Defined in `res/navigation/mobile_navigation.xml`. Key flows:

**Auth Flow:**
```
emailCheckFragment → loginFragment
                   → signUpFragment
                   → oauthWebViewFragment
                   → forgotPasswordFragment
```

**Main Tabs (bottom nav):**
- `nav_map` - Map view (hidden on FLIR builds)
- `nav_projects` - Project list
- `nav_notifications` - Notifications
- `nav_people` - Team members

**Project Flow:**
```
ProjectList → CreateProject → AddressSearch / ManualEntry → ProjectTypeSelection
            → ProjectLanding (overview cards)
              → ProjectDetail (room grid)
              → ProjectNotes
              → ProjectLossInfo
              → TimecardFragment
              → RocketDry (equipment, atmospheric, moisture tabs)
```

**Room Detail Flow:**
```
ProjectDetail → RoomDetail (tabs: photos, notes, damages, work scope)
                → BatchCapture (regular photo)
                → FlirCapture (thermal photo)
                → PhotoViewer
                → ScopePickerFragment
```

**Admin/Debug:**
- `syncStatusFragment` - Sync queue inspector
- `conflictListFragment` - Conflict resolution UI
- `imageProcessorAssembliesFragment` - Assembly status
- `imageProcessorConfigFragment` - Upload settings

### UI Patterns

**Fragments:** All screens are Fragments; no BaseFragment. Each extends `Fragment` directly with ViewBinding.

**ViewModels:** 31 ViewModels, one per screen. Created via `ViewModelProvider` with custom factories that accept Application-level services. ViewModels observe Room Flows and expose `StateFlow`/`LiveData` to fragments.

**Data flow:**
```
Room DB → Flow<List<Entity>> → ViewModel (map/filter) → StateFlow → Fragment (collect) → RecyclerView
```

**Key ViewModels:**

| ViewModel | Screen | Key responsibilities |
|-----------|--------|---------------------|
| `ProjectsViewModel` | Project list | Filtered project list, search, sync trigger |
| `ProjectDetailViewModel` | Room grid | Room list, project sync, room CRUD |
| `RoomDetailViewModel` | Room tabs | Photos, notes, damages, work scope for a room |
| `BatchCaptureViewModel` | Photo capture | Camera control, photo saving, assembly creation |
| `RocketDryViewModel` | Equipment tabs | Equipment, atmospheric logs, moisture logs |
| `SyncStatusViewModel` | Debug screen | Sync queue state, pending operations |
| `ConflictListViewModel` | Conflict UI | Conflict list, resolution actions |

---

## 3. Data Layer

### Room Database

**Class:** `OfflineDatabase` (version 25)
**Database name:** `rocketplan_offline.db`
**DAOs:** `OfflineDao` (main), `ImageProcessorDao` (assemblies)
**Fallback:** Destructive migration in DEBUG builds only

**37 entities across these categories:**

| Category | Entities |
|----------|----------|
| **Core** | Company, User, Property, Project, Location, Room, RoomType |
| **Catalog** | CatalogPropertyType, CatalogLevel, CatalogRoomType, WorkScopeCatalogItem, DamageType, DamageCause |
| **Content** | Photo, Album, AlbumPhoto, RoomPhotoSnapshot, Note, Damage, WorkScope |
| **RocketDry** | Equipment, Material, MoistureLog, AtmosphericLog |
| **Timecards** | Timecard, TimecardType |
| **Support** | SupportCategory, SupportConversation, SupportMessage, SupportMessageAttachment |
| **Roles** | Role, UserRole |
| **Loss Info** | Claim |
| **Sync infra** | SyncQueue, ConflictResolution |
| **Image processor** | ImageProcessorAssembly, ImageProcessorPhoto |

**Files:**
- `data/local/OfflineDatabase.kt` - Database definition, migrations (10→25)
- `data/local/entity/OfflineEntities.kt` - All entity data classes
- `data/local/dao/OfflineDao.kt` - Single DAO with all queries

### Data Classification: Static vs Dynamic

#### Static Data (reference/catalog, fetched once or infrequently)

Loaded at startup or lazily on first use. Changes rarely (per-company configuration).

| Data | Storage | How Loaded | Refresh |
|------|---------|-----------|---------|
| Room Types & Levels (Catalog) | DataStore (`OfflineRoomTypeCatalogStore`) + Room DB (`CatalogRoomType`, `CatalogLevel`, `CatalogPropertyType`) | `MainActivity.prefetchOfflineCatalog()` on first auth | App startup (best-effort) |
| Damage Types | Room DB (`DamageType`) — per-project scoped (composite key `[projectServerId, damageTypeId]`) | Fetched as part of property sync / loss info | On project sync |
| Damage Causes | Room DB (`DamageCause`) — per-project scoped (composite key `[projectServerId, damageCauseId]`) | Fetched on-demand with loss info | On project sync |
| Work Scope Catalog | Room DB (`WorkScopeCatalogItem`) — per-company scoped (composite key `[companyId, itemId]`) | `WorkScopeSyncService.fetchWorkScopeCatalog()` | Per-company fetch |
| Materials | Room DB (`Material`) | Embedded in project data | On project sync |
| Support Categories | Room DB (`SupportCategory`) | `SupportSyncService.syncCategories()` | Lazy, on support screen open |
| Timecard Types | Room DB (`TimecardType`) | `TimecardSyncService.syncTimecardTypes()`, triggered from `TimecardViewModel` | Lazy, on timecard screen open (falls back to hardcoded default ID=1 "Standard" if empty) |
| Roles | Room DB (`Role`, `UserRole`) | Part of user context fetch | On login / user context refresh |
| Feature Flags | **Not persisted** | `getFeatureFlags()` API exists but not called | Not implemented yet |
| Countries | **Not implemented** | No endpoint or entity in Android | N/A (iOS-only currently) |

#### User Session Data (auth context, persisted across launches)

Stored in `SecureStorage` (EncryptedSharedPreferences + encrypted DataStore). Survives app restarts.

| Data | Storage | Access |
|------|---------|--------|
| JWT Auth Token | SecureStorage (EncryptedSharedPreferences) | `AuthRepository.getAuthToken()` |
| User ID | SecureStorage (DataStore) | `AuthRepository.getStoredUserId()` |
| User Email | SecureStorage (DataStore) | `AuthRepository.getSavedEmail()` |
| User Name | SecureStorage (DataStore) | `AuthRepository.getStoredUserName()` |
| Company ID | SecureStorage (DataStore) | `AuthRepository.getStoredCompanyId()` |
| Company Name | SecureStorage (DataStore) | `AuthRepository.getStoredCompanyName()` |
| Remember Me Flag | SecureStorage (DataStore) | `AuthRepository.isRememberMeEnabled()` |
| Encrypted Password | SecureStorage (EncryptedSharedPreferences) | `AuthRepository.getSavedCredentials()` (only if Remember Me) |
| Cached User/Company | Room DB (`Company`, `User`) | Used for offline login fallback |

Refreshed via `SyncJob.EnsureUserContext` → `GET /auth/user` → stores user ID, name, email, company ID/name in SecureStorage.

#### Dynamic Data (per-project, synced regularly)

Stored in Room DB, synced via `SyncQueueManager` and per-entity sync services. Each entity has `isDirty`, `isDeleted`, and `syncStatus` fields for change tracking.

**Per-Project entities:**

| Entity | Parent | Notes |
|--------|--------|-------|
| `Project` | Company | Top-level, has full sync tracking |
| `Property` | Project | Address, coordinates for map pins |
| `Location` | Project | Building/structure within property (has `projectId`, supports hierarchy via `parentLocationId`) |
| `Room` | Location | Individual room |
| `Photo` | Room | Includes local cache path + upload status |
| `AtmosphericLog` | Project/Room | Environmental readings |
| `MoistureLog` | Room | Moisture readings with material reference |
| `Equipment` | Project/Room | Dehumidifiers, fans, etc. |
| `Damage` | Room | Damage assessments |
| `Note` | Project/Room/Photo | Polymorphic parent (has `projectId`, optional `roomId`, optional `photoId`) |
| `WorkScope` | Room | Scope of work items |
| `Timecard` | Project | Clock in/out records |
| `Album` | Project | Photo albums (many-to-many with photos via `AlbumPhoto`) |
| `Claim` | Project | Insurance claims (loss info) |

**Company-level entities (not per-project):**

| Entity | Scope | Notes |
|--------|-------|-------|
| `User` | Company | Employees; also cached in SecureStorage for offline login |
| `Company` | Global | Company info for offline login |
| `SupportConversation` | User | In-app support threads |
| `SupportMessage` | Conversation | Messages within support threads |
| `SupportMessageAttachment` | Message | File attachments on support messages |

#### Sync Infrastructure (internal, not user data)

| Entity | Purpose |
|--------|---------|
| `OfflineSyncQueueEntity` | Pending create/update/delete operations for push to server |
| `OfflineConflictResolutionEntity` | 409 conflict records awaiting user resolution |
| `ImageProcessorAssembly` | Photo upload batch tracking |
| `ImageProcessorPhoto` | Individual photo upload status within an assembly |
| `RoomPhotoSnapshot` | Ordered photo snapshots per room (photoId, URLs, thumbnails, order index) for UI display |

#### Persistence Layers

| Layer | Format | Location | Survives Reinstall |
|-------|--------|----------|:------------------:|
| SecureStorage | EncryptedSharedPreferences + DataStore | App private storage | No |
| Room DB | SQLite | `rocketplan_offline.db` | No |
| DataStore (Catalog) | Proto/Preferences | `OfflineRoomTypeCatalogStore` | No |
| Photo Cache | JPEG files | `PhotoCacheManager` directories | No |

### Entity Lifecycle Fields

Every syncable entity has these tracking fields:

| Field | Type | Purpose |
|-------|------|---------|
| `syncStatus` | `SyncStatus` | `PENDING` (not yet synced) or `SYNCED` |
| `isDirty` | `Int` (0/1) | Local modifications not yet pushed |
| `isDeleted` | `Int` (0/1) | Soft-deleted locally, pending server delete |
| `serverUpdatedAt` | `String?` | Server's `updated_at` timestamp for optimistic locking |

**Lifecycle:**
```
Create locally  → syncStatus=PENDING, isDirty=1
Push to server  → syncStatus=SYNCED, isDirty=0, serverUpdatedAt=<server value>
Edit locally    → isDirty=1
Delete locally  → isDeleted=1, isDirty=1
Push delete     → Row removed from DB
Server update   → Row replaced, isDirty preserved if locally dirty
```

### LocalDataService

Wrapper around `OfflineDao` providing transaction safety and batch operations. Handles:

- Insert/replace/delete for all entity types
- Batch operations with `@Transaction` annotation
- Flow queries for reactive UI updates
- Clearing all data on logout

**File:** `data/local/LocalDataService.kt`

### DTOs & Mappers

Server JSON is deserialized into DTO classes (`*Dto`), then mapped to Room entities via extension functions in `SyncEntityMappers.kt`.

**Mapping responsibilities:**
- Convert server IDs to local IDs
- Preserve local-only fields (`isDirty`, `syncStatus`) during sync
- Handle nullable fields and default values
- ID remapping for offline-created entities

**Key files:**
- `data/api/dto/` - DTO classes with `@SerializedName` annotations
- `data/repository/mapper/SyncEntityMappers.kt` - `*Dto.toEntity()` extension functions

---

## 4. Networking

### Retrofit Setup

**Class:** `RetrofitClient` - Configures OkHttp + Gson for REST API calls.

**Features:**
- Base URL determined by build variant (`AppConfig`)
- Auth interceptor adds `Bearer <token>` header
- 30-second timeout in production, 60-second in dev/staging (configured via `AppConfig.apiTimeout`)
- JSON serialization via Gson with `@SerializedName` mapping

### API Interface

**File:** `data/api/OfflineSyncApi.kt`

**Endpoint categories:**

| Category | Example endpoints |
|----------|------------------|
| Auth | `/api/auth/user`, `/api/auth/user/feature-flags` |
| Projects | `/api/companies/{id}/projects`, `/api/projects/{id}` |
| Properties | `/api/projects/{id}/properties`, `/api/properties/{id}` |
| Locations | `/api/properties/{id}/locations` |
| Rooms | `/api/locations/{id}/rooms`, `/api/rooms/{id}` |
| Photos | `/api/rooms/{id}/photos`, `/api/projects/{id}/floor-photos` |
| Albums | `/api/projects/{id}/albums` |
| Notes | `/api/projects/{id}/notes`, `/api/notes/{id}` |
| Equipment | `/api/projects/{id}/equipment`, `/api/rooms/{id}/equipment` |
| Moisture | `/api/rooms/{id}/damage-materials/logs` |
| Atmospheric | `/api/projects/{id}/atmospheric-logs` |
| Timecards | `/api/projects/{id}/timecards`, `/api/timecards/{id}` |
| Work Scope | `/api/work-scope/{companyId}`, `/api/rooms/{id}/work-scope-items` |
| Loss Info | `/api/projects/{id}/damage-causes`, `/api/projects/{id}/claims` |
| Support | `/api/support/conversations`, `/api/support/conversations/{id}/messages` |
| Sync | `/api/sync/deleted?since=`, `/api/sync/updated?since=` |

### Authentication

**AuthRepository** (`data/repository/AuthRepository.kt`) handles:
- Email/password login and registration
- Google Sign-In via Credential Manager
- OAuth state management
- Token storage via `SecureStorage` (DataStore-based encrypted storage)
- Company context switching
- Offline auth (cached identity check)

**SecureStorage** stores: auth token, user ID, company ID, email, encrypted password, OAuth state.

There is no separate TokenManager class; all token/credential management lives in `SecureStorage`.

---

## 5. Sync Architecture

This is the core of the app. The sync system enables full offline-first operation with eventual consistency.

### High-Level Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    SyncQueueManager                          │
│  (Central orchestrator - processes SyncJob queue)            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. EnsureUserContext          → Fetch user/company data     │
│  2. ProcessPendingOperations   → Push local changes          │
│  3. SyncDeletedRecords         → Fetch server deletions      │
│  4. SyncUpdatedRecords         → Fetch incremental updates   │
│  5. SyncProjects               → Fetch project list          │
│  6. SyncProjectGraph           → Per-project deep sync       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### SyncJob

Sealed class defining sync job types with priority ordering:

| Job | Priority | Description |
|-----|----------|-------------|
| `EnsureUserContext` | 0 | Fetch user profile and company data |
| `ProcessPendingOperations` | 0 | Push all dirty local entities to server |
| `SyncDeletedRecords` | 0 | Poll `/api/sync/deleted` for server-side deletions |
| `SyncUpdatedRecords` | 0 | Poll `/api/sync/updated` for incremental changes |
| `SyncProjects(force)` | 0 or 1 | Fetch project list (force=true for priority 0) |
| `SyncProjectGraph(projectId, mode)` | 3 | Deep sync of a single project |

**ProjectSyncMode:**
- `FULL` - Everything (structure + content + photos)
- `ESSENTIALS_ONLY` - Property, locations, rooms only
- `CONTENT_ONLY` - Photos and metadata
- `PHOTOS_ONLY` - Just room/project photos
- `METADATA_ONLY` - Notes, equipment, logs

**File:** `data/sync/SyncJob.kt`

### SyncQueueManager

Central orchestrator. Runs a processing loop that dequeues and executes `SyncJob` instances.

**Key entry points:**
- `ensureInitialSync()` - First-time sync on login
- `refreshProjects()` - Full refresh (force sync all)
- `refreshProjectsIncremental()` - Incremental (only changed)
- `syncOnForeground()` - Called when app resumes
- `focusProjectSync(projectId)` - Priority sync for current project

**State exposed to UI:**
- `isActive: StateFlow<Boolean>` - Processing queue
- `currentSyncJob: StateFlow<SyncJob?>` - Current job
- `currentSyncProgress: StateFlow<SyncProgress?>` - Detailed progress
- `initialSyncCompleted: StateFlow<Boolean>` - First sync done
- `photoSyncingProjects: StateFlow<Set<Long>>` - Projects syncing photos
- `projectSyncingProjects: StateFlow<Set<Long>>` - Projects syncing structure

**File:** `data/sync/SyncQueueManager.kt`

### ProjectSyncOrchestrator

Orchestrates per-project sync using `DependencySyncQueue` for parallel execution with dependency awareness.

**Dependency graph:**
```
syncEssentials (property + levels + locations + rooms + albums + users)
       │
       ├── syncMetadata (notes, equipment, damages, atmospheric logs)
       ├── syncRoomPhotos (all room photos)
       └── syncProjectPhotos (project-level photos)
```

Essentials must complete before metadata and photos can begin. Metadata and photos run in parallel.

**Features:**
- Cascade cancellation: if essentials fail, metadata and photos are cancelled
- Network retry with increasing delays (2s, 4s, 6s)
- 503 server overload handling with exponential backoff
- 30-second timeout safety net

**Result types:** `Success`, `PartialSuccess`, `Failure`, `Timeout`

**Files:**
- `data/sync/ProjectSyncOrchestrator.kt`
- `data/repository/sync/DependencySyncQueue.kt`

### DependencySyncQueue

Generic dependency-aware parallel execution engine. Items declare dependencies; the queue executes items in waves as dependencies resolve.

```kotlin
val queue = DependencySyncQueue()
val propId = queue.addItem("property") { syncProperty() }
val levelsId = queue.addItem("levels", dependsOn = listOf(propId)) { syncLevels() }
val roomsId = queue.addItem("rooms", dependsOn = listOf(levelsId)) { syncRooms() }
queue.processAll() // property first, then levels, then rooms
```

Supports cascade cancellation: when an item fails, all transitive dependents are cancelled.

**File:** `data/repository/sync/DependencySyncQueue.kt`

### Sync Services (Pull)

Per-entity sync services that fetch data from the server and upsert into Room:

| Service | File | Responsibilities |
|---------|------|-----------------|
| `ProjectSyncService` | `ProjectSyncService.kt` | Project list with incremental checkpoints |
| `PropertySyncService` | `PropertySyncService.kt` | Property with levels and room types |
| `RoomSyncService` | `RoomSyncService.kt` | Rooms per location |
| `PhotoSyncService` | `PhotoSyncService.kt` | Room/project/floor/location photos |
| `NoteSyncService` | `NoteSyncService.kt` | Notes per project |
| `EquipmentSyncService` | `EquipmentSyncService.kt` | Equipment per project/room |
| `MoistureLogSyncService` | `MoistureLogSyncService.kt` | Moisture logs per room |
| `ProjectMetadataSyncService` | `ProjectMetadataSyncService.kt` | Damages, atmospheric logs |
| `WorkScopeSyncService` | `WorkScopeSyncService.kt` | Work scope catalog + items |
| `DeletedRecordsSyncService` | `DeletedRecordsSyncService.kt` | Server-side deletions via `/api/sync/deleted` |
| `UpdatedRecordsSyncService` | `UpdatedRecordsSyncService.kt` | Incremental updates via `/api/sync/updated` |
| `SupportSyncService` | `SupportSyncService.kt` | Support conversations and messages |
| `TimecardSyncService` | `TimecardSyncService.kt` | Timecards per project |

### Sync Queue (Pending Operations)

Local changes are persisted as operations in `OfflineSyncQueueEntity`:

| Field | Purpose |
|-------|---------|
| `operationId` | UUID identifier |
| `entityType` | `"project"`, `"room"`, `"note"`, etc. |
| `entityId` | Local entity ID |
| `entityUuid` | UUID for cross-reference tracking |
| `operationType` | `CREATE`, `UPDATE`, `DELETE` |
| `payload` | Serialized JSON (e.g., `CreateRoomRequest`) as `ByteArray` |
| `priority` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `retryCount` / `maxRetries` | Retry tracking (default max: 3) |
| `skipCount` / `maxSkips` | Dependency skip tracking (max: 20 before terminal failure) |
| `scheduledAt` | Exponential backoff (next retry time) |
| `status` | `PENDING`, `SYNCED`, `FAILED` |

**SyncQueueEnqueuer** creates operations; **SyncQueueProcessor** dequeues and dispatches to push handlers.

**Files:**
- `data/local/entity/OfflineEntities.kt` (entity definition)
- `data/repository/sync/SyncQueueEnqueuer.kt`
- `data/repository/sync/SyncQueueProcessor.kt`

### Deletion Sync

Uses explicit deletion endpoint (`/api/sync/deleted?since=<timestamp>`) rather than inferring deletions from absence in list responses. This prevents data loss from paginated or incomplete API responses.

**File:** `data/repository/sync/DeletedRecordsSyncService.kt`

---

## 6. Push Handlers

11 push handlers in `data/repository/sync/handlers/`, one per entity type. They process pending operations from the sync queue and push local changes to the server.

### Handler List

| Handler | Entity | Create | Update | Delete |
|---------|--------|--------|--------|--------|
| `ProjectPushHandler` | Project | Yes | Yes | No |
| `PropertyPushHandler` | Property | Yes | Yes | No |
| `LocationPushHandler` | Location | Yes | Yes | Yes |
| `RoomPushHandler` | Room | Yes | Yes | Yes |
| `NotePushHandler` | Note | Yes | Yes | Yes |
| `PhotoPushHandler` | Photo | No | Yes | Yes |
| `EquipmentPushHandler` | Equipment | Yes | Yes | Yes |
| `MoistureLogPushHandler` | MoistureLog | Yes | Yes | Yes |
| `AtmosphericLogPushHandler` | AtmosphericLog | Yes | Yes | Yes |
| `TimecardPushHandler` | Timecard | Yes | Yes | Yes |
| `SupportPushHandler` | SupportMessage | Yes | No | No |

### Common Push Pattern

```
1. Read local entity from Room DB
2. Build API request from entity fields
3. Call server API (POST for create, PUT/PATCH for update, DELETE for delete)
4. On success:
   a. Persist server response to Room (gets server ID, timestamps)
   b. Clear isDirty flag
   c. Update serverUpdatedAt with server's value
   d. Clear deletion tombstone cache (if applicable)
5. Return OperationOutcome
```

### OperationOutcome

| Outcome | Meaning | Queue action |
|---------|---------|-------------|
| `SUCCESS` | Operation completed | Remove from queue |
| `SKIP` | Dependency not ready | Increment skipCount, retry later |
| `RETRY` | Transient failure | Retry immediately |
| `DROP` | Permanently invalid (e.g., 422) | Remove from queue |
| `CONFLICT_PENDING` | 409 conflict needs resolution | Keep with CONFLICT status |

### ID Remapping

When an offline-created entity (local negative ID) is pushed to the server, the server assigns a real positive ID. `IdRemapService` updates all pending queue operations that reference the old local ID.

**Remap hierarchy:**
```
Project → Property, Location, Room, Note, Equipment, Logs
Property → Location
Location → Room
Room → Note, Equipment, MoistureLog, AtmosphericLog
```

**Process:**
1. Push handler creates entity on server, receives server ID
2. Calls `IdRemapService.remap*Id(localId, serverId)`
3. Service finds all pending operations referencing old ID
4. Deserializes each operation's payload JSON
5. Updates the ID reference
6. Re-serializes and updates the operation in the queue

**File:** `data/repository/sync/IdRemapService.kt`

### Conflict Resolution (409 Handling)

When the server returns HTTP 409 (conflict), the entity's `serverUpdatedAt` doesn't match the server's current version.

**Resolution flow:**
1. Push handler receives 409
2. Extracts `updated_at` from 409 response body via `error.extractUpdatedAt(gson)` (in `SyncHandlerUtils.kt`)
3. Retries the push with the fresh timestamp
4. If retry still gets 409, creates `OfflineConflictResolutionEntity` for user resolution
5. Returns `OperationOutcome.CONFLICT_PENDING`

**File:** `data/repository/sync/handlers/SyncHandlerUtils.kt`

### Error Handling by HTTP Status

| Status | Handling |
|--------|----------|
| 200-299 | Success - persist response |
| 409 | Conflict - fetch fresh timestamp, retry |
| 422 | Validation error - drop operation permanently |
| 5xx | Server error - exponential backoff retry |
| Network error | Retry on next sync cycle |

---

## 7. Image Processing & Photo System

### Overview

Photos go through a server-side image processing pipeline. The app manages the upload queue and listens for completion via Pusher.

### Upload Flow

```
Camera Capture
    │
    ▼
Local DB (PENDING)  ←── UI shows spinner
    │
    ▼
ImageProcessorQueueManager picks up assembly
    │
    ▼
Create assembly on backend (POST /api/image-processor/assemblies)
    │
    ▼
Upload photos via HTTP POST (direct upload with Content-Type header)
    │
    ▼
Trigger processing (PATCH /api/image-processor/assemblies/{id}/trigger)
    │
    ▼
Server processes images
    │
    ▼
Pusher event: ImageProcessorUpdated → status = "completed"
    │
    ▼
Download processed outputs, update local DB
    │
    ▼
COMPLETED ←── UI refreshes photos
```

### ImageProcessorQueueManager

Sequential processing - one assembly at a time (matches iOS behavior).

**Key features:**
- Cold-start recovery: resets interrupted uploads on app launch
- Reconciliation: checks backend status for in-progress assemblies
- Exponential backoff retry: 10s initial, 30min max, 13 max attempts
- WorkManager for background retry (every 15 min)

**Callbacks to sync system:**
- `onAssemblyUploadCompleted(projectId, roomId)` - triggers photo sync
- `onAtmosphericLogPhotoCompleted(entityUuid, projectId)` - re-syncs atmospheric log

**Files:**
- `data/queue/ImageProcessorQueueManager.kt`
- `data/local/entity/ImageProcessorAssemblyEntity.kt`
- `data/local/entity/ImageProcessorPhotoEntity.kt`
- `data/local/dao/ImageProcessorDao.kt`

### Assembly States

| Status | Meaning |
|--------|---------|
| `QUEUED` | Queued for processing |
| `PENDING` | Awaiting upload |
| `CREATING` | Creating assembly on backend |
| `CREATED` | Assembly created, ready for photo upload |
| `UPLOADING` | Photo upload in progress |
| `PROCESSING` | Server processing images |
| `COMPLETED` | Processing done, outputs available |
| `FAILED` | Processing or upload failed |
| `CANCELLED` | Upload cancelled |
| `RETRYING` | Retrying after failure |
| `WAITING_FOR_CONNECTIVITY` | Waiting for network |
| `WAITING_FOR_ROOM` | Waiting for room to get serverId |
| `WAITING_FOR_ENTITY` | Waiting for parent entity to get serverId |

### Photo States (within an assembly)

| Status | Meaning |
|--------|---------|
| `PENDING` | Awaiting upload |
| `UPLOADING` | Upload in progress |
| `PROCESSING` | Server processing |
| `COMPLETED` | Upload/processing done |
| `FAILED` | Upload/processing failed |
| `CANCELLED` | Upload cancelled |

### Assembly / Photo Relationship

An **assembly** is a batch of photos captured together (e.g., one room capture session). Each assembly contains 1+ photos. The assembly is the unit of upload and processing.

---

## 8. Realtime (Pusher)

### PusherService

Manages WebSocket connection lifecycle. Connects on login, disconnects on logout. Uses environment-specific API keys (dev/staging/prod).

**Configuration:** `realtime/PusherConfig.kt`
- Cluster: `us2`
- Keys per environment (dev, staging, prod)

**File:** `realtime/PusherService.kt`

### Channels and Events

**Image Processor:**
- Channel: `imageprocessornotification.AssemblyId.<id>`
- Events: `ImageProcessorPhotoUpdated`, `ImageProcessorUpdated`

**Photo Upload:**
- Channel: `PhotoUploadingCompletedAnnouncement.User.<userId>`
- Event: `PhotoUploadingCompletedAnnouncement`

**Photo Assembly Results:**
- Channel: `BroadcastPhotoAssemblyResultEvent.AssemblyId.<id>`
- Event: `PhotoAssemblyUpdated`

**Projects:**
- Channel: `BroadcastProjectCreatedEvent.User.<userId>`
- Channel: `BroadcastProjectCompletedEvent.User.<userId>`
- Channel: `BroadcastProjectCompletedEvent.Company.<companyId>`
- Channel: `BroadcastProjectDeletedEvent.User.<userId>`
- Events: `ProjectCreated`, `ProjectCompleted`, `ProjectDeleted`

**User Roles:**
- Channel: `BroadcastUserRoleChangedEvent.User.<userId>`
- Event: `UserRoleChanged`

**Notes:**
- Channel: `BroadcastNoteCreatedEvent.Project.<projectId>`
- Channel: `BroadcastNoteUpdatedEvent.Note.<noteId>`
- Channel: `BroadcastNoteDeletedEvent.Note.<noteId>`
- Channel: `BroadcastNoteFlaggedEvent.Note.<noteId>`
- Channel: `BroadcastNoteBookmarkedEvent.Note.<noteId>`
- Events: `NoteCreated`, `NoteUpdated`, `NoteDeleted`, `NoteFlagged`, `NoteBookmarked`

### Realtime Managers

| Manager | File | Purpose |
|---------|------|---------|
| `ImageProcessorRealtimeManager` | `realtime/ImageProcessorRealtimeManager.kt` | Assembly/photo status updates |
| `PhotoSyncRealtimeManager` | `realtime/PhotoSyncRealtimeManager.kt` | Photo upload completion |
| `ProjectRealtimeManager` | `realtime/ProjectRealtimeManager.kt` | Project create/complete/delete |
| `NotesRealtimeManager` | `realtime/NotesRealtimeManager.kt` | Note CRUD events |

Each manager subscribes to relevant channels and triggers local data updates (sync or Room inserts) when events arrive.

---

## 9. FLIR Integration

### Product Flavor Architecture

FLIR support uses product flavors with source set switching:

```
app/src/flir/java/.../thermal/       ← Real FLIR SDK implementation
    FlirSdkManager.kt                   Camera discovery, connection
    FlirCameraController.kt             Image capture, streaming
    FlirTypes.kt                         Thermal data types

app/src/standard/java/.../thermal/   ← Stub implementation (no-ops)
    FlirSdkManager.kt                   Returns empty results
    FlirCameraController.kt             No-op methods
    FlirTypes.kt                         Empty types

app/src/main/java/.../thermal/       ← Shared interfaces (if any)
```

### Key Classes

- **`FlirSdkManager`** (flir flavor): Manages FLIR SDK lifecycle - camera discovery, connection, disconnection. Wraps the FLIR Atlas SDK.
- **`FlirCameraController`** (flir flavor): Controls thermal image capture and live preview streaming.
- **`FlirCaptureFragment`**: UI for thermal photo capture. Accesses thermal classes through the flavor-specific implementations.
- **`FlirTestFragment`** / **`FlirIrPreviewFragment`**: Debug screens for testing FLIR connectivity.

### Feature Gating

FLIR features are conditionally shown based on `BuildConfig.FLAVOR_device`:
- Map tab hidden on FLIR builds
- FLIR capture option shown only on FLIR builds
- FLIR test screens accessible only on FLIR builds

---

## 10. Support Chat

### Architecture

Offline-first support ticket system with server sync.

**Components:**

| Component | File | Purpose |
|-----------|------|---------|
| `SupportSyncService` | `data/repository/sync/SupportSyncService.kt` | Sync categories, conversations, messages |
| `SupportPushHandler` | `data/repository/sync/handlers/SupportPushHandler.kt` | Push new messages to server |
| `SupportViewModel` | `ui/support/SupportViewModel.kt` | Conversation list |
| `SupportChatViewModel` | `ui/support/SupportChatViewModel.kt` | Message thread |
| `NewSupportConversationViewModel` | `ui/support/NewSupportConversationViewModel.kt` | Create new ticket |

**Flow:**
1. User creates conversation (stored locally, queued for sync)
2. Messages sent offline (queued as pending operations)
3. `SupportPushHandler` pushes messages when online
4. `SupportSyncService` pulls new messages from server

**Screens:**
- `supportFragment` - Conversation list with categories
- `supportChatFragment` - Message thread
- `newSupportConversationFragment` - Create new conversation with category selection

---

## 11. Key Patterns & Conventions

### Soft-Delete Pattern

Entities are never immediately removed from Room. Instead:
```kotlin
entity.isDeleted = 1
entity.isDirty = 1  // Queue for server-side delete
localDataService.update(entity)
// SyncQueueProcessor picks up and calls DELETE API
// On success, row is removed from Room
```

Guard: `isDirty = 0` check before accepting server updates to avoid overwriting pending local changes.

### Timestamp Handling

`DateUtils` (`util/DateUtils.kt`) is the single source for all date parsing/formatting. Server uses ISO 8601 format (`yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`).

### Coroutine Patterns

- **ViewModels:** `viewModelScope` for UI-triggered operations
- **Data layer:** `withContext(Dispatchers.IO)` for database/network calls
- **Sync system:** Dedicated coroutine scope in `SyncQueueManager`
- **Flow collection:** `repeatOnLifecycle(STARTED)` in Fragments

### Error Propagation

Mixed approach across the codebase:
- Some paths use `Result<T>` return types
- Some use `runCatching { }.getOrNull()` (swallows errors)
- Some propagate exceptions directly
- Push handlers return `OperationOutcome` enum

### Logging

- **Remote logging:** `RemoteLogger` sends structured logs to Sentry
- **Local logging:** Android `Log.*` with component-specific tags
- **Sync logging:** Detailed logs with emoji prefixes for easy filtering

### Network Monitoring

`SyncNetworkMonitor` observes connectivity changes and triggers sync when network becomes available. Used by `SyncQueueManager` to pause/resume processing.

### WorkManager

Single worker: `ImageProcessorRetryWorker` runs every 15 minutes to retry failed assemblies. Registered in `RocketPlanApplication` during initialization.

### Key File Quick Reference

| Area | Primary files |
|------|--------------|
| Entry point | `RocketPlanApplication.kt`, `MainActivity.kt` |
| Navigation | `res/navigation/mobile_navigation.xml` |
| Database | `data/local/OfflineDatabase.kt`, `data/local/dao/OfflineDao.kt` |
| Entities | `data/local/entity/OfflineEntities.kt` |
| API | `data/api/OfflineSyncApi.kt` |
| DTOs | `data/api/dto/*.kt` |
| Mappers | `data/repository/mapper/SyncEntityMappers.kt` |
| Sync orchestration | `data/sync/SyncQueueManager.kt`, `data/sync/SyncJob.kt` |
| Project sync | `data/sync/ProjectSyncOrchestrator.kt` |
| Sync services | `data/repository/sync/*.kt` |
| Push handlers | `data/repository/sync/handlers/*.kt` |
| Realtime | `realtime/PusherService.kt`, `realtime/PusherConfig.kt` |
| Image processor | `data/queue/ImageProcessorQueueManager.kt` |
| Auth | `data/repository/AuthRepository.kt`, `data/local/SecureStorage.kt` |
| FLIR | `app/src/flir/java/.../thermal/`, `ui/projects/flir/FlirCaptureFragment.kt` |
| Support | `data/repository/sync/SupportSyncService.kt` |
