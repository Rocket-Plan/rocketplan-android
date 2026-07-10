---
bug_ids: []
title: "RP-FR-018 H1-H4 + M2: Equipment Move/Transfer Five Fixes"
type: functional
classification: new_code_bug
source: review
found_in: "RP-FR-018"
found_at: "2026-07-10 00:00:00 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_fr_018_equipment_move_and_tracking_2026-07-09.md
related_review: null
related_test: null
last_updated: 2026-07-10
---

# Plan: RP-FR-018 H1–H4 + M2 — Equipment Move/Transfer Five Fixes

## Summary

Five bugs in the RP-FR-018 Equipment Move & Transfer implementation, found during code review of the first-fix round. None were in the two prior fix rounds. All must land before RP-FR-018 is shippable.

> **Update 2026-07-10:** a second review round (see "Review Round 2" at the bottom) found three more — R1 (`roomId` local/server confusion), R2 (full-transfer ghost source, completing H1-B), R3 (cross-project transfer of an unsynced item). All three are applied in the working tree and compile clean; unit tests still pending.

| ID | Location | Summary |
|----|----------|---------|
| H1 | `EquipmentPushHandler.kt:397` | `reconcileTransferResult` sets `projectId = destPivot.projectId` — but `EquipmentRoomResource` has no `project_id`, so dest placement gets `projectId = 0` and is invisible to project-scoped queries. Full transfer also leaves source row uncleared (ghost) because `updatedQty > 0` guard skips saving quantity = 0. |
| H2 | `EquipmentPushHandler.kt:279` | `resolveToRoomServerId` for move: when destination room exists locally but has no `serverId` (not yet synced), the UUID lookup returns the room but `serverId` is null — falls through to `payload.toRoomId` instead of returning null. Unlike transfer's version (line 292–293) which explicitly returns `null`, move proceeds with the fallback `toRoomId`. |
| H3 | `ProjectMetadataSyncService.kt:188` | Pull reconcile matches incoming pivots to existing rows by `catalogServerId` only. When the same catalog item is placed in multiple rooms, `associateBy` keeps only one (last-wins); the other placements are saved as new rows, duplicating the equipment. |
| H4 | `EquipmentPushHandler.kt:183–192`, `:252–261` | `handleMove` and `handleTransfer` catch exceptions but have no `isValidationError()` branch. A 422 falls to `OperationOutcome.RETRY`, retrying a validation error forever instead of dropping it. Compare `handleUpsert` (`:78–84`) which has the correct `isValidationError() → DROP` branch. |
| M2 | `LossInfoModels.kt:136`, `EquipmentRoomFragment.kt` | `FeatureFlagValues` has no `equipmentMoveTransfer` field; the Move/Transfer UI action and history view are unconditionally live with no dark-launch control. |

---

## H1: `reconcileTransferResult` — projectId=0 + ghost source row

### Root Cause

**Part A — wrong projectId:** `EquipmentDto` (line 657) declares `projectId: Long` from `project_id`. But `EquipmentRoomResource` (backend pivot model) has no `project_id` — the server returns `0`. At `reconcileTransferResult:397`:

```kotlin
projectId = destPivot.projectId,  // ← always 0 when backend omits project_id
```

The dest placement is saved with `projectId = 0`, making it invisible to all project-scoped local queries.

**Part B — ghost source row:** For a full transfer (`requestedQty >= equipment.quantity`), `updatedQty = sourcePivot?.quantity ?: (equipment.quantity - requestedQty) = 0`. The guard at `:382`:

```kotlin
if (updatedQty > 0) {   // ← 0 fails this; source row is never re-saved
    ctx.localDataService.saveEquipment(listOf(equipment.copy(...)))
}
```

The source row keeps its old `syncStatus`/`isDirty` values. Since the entity was marked dirty when the transfer was enqueued, after the push it should be `SYNCED`. But without a save, the source row stays dirty (or whatever state it was in), a ghost in the source room.

### Fix

**Part A:** Replace `destPivot.projectId` with `equipment.projectId` at line 397. The transferred placement belongs to the same project as the source.

