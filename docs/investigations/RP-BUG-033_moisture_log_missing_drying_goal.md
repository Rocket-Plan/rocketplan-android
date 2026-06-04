---
bug_id: RP-BUG-033
aliases: []
title: MoistureLogRequest missing dryingGoal field — user-set drying goals not persisted to server
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
related_plan: docs/plans/plan_rp_bug_033_moisture_drying_goal_2026-06-03.md
related_review: null
related_test: null
last_updated: 2026-06-03
---

# Investigation: MoistureLogRequest does not include dryingGoal

## Symptom

`OfflineMoistureLogEntity` has a `dryingGoal: Double?` field (line 618), but `MoistureLogRequest` does NOT include this field. When moisture logs are pushed to the server, the `dryingGoal` value is never sent. The server returns `dryingGoal` in responses but Android never sends it.

## Discovery

- **Source:** Parity review vs iOS RP-BUG-124
- **Evidence:** Code analysis confirmed
- **Found during:** iOS parity investigation 2026-06-03

## Root Cause (Confirmed)

### MoistureLogRequest Missing dryingGoal

**File:** `app/src/main/java/com/example/rocketplan_android/data/model/offline/OfflineDtos.kt`
**Lines:** 241-249

```kotlin
data class MoistureLogRequest(
    val reading: Double? = null,
    val removed: Boolean? = null,
    val location: String? = null,
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
    // MISSING: dryingGoal!
)
```

### OfflineMoistureLogEntity Has dryingGoal

**File:** `app/src/main/java/com/example/rocketplan_android/data/local/entity/OfflineEntities.kt`
**Lines:** 594-619

```kotlin
data class OfflineMoistureLogEntity(
    ...
    val projectId: Long,
    val roomId: Long,
    val materialId: Long,
    val moistureContent: Double? = null,
    val removed: Boolean = false,
    val location: String? = null,
    val dryingGoal: Double? = null,        // Line 618 - EXISTS in entity
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    ...
)
```

### toRequest() Mapper Doesn't Include dryingGoal

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/mapper/SyncEntityMappers.kt`
**Lines:** 656-665

```kotlin
internal fun OfflineMoistureLogEntity.toRequest(
    updatedAtOverride: String? = null
): MoistureLogRequest =
    MoistureLogRequest(
        reading = moistureContent,
        removed = removed,
        location = location,
        idempotencyKey = uuid,
        updatedAt = updatedAtOverride ?: updatedAt.toApiTimestamp()
        // NO dryingGoal passed!
    )
```

### Server Accepts and Returns dryingGoal

**File:** `app/src/main/java/com/example/rocketplan_android/data/model/offline/OfflineDtos.kt`
**Lines:** 618-619

```kotlin
@SerializedName("drying_goal")
val dryingGoal: Double? = null,  // <-- Server RETURNS this in MoistureLogDto
```

**File:** `app/src/main/java/com/example/rocketplan_android/data/api/OfflineSyncApi.kt`

```kotlin
@POST("/api/rooms/{roomId}/damage-materials/{damageMaterialId}/logs")
suspend fun createMoistureLog(
    @Path("roomId") roomId: Long,
    @Path("damageMaterialId") damageMaterialId: Long,
    @Body body: MoistureLogRequest  // Currently no dryingGoal field
): MoistureLogDto

@PUT("/api/damage-material-room-log/{logId}")
suspend fun updateMoistureLog(
    @Path("logId") logId: Long,
    @Body body: MoistureLogRequest
): MoistureLogDto
```

### How dryingGoal is Currently Used

1. **Set locally** via `RocketDryRoomViewModel.kt:247` when creating moisture logs
2. **Stored** in `OfflineMoistureLogEntity` (line 618)
3. **Parsed** from material description at `MoistureLogPushHandler.kt:291` for `DamageMaterialRequest` (different request - for materials, not logs)
4. **Returned** from server in `MoistureLogDto` (line 619)
5. **Mapped back** to entity at `SyncEntityMappers.kt:652` via `dryingGoal = dryingGoal ?: existing?.dryingGoal`

**The problem:** When pushing to server, `dryingGoal` is never included in `MoistureLogRequest`. It is only preserved when receiving from server.

## Impact

| Aspect | What Happens |
|--------|--------------|
| **User sets drying goal** | Value stored locally in entity |
| **Log synced to server** | `dryingGoal` NOT sent in request |
| **Server receives log** | `dryingGoal` is null/default |
| **Next sync from server** | Server's null/default overwrites local value |
| **User visible** | Drying goals appear to not persist / reset |

## Affected Code

| Location | Lines | Issue |
|----------|-------|-------|
| `OfflineDtos.kt` | 241-249 | `MoistureLogRequest` missing `dryingGoal` field |
| `SyncEntityMappers.kt` | 656-665 | `toRequest()` doesn't include `dryingGoal` |
| `OfflineEntities.kt` | 618 | `dryingGoal` exists in entity |
| `OfflineDtos.kt` | 618-619 | `MoistureLogDto` has `dryingGoal` (server returns it) |

## Proposed Fix Approach

1. **Add `dryingGoal: Double?` to `MoistureLogRequest`** in `OfflineDtos.kt:241-249`
2. **Update `toRequest()` mapper** in `SyncEntityMappers.kt:664` to include `dryingGoal = dryingGoal`

## Observability

### Current Signals
- Local console logs: `moisture_log_created`, `moisture_log_sync`
- Remote logs: None currently

### Gaps
- Server never receives dryingGoal from Android client
- User-set drying goals are silently lost

### Proposed Instrumentation
- Local debug logs: `moisture_log_drying_goal_set`, `drying_goal_missing_from_request`
- Remote logs: Warning category `moisture_log_drying_goal_not_sent`
- Key fields: `log_id`, `drying_goal`, `room_id`, `material_id`

---

## Related

- iOS counterpart: `RP-BUG-124` (dryingGoal not persisted to OfflineDamage)