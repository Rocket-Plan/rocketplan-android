---
bug_id: RP-BUG-034
aliases: []
title: PropertyPushHandler sends propertyTypeId=0 to server when offline-created property has no type set — server returns 422 and operation is silently dropped
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
related_plan: docs/plans/plan_rp_bug_034_property_type_id_2026-06-03.md
related_review: null
related_test: null
last_updated: 2026-06-03
---

# Investigation: PropertyPushHandler sends propertyTypeId=0 for offline-created properties

## Symptom

When an offline-created property has no `propertyTypeId` explicitly set (defaults to 0), `PropertyPushHandler.handleCreate()` sends `propertyTypeId=0` to the server. The server likely rejects this with a 422 validation error, and the sync operation is **silently dropped** with no user notification.

## Discovery

- **Source:** Parity review vs iOS RP-BUG-166
- **Evidence:** Code analysis confirmed bug
- **Found during:** iOS parity investigation 2026-06-03

## Root Cause (Confirmed)

### PendingPropertyCreationPayload Defaults to 0

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/mapper/SyncPayloads.kt`
**Lines:** 20-27

```kotlin
internal data class PendingPropertyCreationPayload(
    val localPropertyId: Long,
    val propertyUuid: String,
    val projectId: Long,
    val propertyTypeId: Int,        // NOT NULLABLE, defaults to 0
    val propertyTypeValue: String?,
    val idempotencyKey: String?
)
```

`propertyTypeId: Int` cannot be null - it defaults to 0 when not explicitly set.

### PropertyPushHandler.handleCreate() Passes propertyTypeId Without Validation

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/sync/handlers/PropertyPushHandler.kt`
**Lines:** 38-43

```kotlin
val request = PropertyMutationRequest(
    uuid = payload.propertyUuid,
    propertyTypeId = payload.propertyTypeId,  // DIRECT PASS-THROUGH, NO VALIDATION
    projectUuid = project.uuid,
    idempotencyKey = payload.idempotencyKey
)
```

### 422 Handling Silently Drops the Operation

**File:** `PropertyPushHandler.kt`
**Lines:** 62-67

```kotlin
if (error.isValidationError()) {
    ctx.remoteLogger?.log(
        LogLevel.WARN, SYNC_TAG, "Property creation dropped - 422 validation error",
        mapOf("projectServerId" to projectServerId.toString())
    )
    return OperationOutcome.DROP  // Silently drops - no user notification
}
```

### OfflinePropertyEntity Has Nullable propertyTypeId

**File:** `app/src/main/java/com/example/rocketplan_android/data/local/entity/OfflineEntities.kt`
**Lines:** 87-129

```kotlin
data class OfflinePropertyEntity(
    @PrimaryKey(autoGenerate = true)
    val propertyId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val address: String,
    // ...
    val propertyTypeId: Long? = null,   // NULLABLE - can be null
    // ...
)
```

The entity allows null, but `PendingPropertyCreationPayload` uses `Int` (not `Int?`) so it defaults to 0.

### Server API Contract

**File:** `app/src/main/java/com/example/rocketplan_android/data/api/OfflineSyncApi.kt`

```kotlin
@POST("/api/projects/{projectId}/properties")
suspend fun createProjectProperty(
    @Path("projectId") projectId: Long,
    @Body body: PropertyMutationRequest
): PropertyResourceResponse
```

**PropertyMutationRequest:**
```kotlin
data class PropertyMutationRequest(
    val uuid: String? = null,
    @SerializedName("property_type_id")
    val propertyTypeId: Int,  // Required, no default
    @SerializedName("project_uuid")
    val projectUuid: String? = null,
    // ...
)
```

## When the Bug Fires

1. User creates a property offline WITHOUT going through `ProjectTypeSelectionViewModel.selectPropertyType()`
2. `PendingPropertyCreationPayload.propertyTypeId` is never set, defaults to `0`
3. When sync runs, `PropertyPushHandler.handleCreate()` sends `propertyTypeId=0` to server
4. Server returns 422 validation error
5. **Operation silently dropped** - user never knows their property wasn't created

## Safe Paths (User-Provided propertyTypeId)

### Path A: ProjectTypeSelectionViewModel (lines 124-132)

```kotlin
val request = PropertyMutationRequest(propertyTypeId = propertyType.propertyTypeId)
// propertyType is PropertyType enum with IDs 1-5:
enum class PropertyType(val propertyTypeId: Int, val apiValue: String) {
    SINGLE_UNIT(1, "single_unit"),
    MULTI_UNIT(2, "multi_unit"),
    SINGLE_LOCATION(3, "single_location"),
    COMMERCIAL(4, "commercial"),
    EXTERIOR(5, "exterior");
}
```

**This path is SAFE** - user must select a type with valid ID 1-5.

### Path B: RoomSyncService Fallback (lines 652-656)

```kotlin
val propertyTypeId = (roomTypeRepository.resolveCatalogPropertyTypeId(project.propertyType)
    ?: RoomTypeRepository.fallbackPropertyTypeId(
        RoomTypeRepository.normalizePropertyType(project.propertyType) ?: "single_unit"
    )
    ?: 1L).toInt()  // Falls back to 1 (SINGLE_UNIT)
```

**This path is SAFE** - falls back to 1 if catalog unavailable.

## Affected Code

| Location | Lines | Issue |
|----------|-------|-------|
| `SyncPayloads.kt` | 24 | `propertyTypeId: Int` (non-nullable) defaults to 0 |
| `PropertyPushHandler.kt` | 40 | Passes `propertyTypeId` directly without validation |
| `PropertyPushHandler.kt` | 62-67 | 422 silently drops operation - no user notification |
| `OfflineSyncApi.kt` | 196-200 | Server accepts `PropertyMutationRequest` with `propertyTypeId: Int` |

## Proposed Fix Approach

1. **Add pre-sync validation** in `PropertyPushHandler.handleCreate()` before line 38:
   ```kotlin
   if (payload.propertyTypeId == 0) {
       Log.w(SYNC_TAG, "⚠️ propertyTypeId is 0, skipping sync")
       return OperationOutcome.DROP
   }
   ```
2. **Or:** Make `PendingPropertyCreationPayload.propertyTypeId` nullable (`Int?`)
3. **Or:** Surface the 422 error to user instead of silently dropping

## Observability

### Current Signals
- Local console logs: `property_create_enqueued`, `Property creation dropped - 422 validation error`
- Remote logs: `property_type_id_invalid` warning
- Sentry: None yet observed

### Gaps
- User never notified when property creation fails silently
- No way to retry or recover the failed property creation

### Proposed Instrumentation
- Local debug logs: `property_type_id_zero_sync_blocked`
- Remote logs: Error category `property_creation_validation_failure`
- Key fields: `property_id`, `property_uuid`, `property_type_id`

---

## Related

- iOS counterpart: `RP-BUG-166` (offline-created property tombstoned when propertyTypeId never set)