**Part B:** For full transfer, explicitly tombstone the source row by saving a copy with `isDeleted = true, isDirty = false, syncStatus = SyncStatus.SYNCED`. This removes it from the source room without relying on `updatedQty > 0`.

```kotlin
// reconcileTransferResult, after computing updatedQty
if (sourcePivot != null && updatedQty > 0) {
    ctx.localDataService.saveEquipment(listOf(equipment.copy(
        serverId = sourcePivot.id ?: equipment.serverId,
        quantity = updatedQty,
        isDirty = false,
        syncStatus = SyncStatus.SYNCED,
        lastSyncedAt = ctx.now()
    )))
} else if (sourcePivot != null) {
    // Full transfer: tombstone the source placement
    ctx.localDataService.saveEquipment(listOf(equipment.copy(
        serverId = sourcePivot.id ?: equipment.serverId,
        isDeleted = true,
        isDirty = false,
        syncStatus = SyncStatus.SYNCED,
        lastSyncedAt = ctx.now()
    )))
}
```

### File

`app/src/main/java/.../data/repository/sync/handlers/EquipmentPushHandler.kt`

---

## H2: `resolveToRoomServerId` (move) — sends local room id when dest not synced

### Root Cause

The move payload's `toRoomUuid` is the destination room's UUID. `resolveToRoomServerId` for move (line 279):

```kotlin
private suspend fun resolveToRoomServerId(payload: PendingEquipmentMovePayload): Long? {
    payload.toRoomUuid?.let { uuid ->
        ctx.localDataService.getRoomByUuid(uuid)?.serverId?.let { return it }
    }
    return payload.toRoomId?.takeIf { it > 0 }   // ← fallback when room not synced
}
```

When the destination room exists locally (`getRoomByUuid` returns a room) but has no `serverId` (never synced), `?.serverId?.let { return it }` doesn't return. Execution falls through to the fallback, which uses `payload.toRoomId` directly — whatever value was stored at enqueue time (a local room id passed from the UI at `EquipmentRoomFragment.kt:299`).

Compare the transfer version (line 286) which explicitly handles this case:

```kotlin
if (room != null) {
    return null   // ← dest exists but not synced: SKIP, don't fall through
}
```

### Fix

Mirror the transfer pattern — return `null` when the room exists but has no positive serverId:

```kotlin
private suspend fun resolveToRoomServerId(payload: PendingEquipmentMovePayload): Long? {
    payload.toRoomUuid?.let { uuid ->
        val room = ctx.localDataService.getRoomByUuid(uuid)
        if (room != null && room.serverId != null && room.serverId > 0) {
            return room.serverId
        }
        if (room != null) {
            return null  // dest exists but not synced yet
        }
    }
    return payload.toRoomId?.takeIf { it > 0 }
}
```

### File

`app/src/main/java/.../data/repository/sync/handlers/EquipmentPushHandler.kt`

---

## H3: ProjectMetadataSyncService — pull reconcile matches by catalog id only

### Root Cause

At `ProjectMetadataSyncService.kt:188–192`:

```kotlin
val existingByCatalog = if (pulledCatalogIds.isNotEmpty()) {
    localDataService.getEquipmentByCatalogServerIds(pulledCatalogIds)
        .associateBy { it.catalogServerId }   // ← one entry per catalog id
} else emptyMap()
val placements = response.data.map { dto ->
    val existing = dto.equipmentId?.let { existingByCatalog[it] }
    dto.toEntity(existing).copy(...)
}
```

`associateBy` creates a map keyed by `catalogServerId`. If the same catalog item is placed in two different rooms (e.g., two "Dehumidifier" placements in Room A and Room B), the map keeps only the last one. The second placement's `toEntity(existing)` receives `existing = null`, and a brand-new local row is inserted — duplicate equipment, one per room.

The same problem exists in `reconcileMoveResult` at line 306:

```kotlin
val sourcePivot = returnedPivots.find {
    it.uuid == equipment.uuid || it.id == equipment.serverId
}
```

