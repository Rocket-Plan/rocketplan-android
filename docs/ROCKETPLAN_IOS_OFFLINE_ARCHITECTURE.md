# RocketPlan iOS - Offline Architecture

This document outlines the comprehensive offline architecture for RocketPlan iOS, enabling full app functionality without network connectivity. The design uses Core Data for local persistence with a robust sync strategy for eventual consistency with the backend.

## Design Goals

1. **Offline-First**: App functions fully without network connection
2. **Seamless Sync**: Automatic background sync when network available
3. **Conflict Resolution**: Handle concurrent edits gracefully
4. **Data Integrity**: Ensure consistency between local and remote data
5. **Progressive Enhancement**: Start with critical features, expand gradually
6. **Performance**: Fast local operations with optimized queries

## Core Data Model

### Entity Naming Convention

All offline entities use the `Offline` prefix to distinguish from in-memory models:
- `OfflineProject` vs `Project`
- `OfflineRoom` vs `Room`
- `OfflineAtmosphericLog` vs `AtmosphericLog`

### Common Attributes (All Entities)

Every entity includes these standard fields for sync management:

```swift
// Identity
- [entity]Id: Int64        // Local database ID (primary key)
- serverId: Int64?         // Backend API ID (nil until synced)
- uuid: String             // UUID for offline creation (indexed)

// Sync tracking
- syncStatus: String       // pending/syncing/synced/conflict/failed
- syncVersion: Int32       // Version for optimistic locking
- isDirty: Bool            // Has unsynced local changes
- isDeleted: Bool          // Soft delete flag

// Timestamps
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?      // Last successful sync
```

## Primary Entities

### 1. OfflineProject

Primary container for all project-related data.

```swift
Entity: OfflineProject

Attributes:
- projectId: Int64 (primary key, indexed)
- serverId: Int64? (backend ID when synced)
- uuid: String (UUID, indexed)
- title: String
- projectNumber: String?
- status: String (active/completed/archived)
- propertyType: String? (residential/commercial/multi-unit)
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?
- syncStatus: String
- syncVersion: Int32
- isDirty: Bool
- isDeleted: Bool

Relationships:
- company: OfflineCompany? (to-one)
- property: OfflineProperty? (to-one)
- locations: Set<OfflineLocation> (to-many, cascade)
- rooms: Set<OfflineRoom> (to-many, cascade)
- atmosphericLogs: Set<OfflineAtmosphericLog> (to-many, cascade)
- moistureLogs: Set<OfflineMoistureLog> (to-many, cascade)
- equipment: Set<OfflineEquipment> (to-many, cascade)
- photos: Set<OfflinePhoto> (to-many, cascade)
- notes: Set<OfflineNote> (to-many, cascade)
- crew: Set<OfflineUser> (to-many)

Indexes:
- projectId (unique)
- uuid (unique)
- serverId
- syncStatus
- isDirty
```

### 2. OfflineRoom

Represents rooms or areas within a project.

```swift
Entity: OfflineRoom

Attributes:
- roomId: Int64 (primary key, indexed)
- serverId: Int64?
- uuid: String (indexed)
- projectId: Int64 (indexed, required)
- locationId: Int64? (indexed)
- title: String
- roomType: String?
- level: String?
- squareFootage: Double
- isAccessible: Bool
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?
- syncStatus: String
- syncVersion: Int32
- isDirty: Bool
- isDeleted: Bool

Relationships:
- project: OfflineProject (to-one, required)
- location: OfflineLocation? (to-one)
- photos: Set<OfflinePhoto> (to-many, cascade)
- atmosphericLogs: Set<OfflineAtmosphericLog> (to-many, cascade)
- moistureLogs: Set<OfflineMoistureLog> (to-many, cascade)
- equipment: Set<OfflineEquipment> (to-many, cascade)
- damages: Set<OfflineDamage> (to-many, cascade)
- workScope: Set<OfflineWorkScope> (to-many, cascade)

Indexes:
- roomId (unique)
- uuid (unique)
- projectId
- serverId
- syncStatus
- isDirty
```

### 3. OfflineAtmosphericLog

Environmental monitoring logs (RocketDry feature).

