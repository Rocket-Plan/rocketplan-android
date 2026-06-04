---
bug_id: RP-BUG-030
aliases: []
title: ImageProcessorQueueManager.resolveServerRoomId returns local ID instead of blocking when room not synced
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
related_plan: docs/plans/plan_rp_bug_030_image_processor_room_id_2026-06-03.md
related_review: null
related_test: null
last_updated: 2026-06-03
---

# Investigation: ImageProcessorQueueManager passes local room ID instead of blocking when room not synced

## Symptom

Photo assemblies with unsynced rooms (local ID but no server ID) proceed to `createRoomAssembly()` with the local room ID in the URL path, causing 404s or wrong associations. The code comment says "will upload to project level" but actually calls `createRoomAssembly()` with the invalid local ID.

## Discovery

- **Source:** Parity review vs iOS RP-BUG-065
- **Evidence:** Code analysis confirmed bug exists
- **Found during:** iOS parity investigation 2026-06-03

## Root Cause (Confirmed)

### The Core Bug: `resolveServerRoomId()` returns local ID when serverId is missing

**File:** `app/src/main/java/com/example/rocketplan_android/data/queue/ImageProcessorQueueManager.kt`
**Lines:** 1455-1459

```kotlin
private suspend fun resolveServerRoomId(roomId: Long?): Long? {
    if (roomId == null) return null
    val localRoom = offlineDao.getRoom(roomId)
    return localRoom?.serverId?.takeIf { it > 0 } ?: roomId.takeIf { it > 0 }
}
```

**Problem:** When `localRoom.serverId == null` (offline-created room not yet synced), the fallback `roomId.takeIf { it > 0 }` returns the local ID (e.g., `123`) instead of `null`. This local ID then reaches the API as if it were a valid server ID.

### How Assemblies Get Created with Invalid Room IDs

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/ImageProcessorRepository.kt`
**Lines:** 87-103

```kotlin
val roomServerId = if (roomId != null) {
    val room = offlineDao.getRoom(roomId) ?: return@withContext logFailure(...)
    room.serverId?.takeIf { it > 0 }
} else {
    null
}
// Don't block on room sync - photos can upload to project level and be associated later
val waitingForRoomSync = false
val waitingForSync = waitingForProjectSync
val resolvedRoomId = roomServerId ?: roomId  // LINE 103: Fallback to local roomId!
```

**Line 103** is critical: `resolvedRoomId = roomServerId ?: roomId` - when room hasn't synced, `roomServerId` is null, so `resolvedRoomId` becomes the local `roomId`.

Then at **line 148**, the assembly is stored with this local roomId:
```kotlin
val assemblyEntity = ImageProcessorAssemblyEntity(
    assemblyId = assemblyId,
    roomId = resolvedRoomId,  // Can be a local ID, not a server ID!
    ...
)
```

### The Broken Guard: `registerWaitingAssemblies()` proceeds without room sync

**File:** `ImageProcessorQueueManager.kt`
**Lines:** 415-428

```kotlin
val roomServerId = resolveServerRoomId(assembly.roomId)
if (assembly.roomId != null && roomServerId == null) {
    Log.d(TAG, "📤 Assembly ${assembly.assemblyId} proceeding without room sync (roomId=${assembly.roomId}) - will upload to project level")
    // Proceeds anyway! No blocking action.
}
// Always promotes to QUEUED regardless
updateAssemblyStatus(assembly.assemblyId, AssemblyStatus.QUEUED, null)
```

**Lines 455-457** promote to QUEUED regardless - the comment "will upload to project level" is misleading.

### How the Invalid Room ID Reaches the API

**File:** `ImageProcessorQueueManager.kt`
**Lines:** 583-606

```kotlin
val request = ImageProcessorAssemblyRequest(
    assemblyId = assembly.assemblyId,
    totalFiles = assembly.totalFiles,
    roomId = roomServerId,  // Can be local ID if not synced
    projectId = projectServerId,
    ...
)

val response = runCatching {
    if (roomServerId != null) {
        api.createRoomAssembly(roomServerId, request)  // Sends local ID to API!
    } else {
        api.createEntityAssembly(request)
    }
```

**Line 602:** The guard `if (roomServerId != null)` is true when `roomServerId` contains a local ID (e.g., `123`), so it takes the wrong branch and calls `createRoomAssembly(123, request)` with an invalid local ID.

## Affected Code

| Location | Lines | Issue |
|----------|-------|-------|
| `ImageProcessorQueueManager.kt` | 1455-1459 | `resolveServerRoomId()` returns local ID when serverId missing |
| `ImageProcessorQueueManager.kt` | 415-428 | `registerWaitingAssemblies()` proceeds without room sync |
| `ImageProcessorQueueManager.kt` | 602-605 | Calls `createRoomAssembly()` with invalid local ID |
| `ImageProcessorRepository.kt` | 103 | `resolvedRoomId = roomServerId ?: roomId` - fallback to local ID |
| `ImageProcessorRepository.kt` | 148 | Assembly stored with unsynced roomId |

## Impact

- **User visible:** Photos uploaded to wrong room or failing 404s
- **Data integrity:** Photos not associated with correct room after sync
- **Workaround:** Room sync completes before photo upload, or user retakes photos after room syncs

## Proposed Fix Approach

Option 1: **Block until room syncs** - revert to old behavior where photo uploads wait for room sync
Option 2: **Route to entity-level upload** when `roomServerId == null` - use `createEntityAssembly()` instead of `createRoomAssembly()`

The current code at line 602-605 already has the `if/else` for this, but the condition is wrong - it checks `roomServerId != null` which is true when it contains an invalid local ID.

## Observability

### Current Signals
- Local console logs: `room_id_zero_assembly_created`, `assembly_proceeding_without_room_sync`
- Remote logs: None currently
- Sentry: None yet observed

### Gaps
- No guard preventing assembly creation with unsynced room
- No clear error when local ID reaches server

### Proposed Instrumentation
- Local debug logs: `assembly_proceeding_without_valid_room`, `room_server_id_local_fallback`
- Remote logs: Error category `photo_assembly_invalid_room_id`
- Key fields: `room_id`, `assembly_id`, `resolved_room_id`, `room_server_id`

---

## Related

- iOS counterpart: `RP-BUG-065` (photo assembly stuck with room_id=0)
- Android partial: `RP-BUG-013` (photo upload spinner issue)