This is fine because it uses `uuid`/`serverId` (pivot identity). The issue is the pull reconcile path.

### Fix

Match incoming pivots by `(catalogServerId, roomId)` — the pair that uniquely identifies a placement. Use a two-pass approach or a composite key:

```kotlin
// After migration 30->31, existing rows have catalogServerId backfilled.
// Match by catalog + current room so multi-placement catalogs don't collide.
val pulledPivots = response.data
val existingByCatalogAndRoom = localDataService
    .getEquipmentByCatalogServerIds(pulledPivots.mapNotNull { it.equipmentId })
    .groupBy { "${it.catalogServerId}_${it.roomId}" }  // composite key

val placements = pulledPivots.map { dto ->
    val existing = dto.equipmentId?.let { catId ->
        existingByCatalogAndRoom["${catId}_${localRoomId}"]?.firstOrNull()
    }
    dto.toEntity(existing).copy(
        projectId = projectId,
        roomId = localRoomId
    )
}
```

If `existingByCatalogAndRoom` has no entry for the `(catalog, room)` pair, `existing` is null and a new placement is correctly created (first placement in that room for that catalog).

### File

`app/src/main/java/.../data/repository/sync/ProjectMetadataSyncService.kt`

Also verify `LocalDataService.getEquipmentByCatalogServerIds` and the DAO query (`OfflineDao.kt:821`) — the query itself is fine (returns all rows matching catalog ids); the grouping happens in the service layer.

---

## H4: handleMove / handleTransfer — 422 falls to RETRY instead of DROP

### Root Cause

Both `handleMove` and `handleTransfer` catch exceptions but have no `isValidationError()` branch:

```kotlin
// handleMove, lines 183–192
} catch (e: Exception) {
    if (e.isConflict()) {
        return handleMoveConflict(e as HttpException, equipment, pivotServerId, moveRequest, operation)
    }
    if (e.isMissingOnServer()) {
        Log.w(SYNC_TAG, "⚠️ handleMove: pivot $pivotServerId missing on server, SKIP")
        return OperationOutcome.SKIP
    }
    Log.w(SYNC_TAG, "handleMove error for pivot $pivotServerId; retrying", e)
    OperationOutcome.RETRY   // ← 422 reaches here → RETRY forever
}
```

Compare `handleUpsert` (`:78–84`) which correctly handles 422:

```kotlin
if (e.isValidationError()) {
    Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid}: server validation error (422)")
    ctx.remoteLogger?.log(LogLevel.WARN, SYNC_TAG, "Equipment dropped - 422 validation error", ...)
    OperationOutcome.DROP
} else {
    Log.w(SYNC_TAG, "EquipmentPushHandler unknown error; retrying", e)
    OperationOutcome.RETRY
}
```

### Fix

Add `isValidationError()` branches to both handlers:

**handleMove** — after `isMissingOnServer()` branch:
```kotlin
if (e.isValidationError()) {
    Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid} move: server validation error (422)")
    ctx.remoteLogger?.log(
        LogLevel.WARN, SYNC_TAG, "Equipment move dropped - 422 validation error",
        mapOf("equipmentUuid" to equipment.uuid, "pivotServerId" to pivotServerId.toString())
    )
    return OperationOutcome.DROP
}
```

**handleTransfer** — after `isMissingOnServer()` branch (same pattern):
```kotlin
if (e.isValidationError()) {
    Log.w(SYNC_TAG, "Dropping equipment ${equipment.uuid} transfer: server validation error (422)")
    ctx.remoteLogger?.log(
        LogLevel.WARN, SYNC_TAG, "Equipment transfer dropped - 422 validation error",
        mapOf("equipmentUuid" to equipment.uuid, "pivotServerId" to pivotServerId.toString())
    )
    return OperationOutcome.DROP
}
```

### Files

`app/src/main/java/.../data/repository/sync/handlers/EquipmentPushHandler.kt`

---

## M2: No `equipmentMoveTransfer` feature-flag gate

### Root Cause

`FeatureFlagValues` (`data/model/LossInfoModels.kt:136`) defines all known feature flags. `equipmentMoveTransfer` does not exist:

```kotlin
data class FeatureFlagValues(
    @SerializedName("project_loss_info")
    val projectLossInfo: Boolean? = null,
    // ... existing flags ...
    // NO equipmentMoveTransfer
)
```

The Move/Transfer UI action and History view are unconditionally live. There is no dark-launch control.

### Fix

**Step 1** — Add the flag to `FeatureFlagValues`:
```kotlin
@SerializedName("equipment_move_transfer")
val equipmentMoveTransfer: Boolean? = null,
```

**Step 2** — Fetch and cache it. The existing `FeatureFlagService` or equivalent that calls `getFeatureFlags()` will automatically surface it. Confirm where feature flags are stored/cached and add `equipmentMoveTransfer` there.

**Step 3** — Gate the UI in `EquipmentRoomFragment` (move action button, transfer action button) and the history view. The exact gating mechanism depends on where other flags gate UI (likely a ViewModel or SharedPreferences-backed flag cache). Typical pattern:

```kotlin
if (featureFlags.equipmentMoveTransfer == true) {
    moveButton.show()
} else {
    moveButton.hide()
}
```

Or if the feature is visible but non-functional without the flag, show a "coming soon" tooltip instead.

**Step 4** — Confirm the flag is retrieved at app startup / post-login, not lazily per screen, so the gate is synchronous.

### Files

`app/src/main/java/.../data/model/LossInfoModels.kt`
`app/src/main/java/.../ui/rocketdry/EquipmentRoomFragment.kt`
`app/src/main/java/.../ui/rocketdry/EquipmentRoomViewModel.kt` (or wherever flags are checked)

---

## Observability

### Current Signals
- Local `Log.w` on 409/422 already fires in handlers.
- Remote logging via `ctx.remoteLogger?.log` is called for DROP outcomes but not for the specific H4 422-in-move/transfer case (needs to be added — covered by the H4 fix).

### Gaps
- H1 Part B (ghost source row) leaves no trace in logs — the source simply stays dirty. No current observability.
- H2: when a move is deferred because dest room not synced, the SKIP is logged at DEBUG level — adequate.
- H3: duplicate placement after pull is not observable; the extra rows appear silently.

### Proposed Instrumentation
- H1: Add a log when source is tombstoned in a full transfer: `Log.d(SYNC_TAG, "✅ reconcileTransfer: full transfer — source tombstoned")`.
- H4: Remote log (WARN) when a move/transfer is dropped for 422 — already part of the fix.
- H3: Add a `Log.w` when `existingByCatalogAndRoom[compositeKey]` is null (first placement in room) vs found (subsequent placement) to surface multi-placement frequency.

---

## Test Plan

- [ ] H1a: Unit — full transfer → source row tombstoned with `isDeleted=true, syncStatus=SYNCED`; partial transfer → source row updated with reduced quantity.
- [ ] H1b: Unit — transferred placement has `projectId = equipment.projectId` (not `destPivot.projectId`).
- [ ] H2: Unit — move to a destination room that exists locally but has `serverId=null` → `resolveToRoomServerId` returns `null` → `handleMove` returns `SKIP`.
- [ ] H3: Unit — same catalog in two rooms; pull reconcile → two separate local rows (not duplicates), each with correct `roomId`.
- [ ] H4: Unit — `handleMove` 422 → returns `DROP` (not `RETRY`); `handleTransfer` 422 → returns `DROP`.
- [ ] M2: UI — with `equipmentMoveTransfer=false`, move/transfer buttons are hidden (or disabled with tooltip).

---

## Dependencies

- RP-FR-018 (this feature) depends on RP-BUG-279 (pivot write-sync) which is a prerequisite.
- H1–H4 are independent of each other and can land in any order.
- M2 must land before ship to provide the dark-launch kill switch.

## Rollback

- H1–H4: revert the specific lines; queued move/transfer ops will be retried or handled by existing error paths.
- M2: set flag to `true` or remove the gate; UI becomes unconditionally visible (same as current state).

---