```swift
Entity: OfflineAtmosphericLog

Attributes:
- logId: Int64 (primary key, indexed)
- serverId: Int64?
- uuid: String (indexed)
- projectId: Int64 (indexed, required)
- roomId: Int64? (indexed)
- date: Date (indexed)
- relativeHumidity: Double
- temperature: Double
- dewPoint: Double?
- gpp: Double? (grains per pound)
- pressure: Double?
- windSpeed: Double?
- isExternal: Bool
- isInlet: Bool
- inletId: Int64? (for outlet logs)
- photoUrl: String?
- photoLocalPath: String?
- photoUploadStatus: String (none/pending/uploading/completed/failed)
- photoAssemblyId: String?
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?
- syncStatus: String
- syncVersion: Int32
- isDirty: Bool
- isDeleted: Bool

Relationships:
- project: OfflineProject (to-one, required)
- room: OfflineRoom? (to-one)
- outlet: OfflineAtmosphericLog? (to-one, inlet->outlet)
- inlet: OfflineAtmosphericLog? (to-one, outlet->inlet)
- photo: OfflinePhoto? (to-one)

Indexes:
- logId (unique)
- uuid (unique)
- projectId
- roomId
- date
- serverId
- syncStatus
- isDirty
```

### 4. OfflinePhoto

Photo attachments for projects, rooms, and logs.

```swift
Entity: OfflinePhoto

Attributes:
- photoId: Int64 (primary key, indexed)
- serverId: Int64?
- uuid: String (indexed)
- projectId: Int64 (indexed, required)
- roomId: Int64? (indexed)
- albumId: Int64?
- fileName: String
- localPath: String (path to local file)
- remoteUrl: String?
- thumbnailUrl: String?
- uploadStatus: String (pending/uploading/completed/failed)
- assemblyId: String? (ImageProcessor assembly ID)
- tusUploadId: String?
- fileSize: Int64
- width: Int32?
- height: Int32?
- mimeType: String
- capturedAt: Date (indexed)
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?
- syncStatus: String
- syncVersion: Int32
- isDirty: Bool
- isDeleted: Bool

Relationships:
- project: OfflineProject (to-one, required)
- room: OfflineRoom? (to-one)
- atmosphericLog: OfflineAtmosphericLog? (to-one)
- moistureLog: OfflineMoistureLog? (to-one)

Indexes:
- photoId (unique)
- uuid (unique)
- projectId
- roomId
- assemblyId
- uploadStatus
- syncStatus
- isDirty
```

### 5. OfflineLocation

Hierarchical locations (buildings, floors, units).

```swift
Entity: OfflineLocation

Attributes:
- locationId: Int64 (primary key, indexed)
- serverId: Int64?
- uuid: String (indexed)
- projectId: Int64 (indexed, required)
- title: String
- type: String (unit/floor/building)
- parentLocationId: Int64? (indexed)
- isAccessible: Bool
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?
- syncStatus: String
- syncVersion: Int32
- isDirty: Bool
- isDeleted: Bool

Relationships:
- project: OfflineProject (to-one, required)
- parentLocation: OfflineLocation? (to-one)
- childLocations: Set<OfflineLocation> (to-many, cascade)
- rooms: Set<OfflineRoom> (to-many, cascade)

Indexes:
- locationId (unique)
- uuid (unique)
- projectId
- parentLocationId
- serverId
- syncStatus
```

### 6. OfflineEquipment

Drying equipment tracking (RocketDry feature).

```swift
Entity: OfflineEquipment

Attributes:
- equipmentId: Int64 (primary key, indexed)
- serverId: Int64?
- uuid: String (indexed)
- projectId: Int64 (indexed, required)
- roomId: Int64? (indexed)
- type: String (dehumidifier/air_mover/etc)
- brand: String?
- model: String?
- serialNumber: String?
- quantity: Int32
- status: String (active/removed/damaged)
- startDate: Date?
- endDate: Date?
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?
- syncStatus: String
- syncVersion: Int32
- isDirty: Bool
- isDeleted: Bool

Relationships:
- project: OfflineProject (to-one, required)
- room: OfflineRoom? (to-one)

Indexes:
- equipmentId (unique)
- uuid (unique)
- projectId
- roomId
- serverId
- syncStatus
```

### 7. OfflineMoistureLog

Material moisture content logs (RocketDry feature).

```swift
Entity: OfflineMoistureLog

Attributes:
- logId: Int64 (primary key, indexed)
- serverId: Int64?
- uuid: String (indexed)
- projectId: Int64 (indexed, required)
- roomId: Int64 (indexed, required)
- materialId: Int64 (indexed)
- date: Date (indexed)
- moistureContent: Double
- location: String?
- depth: String?
- photoUrl: String?
- photoLocalPath: String?
- photoUploadStatus: String (none/pending/uploading/completed/failed)
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?
- syncStatus: String
- syncVersion: Int32
- isDirty: Bool
- isDeleted: Bool

Relationships:
- project: OfflineProject (to-one, required)
- room: OfflineRoom (to-one, required)
- material: OfflineMaterial (to-one, required)
- photo: OfflinePhoto? (to-one)

Indexes:
- logId (unique)
- uuid (unique)
- projectId
- roomId
- materialId
- date
- serverId
- syncStatus
```

