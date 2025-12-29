# UUID Implementation Plan

## Overview

Replace local ID → server ID mapping with stable UUIDs across all entities. This eliminates complex client-side ID resolution logic and simplifies offline sync.

## Problem Statement

Currently, when entities are created offline:
1. Client assigns temporary local ID (e.g., `-1703123456789`)
2. Related entities reference this local ID
3. After sync, server returns server ID (e.g., `456`)
4. Client must remap all references from local ID → server ID
5. Pending operations need complex fallback logic to resolve IDs

This results in 340+ lines of fallback code in `handlePendingRoomCreation` alone.

## Solution

- Client generates UUID at entity creation
- UUID is sent to server and stored permanently
- Related entities reference by UUID (never changes)
- Server accepts UUID as foreign key reference and resolves internally

## Affected Entities

| Entity | References | Priority |
|--------|------------|----------|
| Project | Company | High |
| Property | Project | High |
| Location (Level) | Property | High |
| Location (Unit) | Property, Parent Location | High |
| Room | Location, Level | High |
| Photo | Room, Project | High |
| Note | Room, Photo, Project | Medium |
| Equipment | Room | Medium |
| Moisture Log | Room | Medium |
| Atmospheric Log | Room | Medium |
| Work Scope | Room | Medium |
| Album | Project | Medium |

---

## Phase 1: Server Changes (Laravel)

### 1.1 Database Migrations

Add `uuid` column to all entity tables:

```php
// database/migrations/2025_01_XX_add_uuid_to_entities.php

public function up()
{
    $tables = [
        'projects',
        'properties',
        'locations',
        'rooms',
        'photos',
        'notes',
        'equipment_room',
        'moisture_logs',
        'atmospheric_logs',
        'work_scopes',
        'albums',
    ];

    foreach ($tables as $table) {
        Schema::table($table, function (Blueprint $table) {
            $table->uuid('uuid')->nullable()->unique()->after('id');
        });
    }
}
```

### 1.2 Generate UUIDs for Existing Records

```php
// database/migrations/2025_01_XX_populate_existing_uuids.php

public function up()
{
    $models = [
        \App\Models\Project::class,
        \App\Models\Property::class,
        \App\Models\Location::class,
        \App\Models\Room::class,
        \App\Models\Photo::class,
        // ... etc
    ];

    foreach ($models as $model) {
        $model::whereNull('uuid')->chunkById(1000, function ($records) {
            foreach ($records as $record) {
                $record->update(['uuid' => Str::uuid()->toString()]);
            }
        });
    }
}
```

### 1.3 Make UUID Required

After backfill is complete:

```php
// database/migrations/2025_01_XX_make_uuid_required.php

Schema::table('locations', function (Blueprint $table) {
    $table->uuid('uuid')->nullable(false)->change();
});
```

### 1.4 Update Models

```php
// app/Models/Location.php

class Location extends BaseModel
{
    protected $fillable = [
        'uuid',  // Add uuid
        'property_id',
        'name',
        // ...
    ];

    // Auto-generate UUID on creation
    protected static function boot()
    {
        parent::boot();

        static::creating(function ($model) {
            if (empty($model->uuid)) {
                $model->uuid = Str::uuid()->toString();
            }
        });
    }

    // Helper to find by UUID
    public static function findByUuid(string $uuid): ?self
    {
        return static::where('uuid', $uuid)->first();
    }
}
```

### 1.5 Update API Resources

```php
// app/Http/Resources/LocationResource.php

public function toArray($request): array
{
    return [
        'id' => $this->id,
        'uuid' => $this->uuid,  // Add UUID to response
        'name' => $this->name,
        // ...
    ];
}
```

### 1.6 Accept UUID References in Requests

```php
// app/Http/Requests/StoreRoomRequest.php

public function rules(): array
{
    return [
        'name' => 'required|string|max:255',
        'room_type_id' => 'required|exists:room_types,id',
        'uuid' => 'nullable|uuid|unique:rooms,uuid',

        // Accept either ID or UUID for location
        'location_id' => 'required_without:location_uuid|exists:locations,id',
        'location_uuid' => 'required_without:location_id|exists:locations,uuid',

        // Accept either ID or UUID for level
        'level_id' => 'required_without:level_uuid|exists:locations,id',
        'level_uuid' => 'required_without:level_id|exists:locations,uuid',
    ];
}
```