## Review Round 2 (2026-07-10) — three additional fixes applied to the working tree

A second code review of the working-tree implementation (post H1–H4/M2) surfaced three
issues not covered above. All three are **applied in the working tree** (uncommitted) and
compile clean (`compileDevStandardDebugKotlin`, exit 0). Frontmatter `fixed_in` stays `null`
until committed.

| ID | Location | Summary |
|----|----------|---------|
| R1 | `EquipmentPushHandler.kt` `createAndAttachPivot`, `reconcileMoveResult` | `roomId` (a **local** room PK everywhere — `observeEquipmentForRoom`, the pull path at `ProjectMetadataSyncService`) was being written with the **server** room id on create/move. Newly-added or moved equipment vanished from the room list until the next pull rewrote `roomId` back to the local PK. |
| R2 | `EquipmentPushHandler.kt` `reconcileTransferResult` | Completes H1 Part B. The H1-B fix only tombstoned the source when `sourcePivot != null`, but a **full** transfer returns **no** source pivot (`sourcePivot == null`), so the source row was never cleared — a ghost duplicate remained in the source room. |
| R3 | `SyncQueueProcessor.kt` `enqueueEquipmentTransfer` | For an **unsynced** item (`serverId == null`), the enqueue path relocated the row into the destination room locally and ignored `toProjectId` — silently misfiling a cross-project transfer into the source project. |

### R1 — `roomId` must hold the local room PK, not the server room id

Root cause: `createAndAttachPivot` and the move-reconcile paths stored the **server** room id
(`roomServerId` / `toRoomServerId` / `destPivot.roomId`) into `OfflineEquipmentEntity.roomId`,
which is a **local** room PK in every consumer.

Fix:
- `createAndAttachPivot`: store `equipment.roomId` (already the correct local PK), falling back
  to a new `localRoomIdForServer(serverRoomId)` mapper (`getRoomByServerId(...).roomId`) only if
  the source row somehow has no `roomId`.
- `reconcileMoveResult` (full and partial paths) and `reconcileTransferResult` (dest row): map the
  server room id → local PK via `localRoomIdForServer(...)`, falling back to `equipment.roomId`.

RP-CD rules touched: this is the inverse of **RP-CD-017** (id-domain discipline — unsynced/local
ids must never reach server path construction; here a server id was contaminating a local-PK column).
No dedicated rule exists for "server id in local-PK field"; consider promoting one if it recurs.

### R2 — full transfer must tombstone the source even when the server returns no source pivot

Fix: restructure the source-side branch so the `else` (full transfer / depleted quantity) path
tombstones the source row (`isDeleted = true, isDirty = false, syncStatus = SYNCED`) regardless of
whether `sourcePivot` is present. The prior `else if (sourcePivot != null)` guard dropped the
`sourcePivot == null` case entirely. RP-CD rules touched: **RP-CD-001** (soft-delete via
`isDeleted` + `isDirty`).

### R3 — defer cross-project transfer of an unsynced placement instead of misfiling it

Fix: when `serverId == null`, mark the row dirty (so its pending CREATE syncs) but do **not**
relocate it. Fall through to enqueue the `TRANSFER` op with `pivotServerId = null`; `handleTransfer`
re-reads `equipment.serverId` at process time and `SKIP`s until the CREATE lands, then performs the
real transfer (`resolveToRoomServerId` falls back to the server `toRoomId` for cross-project
destination rooms that don't exist locally). RP-CD rules touched: **RP-CD-017** (unsynced ids never
reach server path construction — defer, don't build a doomed call).

### Test coverage (follow-up)

Not yet added — track alongside the H1–H4 unit tests:
- [ ] R1: full move → saved source row has `roomId` = **local** destination PK; create → saved row keeps the local `roomId`.
- [ ] R2: full transfer with `sourcePivot == null` in the response → source row tombstoned (`isDeleted=true`).
- [ ] R3: `enqueueEquipmentTransfer` with `equipment.serverId == null` → row marked dirty, `TRANSFER` op enqueued with `pivotServerId=null`, row **not** relocated.
