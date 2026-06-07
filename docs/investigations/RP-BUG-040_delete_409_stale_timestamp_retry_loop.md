---
bug_id: RP-BUG-040
aliases: []
title: Offline delete of a server-modified entity fails permanently — delete handlers send a stale updated_at, backend returns 409, and they retry with the same stale timestamp without re-fetching a fresh one
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: open
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
violates: RP-CD-005
priority: P2
last_updated: 2026-06-07
---

# RP-BUG-040: Delete-with-stale-timestamp 409 never recovers

> Found by the 2026-06-07 409/conflict sweep. Verified against the mongoose backend
> (`HandlesOptimisticLocking::assertNotStale`). Distinct from RP-CD-005's body-drain concern (that
> half of the sweep was clean) — this is about delete handlers not *recovering* from a 409.

## Symptom

A user deletes an entity offline that was modified server-side since this device last synced it (e.g.
edited on the web or another device). On sync the delete **silently fails** and the entity is not
deleted — it persists on the server (and can resurrect locally on the next pull).

## Root cause (verified end-to-end)

1. **Delete handlers send an optimistic-lock timestamp.** Every `handleDelete` sends
   `DeleteWithTimestampRequest(updatedAt = (serverUpdatedAt ?: updatedAt))` read from the **local** row.
2. **The backend enforces it.** `destroy()` on `NoteController`, `AtmosphericLogController`,
   `RoomController`, `LocationController`, `PropertyController`, `DamageMaterialRoomLogController`
   (moisture), `EquipmentRoomController` calls `assertNotStale(...)`, which
   `throw new HttpException(409, ...)` when the sent `updated_at` ≠ the server's current value
   (`HandlesOptimisticLocking.php`). The 409 body is a plain message string (no JSON `updated_at`).
3. **Handlers don't recover.** On the 409 the delete handlers either `throw` (Note, AtmosphericLog,
   Room, Location, Property → `markSyncOperationFailure` → retry up to `maxRetries`) or return
   `OperationOutcome.RETRY` (Equipment, MoistureLog). Every retry re-reads the **same local row** with
   the **same stale timestamp** → another 409. The delete never succeeds and is abandoned after
   `maxRetries`. None of them re-fetch a fresh `updated_at` (their *update* paths do — via re-fetch or
   `extractUpdatedAt`).

`PhotoPushHandler.handleDelete` is **safe**: `PhotoController.destroy` does not `assertNotStale`, so a
photo delete never 409s.

## Affected handlers

Note, AtmosphericLog, Room, Location, Property (throw → retry), Equipment, MoistureLog (RETRY). Photo: safe.

## Suggested fix

On a 409 during delete, re-fetch the entity's **fresh** `updated_at` from the server and retry the
delete once with it (delete-wins — the user intends to remove it), mirroring how the *update* handlers
recover from 409. If the re-fetch shows the entity already gone (404/410) → treat as deleted
(SUCCESS). If still 409 after the fresh retry → `DROP` (or record a conflict). Factor this into a
shared helper (e.g. in `SyncHandlerUtils`) so all delete handlers behave identically, since the bug is
systemic. Note: `extractUpdatedAt` cannot be used here — the `assertNotStale` 409 body carries a
message string, not JSON — so recovery must re-fetch the entity.

## Affected code

| Concern | File:line |
|---------|-----------|
| Delete sends local (stale) timestamp | each `*PushHandler.handleDelete` (`DeleteWithTimestampRequest`) |
| Throw/RETRY on 409, no re-fetch | NotePushHandler / AtmosphericLogPushHandler / RoomPushHandler / LocationPushHandler / PropertyPushHandler (throw); Equipment/MoistureLog (RETRY) |
| Backend 409 source | `Concerns/HandlesOptimisticLocking::assertVersionMatches` → `HttpException(409)` |
| Retry mechanics | `SyncQueueProcessor.markSyncOperationFailure` (retry until `maxRetries`) |

## Observability

### Current Signals
- The repeated 409s are logged per retry, but nothing flags "delete permanently abandoned after
  maxRetries".

### Success Criteria
- Deleting an entity modified server-side succeeds (after one fresh-timestamp retry); the entity is
  removed on the server and does not resurrect.