### 1.7 Update Controllers

```php
// app/Http/Controllers/Api/LocationRoomsController.php

public function store(StoreRoomRequest $request, Location $location)
{
    // Resolve location by UUID if provided
    if ($request->has('location_uuid')) {
        $location = Location::where('uuid', $request->location_uuid)->firstOrFail();
    }

    // Resolve level by UUID if provided
    $levelId = $request->level_id;
    if ($request->has('level_uuid')) {
        $levelId = Location::where('uuid', $request->level_uuid)->firstOrFail()->id;
    }

    $room = $location->rooms()->create([
        'uuid' => $request->uuid ?? Str::uuid()->toString(),
        'name' => $request->name,
        'level_id' => $levelId,
        'room_type_id' => $request->room_type_id,
        // ...
    ]);

    return RoomResource::make($room);
}
```

---

## Phase 2: Android Changes

### 2.1 Update API Request Models

```kotlin
// CreateRoomRequest.kt

data class CreateRoomRequest(
    val name: String,
    val uuid: String? = null,           // Client-generated UUID
    val roomTypeId: Long,
    val levelUuid: String,              // Reference by UUID
    val locationUuid: String,           // Reference by UUID
    val isSource: Boolean = false,
    val idempotencyKey: String? = null
)
```

### 2.2 Simplify Sync Payloads

```kotlin
// SyncPayloads.kt

internal data class PendingRoomCreationPayload(
    val localRoomId: Long,
    val roomUuid: String,               // Always have UUID
    val projectUuid: String,            // Reference by UUID
    val levelUuid: String,              // Reference by UUID
    val locationUuid: String,           // Reference by UUID
    val roomName: String,
    val roomTypeId: Long,
    val roomTypeName: String?,
    val isSource: Boolean,
    val isExterior: Boolean,
    val idempotencyKey: String?
)
// Removed: levelServerId, locationServerId, levelLocalId, locationLocalId
```

### 2.3 Simplify handlePendingRoomCreation

```kotlin
// SyncQueueProcessor.kt

private suspend fun handlePendingRoomCreation(
    operation: OfflineSyncQueueEntity
): OperationOutcome {
    val payload = gson.fromJson(
        String(operation.payload, Charsets.UTF_8),
        PendingRoomCreationPayload::class.java
    ) ?: return OperationOutcome.DROP

    // Simple: just check if parent entities have synced
    val location = localDataService.getLocationByUuid(payload.locationUuid)
    val level = localDataService.getLocationByUuid(payload.levelUuid)

    if (location?.serverId == null || level?.serverId == null) {
        Log.d(TAG, "Parent entities not synced yet, will retry")
        return OperationOutcome.SKIP
    }

    if (!isNetworkAvailable()) {
        return OperationOutcome.SKIP
    }

    // Server resolves UUIDs internally
    val request = CreateRoomRequest(
        name = payload.roomName,
        uuid = payload.roomUuid,
        roomTypeId = payload.roomTypeId,
        levelUuid = payload.levelUuid,
        locationUuid = payload.locationUuid,
        isSource = payload.isSource,
        idempotencyKey = payload.idempotencyKey
    )

    val response = api.createRoom(request)

    // Update local entity with server ID
    val entity = response.toEntity(payload.localRoomId, payload.roomUuid)
    localDataService.saveRooms(listOf(entity))

    return OperationOutcome.SUCCESS
}
```

**Result: ~340 lines → ~40 lines**

### 2.4 Update Entity Creation

```kotlin
// RoomSyncService.kt

suspend fun createPendingRoom(
    project: OfflineProjectEntity,
    level: OfflineLocationEntity,
    location: OfflineLocationEntity,
    roomName: String,
    roomTypeId: Long,
    // ...
): OfflineRoomEntity {
    val roomUuid = UUID.randomUUID().toString()

    val pending = OfflineRoomEntity(
        roomId = -System.currentTimeMillis(),
        serverId = null,
        uuid = roomUuid,
        projectId = project.projectId,
        locationId = location.locationId,
        // ...
    )

    localDataService.saveRooms(listOf(pending))

    // Enqueue with UUIDs only - no local IDs needed
    syncQueueEnqueuer.enqueueRoomCreation(
        roomUuid = roomUuid,
        projectUuid = project.uuid,
        levelUuid = level.uuid,
        locationUuid = location.uuid,
        roomName = roomName,
        roomTypeId = roomTypeId,
        // ...
    )

    return pending
}
```