## Supporting Entities

### 8. OfflineCompany

```swift
Entity: OfflineCompany

Attributes:
- companyId: Int64 (primary key)
- serverId: Int64?
- uuid: String
- name: String
- syncStatus: String
- syncVersion: Int32
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?

Relationships:
- projects: Set<OfflineProject> (to-many)
- users: Set<OfflineUser> (to-many)
```

### 9. OfflineUser

```swift
Entity: OfflineUser

Attributes:
- userId: Int64 (primary key)
- serverId: Int64?
- uuid: String
- email: String (indexed)
- firstName: String?
- lastName: String?
- role: String?
- syncStatus: String
- syncVersion: Int32
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?

Relationships:
- company: OfflineCompany? (to-one)
- projects: Set<OfflineProject> (to-many)
```

### 10. OfflineProperty

```swift
Entity: OfflineProperty

Attributes:
- propertyId: Int64 (primary key)
- serverId: Int64?
- uuid: String
- address: String
- city: String?
- state: String?
- zipCode: String?
- latitude: Double?
- longitude: Double?
- syncStatus: String
- syncVersion: Int32
- createdAt: Date
- updatedAt: Date
- lastSyncedAt: Date?

Relationships:
- project: OfflineProject (to-one)
```

## Sync Management Entities

### 11. OfflineSyncQueue

Tracks all operations that need to be synced to the backend.

```swift
Entity: OfflineSyncQueue

Attributes:
- operationId: String (UUID, primary key)
- entityType: String (Project/Room/AtmosphericLog/etc)
- entityId: Int64
- entityUuid: String
- operationType: String (create/update/delete)
- payload: Data (JSON serialized entity data)
- priority: Int32 (0=critical, 1=high, 2=medium, 3=low)
- retryCount: Int32
- maxRetries: Int32
- createdAt: Date (indexed)
- scheduledAt: Date? (for delayed sync)
- lastAttemptAt: Date?
- completedAt: Date?
- status: String (pending/processing/completed/failed)
- errorMessage: String?

Indexes:
- operationId (unique)
- entityType
- status
- priority
- createdAt
- scheduledAt
```

### 12. OfflineConflictResolution

Stores sync conflicts that require resolution.

```swift
Entity: OfflineConflictResolution

Attributes:
- conflictId: String (UUID, primary key)
- entityType: String
- entityId: Int64
- entityUuid: String
- localVersion: Data (JSON of local state)
- remoteVersion: Data (JSON of remote state)
- conflictType: String (concurrent_edit/deleted_remotely/etc)
- detectedAt: Date (indexed)
- resolvedAt: Date?
- resolution: String? (local_wins/remote_wins/merge/manual)
- resolvedBy: String? (userId)
- notes: String?

Indexes:
- conflictId (unique)
- entityType
- entityUuid
- detectedAt
- resolvedAt
```

## Sync Strategy

### Sync Status Enum

```swift
enum SyncStatus: String {
    case pending = "pending"      // Created offline, not yet synced
    case syncing = "syncing"      // Currently being synced
    case synced = "synced"        // Successfully synced with backend
    case conflict = "conflict"    // Conflict detected, needs resolution
    case failed = "failed"        // Sync failed (retryable)
}
```

### Sync Priority

```swift
enum SyncPriority: Int32 {
    case critical = 0    // User auth, company data
    case high = 1        // Active project edits
    case medium = 2      // New logs, equipment changes
    case low = 3         // Photo uploads, historical data
}
```

### Sync Flow

1. **Local Change Detection**
   - Any create/update/delete sets `isDirty = true`
   - Create operation in `OfflineSyncQueue`
   - Mark entity `syncStatus = .pending`
2. **Sync Queue Processing**
   - Sort by priority, then `createdAt`
   - Process one operation at a time
   - Update entity `syncStatus = .syncing`
3. **API Call**
   - Send entity data to backend
   - Backend returns entity with `serverId`
   - Update local entity with `serverId`
   - Mark `syncStatus = .synced`, `isDirty = false`
   - Update `lastSyncedAt`
4. **Conflict Detection**
   - Check `syncVersion` against backend
   - If mismatch, create `OfflineConflictResolution`
   - Mark entity `syncStatus = .conflict`
   - Present to user for resolution
