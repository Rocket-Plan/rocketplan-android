---
bug_id: RP-BUG-040
aliases: []
title: Offline delete of a server-modified entity fails permanently — delete handlers send a stale updated_at, backend returns 409, and they retry with the same stale timestamp without re-fetching a fresh one
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: app/src/test/java/com/example/rocketplan_android/data/repository/sync/handlers/ResolveDeleteWithStaleRetryTest.kt
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
3. **Handlers don't recover — in two different (both broken) ways**, depending on the delete API's
   Retrofit return type:
   - **Group A — `throw`/retry-fail** (Note, AtmosphericLog: `deleteX` returns `Response<Unit>`, handler
     checks the code and throws on 409; Room, Location, Property: `deleteX` returns `Unit`, so Retrofit
     itself throws `HttpException` on 409 and the `catch` re-throws it). The 409 →
     `markSyncOperationFailure` → retry up to `maxRetries`, each retry re-reading the **same local row**
     with the **same stale timestamp** → another 409. The delete never succeeds and is abandoned.
   - **Group B — silent false-success** (Equipment, MoistureLog: `deleteEquipment`/`deleteMoistureLog`
     return `Response<Unit>` and the handler **never checks `response.isSuccessful`**; Retrofit doesn't
     throw for `Response<T>`, so the `runCatching`/`isValidationError`/`isMissingOnServer` branches are
     **dead code for HTTP responses**). A 409 (and 422, 5xx) is **silently swallowed as success** — the
     row is marked deleted locally while it still exists on the server → silent divergence /
     resurrection on the next pull. Worse than Group A.

   None re-fetch a fresh `updated_at` (their *update* paths do).

`PhotoPushHandler.handleDelete` is **safe**: `PhotoController.destroy` does not `assertNotStale`, so a
photo delete never 409s.

## Affected handlers

Note, AtmosphericLog, Room, Location, Property (throw → retry), Equipment, MoistureLog (RETRY). Photo: safe.

## Fix (implemented 2026-06-07)

The recovery is simpler than re-fetching: the backend **skips the staleness check when `updated_at` is
null** (`assertNotStale`: `if ($expected === null) return;`). So on a delete-409 we **retry once with
`updated_at = null`** — delete-wins, no per-entity GET needed. Factored into shared helpers in
`SyncHandlerUtils`:
- `resolveDeleteWithStaleRetry` for delete APIs returning `Response<Unit>` (Note, AtmosphericLog,
  Equipment, MoistureLog) — it inspects the response (fixing the Group-B silent-swallow too): success
  / 404 / 410 → finalize; 409 → retry with null; 422 → DROP; else throw.
- `resolveDeleteThrowingWithStaleRetry` for delete APIs returning `Unit` (Room, Location, Property),
  where Retrofit throws `HttpException` — same logic via try/catch.

All seven delete handlers now route through these. Equipment/MoistureLog additionally wrap the call to
map transient/network errors to `RETRY` (preserving their prior contract). The Equipment/Moisture
handler tests were corrected to mock the realistic `Response.error(code)` (Retrofit returns, not
throws, for `Response<Unit>`) — the old `throws` mocks were why the silent-swallow went unnoticed.
Covered by `ResolveDeleteWithStaleRetryTest` (6 cases). Full suite green (433 tests).

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
