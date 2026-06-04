**Bug ID(s):** RP-BUG-030
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-030_image_processor_room_id_zero.md) · [Plan](./plan_rp_bug_030_image_processor_room_id_2026-06-03.md) · Review: pending

# Fix Plan: [RP-BUG-030] Route photo assemblies to entity-level upload when the room has no server ID

**Bug ID(s):** RP-BUG-030
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-03
**State:** draft

---

## Summary

`resolveServerRoomId()` falls back to the **local** room ID when an offline-created room has no `serverId` yet (`localRoom?.serverId?.takeIf { it > 0 } ?: roomId.takeIf { it > 0 }`). That non-null local ID then satisfies the `if (roomServerId != null)` branch in `createAssembly`, so the queue calls `api.createRoomAssembly(localId, …)` with an ID the server has never seen — producing 404s or photos associated with the wrong room.

The fix is to make "room not yet synced" a distinct, honest state: `resolveServerRoomId()` must return `null` (never a local ID), and the assembly-create path must then route to `createEntityAssembly()` (project-level) rather than `createRoomAssembly()`. The repository's `resolvedRoomId = roomServerId ?: roomId` fallback must stop persisting a local ID as if it were a server ID.

This matches the existing `if (roomServerId != null) createRoomAssembly else createEntityAssembly` structure — the branch is already correct; the inputs feeding it are wrong.

## Affected Code

| File | Change |
|------|--------|
| `data/queue/ImageProcessorQueueManager.kt` (≈1455–1459) | `resolveServerRoomId()` returns `null` when `serverId` is missing — remove the `?: roomId.takeIf { it > 0 }` local-ID fallback. |
| `data/queue/ImageProcessorQueueManager.kt` (≈415–428) | `registerWaitingAssemblies()`: when `roomId != null && roomServerId == null`, keep the assembly **waiting** (do not promote to `QUEUED`) so it can attach to the room once it syncs; only fall through to project-level upload if waiting is not desired. Decide policy in Step 2. |
| `data/queue/ImageProcessorQueueManager.kt` (≈583–606) | `createAssembly` request build: `roomId = roomServerId` (now correctly null) so the `if (roomServerId != null)` branch routes unsynced rooms to `createEntityAssembly()`. |
| `data/repository/ImageProcessorRepository.kt` (≈103, 148) | Stop using `resolvedRoomId = roomServerId ?: roomId`. Persist the **local** roomId in a clearly-local field for later re-association, but never send it as a server ID. |

## Implementation Notes

### Step 1: Make `resolveServerRoomId` honest

```kotlin
private suspend fun resolveServerRoomId(roomId: Long?): Long? {
    if (roomId == null) return null
    val localRoom = offlineDao.getRoom(roomId)
    return localRoom?.serverId?.takeIf { it > 0 }   // null when room not yet synced — no local fallback
}
```

### Step 2: Choose the unsynced-room policy (decision required)

- **Option A — Block/wait (preferred for correctness):** leave the assembly in `WAITING` while `roomId != null && roomServerId == null`. Re-run `registerWaitingAssemblies()` after room sync remaps the room's `serverId`; the assembly then promotes to `QUEUED` and calls `createRoomAssembly(serverId, …)`. Photos land in the correct room with no re-take.
- **Option B — Project-level upload:** promote immediately but take the `createEntityAssembly()` branch (no room path). Photos upload sooner but are attached at project level; re-association to the room must happen later (server- or client-side). Use only if Product accepts project-level placement.

Recommend Option A; it preserves room association and the wait window is short (room sync precedes photo sync in the orchestrator).

### Step 3: Stop persisting a local ID as a server ID

In `ImageProcessorRepository`, keep `roomServerId` (nullable) separate from the local `roomId`. Persist the local `roomId` only for re-association bookkeeping; the assembly's server-facing room field must be `roomServerId` (nullable).

## Observability

- Add a console log when an assembly is held/rerouted for an unsynced room: `assembly_waiting_for_room_serverid` with `assembly_id`, `local_room_id`.
- Replace the misleading "will upload to project level" log (which fires even when it doesn't) with one that states the actual chosen path (`held` vs `entity_level`).
- If reaching the API with an unresolved room is ever observed, emit a remote error `photo_assembly_invalid_room_id` (`room_id`, `assembly_id`, `resolved_room_id`).

## Test Plan

- [ ] Unit: `resolveServerRoomId` returns `null` when local room has `serverId = null`; returns server ID when present.
- [ ] Unit: `createAssembly` takes the `createEntityAssembly` branch (or holds, per chosen option) when `roomServerId == null`; never calls `createRoomAssembly` with a local ID.
- [ ] Manual QA:
  1. Prereq: airplane mode; create a new room offline, then capture photos in it.
  2. Action: restore connectivity, let sync run.
  3. Expected: room syncs first, photos attach to the **correct** room; no 404; no project-level orphan (Option A).

## Rollback Plan

Revert the four edits. No schema or persisted-data changes. Behavior returns to the prior (buggy) local-ID fallback.

## Dependencies

- Requires: Product decision on Step 2 (block vs project-level). No server change needed for Option A.
- Blocking: none. Related to RP-BUG-013 (upload spinner).

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-030] Photos captured in an offline-created room no longer upload with an invalid local room ID; the assembly waits for the room to sync and attaches to the correct room.
```