5. **Error Handling**
   - Increment `retryCount`
   - Apply exponential backoff
   - After max retries, mark `status = .failed`
   - Notify user of persistent failures

### Conflict Resolution Policies

```swift
enum ConflictResolution {
    case lastWriteWins     // Use most recent timestamp
    case serverWins        // Always prefer server version
    case clientWins        // Always prefer local version
    case merge             // Attempt automatic merge
    case manual            // Require user intervention
}
```

**Default policies by entity:**
- **Project**: Manual resolution (critical data)
- **Room**: Last write wins (simple edits)
- **AtmosphericLog**: Server wins (measurements are final)
- **Photo**: Client wins (local is source of truth)
- **Equipment**: Last write wins (status changes)

## Implementation Phases

### Phase 1: Atmospheric Logs (Current)
- Implement `OfflineAtmosphericLog` entity
- Create basic sync queue
- Handle photo upload status tracking
- Support offline log creation with immediate UI update

### Phase 2: Basic Offline Browsing
- Add `OfflineProject`, `OfflineRoom`, `OfflineLocation`
- Download and cache active projects
- Enable read-only offline access
- Implement pull-to-refresh sync

### Phase 3: Full Offline Editing
- Enable create/update/delete operations offline
- Implement sync queue with retry logic
- Add conflict detection
- Support offline photo capture

### Phase 4: Advanced Features
- Add `OfflineEquipment`, `OfflineMoistureLog`
- Implement background sync
- Add manual conflict resolution UI
- Optimize with batch sync

### Phase 5: Polish and Optimization
- Add selective sync (project-based)
- Implement storage limits
- Add sync diagnostics
- Performance optimization

## Data Migration Strategy

### From Current Architecture

Current state uses in-memory models fetched from API:
- `Project`, `Room`, `AtmosphericLog`, etc.
- No local persistence (except upload queue)

Migration path:
1. **Parallel Implementation**: Core Data runs alongside existing API models
2. **Read-Through Cache**: API responses populate Core Data
3. **Gradual Cutover**: Switch features one at a time
4. **Deprecate API Models**: Eventually remove in-memory models

### Sync on First Launch

```swift
func initialSync() async throws {
    // 1. Fetch user's active projects
    let projects = try await projectService.getAllProjects()

    // 2. Save to Core Data
    for project in projects {
        let offlineProject = createOfflineProject(from: project)
        offlineProject.syncStatus = .synced
        offlineProject.isDirty = false
    }

    // 3. Mark as synced
    UserDefaults.standard.set(Date(), forKey: "lastFullSync")
}
```

## File Storage Strategy

### Photo Storage

Photos stored in the app's Documents directory:

```
Documents/
  Photos/
    {projectId}/
      {roomId}/
        {uuid}.jpg
        {uuid}_thumb.jpg
```

### Cleanup Policy

- Delete photos when parent entity deleted (cascade)
- Clean up orphaned files on app launch
- Implement storage limit (for example, 2 GB)
- Prompt user to free space when limit reached

## Network Detection

```swift
class NetworkMonitor {
    func startMonitoring() {
        // Monitor reachability
        // When network becomes available:
        //   - Resume sync queue processing
        //   - Retry failed uploads
        //   - Fetch remote changes
    }
}
```

## Performance Optimizations

1. **Lazy Loading**: Fetch relationships on demand
2. **Batch Fetching**: Load related entities in batches
3. **Indexing**: Index frequently queried fields
4. **Pagination**: Limit fetch results
5. **Background Context**: Heavy operations on background thread
6. **Faulting**: Let Core Data manage memory automatically

## Testing Strategy

### Unit Tests
- Test entity creation, update, and delete
- Test sync queue operations
- Test conflict detection logic

### Integration Tests
- Test full sync flow
- Test offline to online transitions
- Test concurrent modifications

### Manual Testing Scenarios
1. Create log offline → go online → verify sync
2. Edit log offline → remote edit → verify conflict
3. Delete log offline → verify soft delete → verify sync
4. Network interrupted mid-sync → verify retry
5. Photo upload offline → verify queued → go online → verify upload

## Security Considerations

1. **Encryption at Rest**: Encrypt local database (iOS Data Protection)
2. **Token Storage**: Store auth tokens in Keychain
3. **Data Sanitization**: Validate all data before sync
4. **Access Control**: Verify user permissions before operations

## Future Enhancements

- **Selective Sync**: Download only active projects
- **Compression**: Compress photo thumbnails
- **Delta Sync**: Sync only changed fields
- **Batch Operations**: Group related syncs
- **Smart Retry**: Adjust retry based on error type
- **Sync Analytics**: Track sync performance metrics