### 2.5 Remove Legacy Code

Delete from `SyncQueueProcessor.kt`:
- All `levelLocalId` / `locationLocalId` handling
- Name-matching with remote API fallbacks
- Disambiguation logic for level == location
- Server ID validation with remote fetch
- Complex `resolveFromRemote` logic

---

## Phase 3: iOS Changes

Apply the same pattern:
1. Store UUID with all entities
2. Reference parent entities by UUID in sync payloads
3. Simplify sync handlers to just check parent.serverId != nil
4. Let server resolve UUID → ID

---

## Phase 4: Migration Strategy

### 4.1 Server Deployment Order

1. Deploy migration: Add nullable `uuid` column
2. Deploy backfill: Generate UUIDs for existing records
3. Deploy API changes: Return `uuid`, accept `uuid` references
4. Deploy migration: Make `uuid` NOT NULL

### 4.2 Client Rollout

1. **Phase A**: Update clients to READ uuid from API responses
2. **Phase B**: Update clients to SEND uuid on create
3. **Phase C**: Update clients to use uuid for references
4. **Phase D**: Remove legacy local ID resolution code

### 4.3 Backward Compatibility

During transition, server accepts both:
```php
// Either works
'location_id' => 'required_without:location_uuid',
'location_uuid' => 'required_without:location_id',
```

Old clients send `location_id`, new clients send `location_uuid`.

---

## Phase 5: Testing

### 5.1 Server Tests

```php
public function test_can_create_room_with_location_uuid()
{
    $location = Location::factory()->create();

    $response = $this->postJson('/api/rooms', [
        'name' => 'Kitchen',
        'location_uuid' => $location->uuid,
        'room_type_id' => 1,
    ]);

    $response->assertCreated();
    $this->assertEquals($location->id, Room::first()->location_id);
}
```

### 5.2 Android Tests

```kotlin
@Test
fun `room creation with pending location uses UUID reference`() {
    // Create location offline
    val location = createPendingLocation(uuid = "loc-123")

    // Create room referencing location by UUID
    val room = createPendingRoom(locationUuid = "loc-123")

    // Sync location first
    syncLocation(location)

    // Room sync should succeed using UUID
    val result = syncRoom(room)
    assertEquals(OperationOutcome.SUCCESS, result)
}
```

---

## Rollback Plan

If issues arise:
1. Server continues accepting `location_id` (backward compatible)
2. Revert client to use legacy ID resolution
3. UUID columns remain but unused

---

## Success Metrics

| Metric | Before | After |
|--------|--------|-------|
| `handlePendingRoomCreation` lines | 340+ | ~40 |
| Fallback resolution phases | 7 | 0 |
| Remote API calls for ID resolution | 2-4 | 0 |
| Sync failure rate (ID resolution) | TBD | ~0% |

---

## Timeline Estimate

| Phase | Tasks |
|-------|-------|
| Phase 1 | Server migrations, model updates, API changes |
| Phase 2 | Android client updates |
| Phase 3 | iOS client updates |
| Phase 4 | Migration, rollout, monitoring |
| Phase 5 | Testing, cleanup legacy code |

---

## Decisions

1. **UUID v7 (time-ordered)** - Better for database indexing due to sequential nature
   - Android: Use `java.util.UUID` with custom v7 generator or `com.github.f4b6a3:uuid-creator` library
   - Laravel: Use `Str::orderedUuid()` (built-in, UUID v7 compatible)
   - iOS: Use custom v7 generator or library

2. **Backward compatibility is permanent** - Server always accepts both ID and UUID references
   - No migration deadline for old clients
   - Clean separation: old clients use IDs, new clients use UUIDs

3. **Photos use room UUID** - Consistency across all entities

4. **Web dashboard** - No changes needed initially (can query by either ID or UUID)

## Open Questions

_(None remaining)_
