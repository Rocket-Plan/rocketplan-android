---
bug_id: RP-BUG-046
aliases: []
title: Offline-created moisture logs are rejected with HTTP 422 and silently dropped; the push handler logs only "HTTP 422" (not the response body), so the failing backend rule cannot be diagnosed
type: functional
classification: pre_existing_latent
source: internal
found_in: "1.0.00"
found_at: "2026-06-07 21:33:57 PDT"
fixed_in: null
released_in: null
state: investigating
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
last_updated: 2026-06-07
---

# RP-BUG-046: moisture logs dropped on 422 with no diagnosable detail

> Found 2026-06-07 in on-device logcat (tablet `30407ef`, `1.29 (32)-dev`) while verifying RP-BUG-043/044.
> Two issues bundled: (1) a real but **not-yet-pinpointed** server rejection of moisture logs, and (2) a
> diagnosability gap that *prevents* pinpointing it. (2) is fixed here; (1) needs the captured body.

## Symptom

Pending (offline-created) moisture logs fail to sync and are **silently dropped** — the reading never
reaches the server. Logcat:
```
⚠️ [syncPendingMoistureLogs] Failed to push moisture log 019ea582-3906-7059-8590-e51adb4140d5
retrofit2.HttpException: HTTP 422
Dropping moisture log 019ea582-3906-…: server validation error (422)
⚠️ [pending:moisture] Dropping sync op=moisture_log--…  type=moisture_log
```
Four pending logs (`019ea581-f96e…`, `019ea582-04e5…`, `019ea582-3906…`, `019ea584-0e7b…`) all `PENDING`,
all dropped on 422.

## Investigation so far (verified, but incomplete)

**Client sends** (`OfflineMoistureLogEntity.toRequest`, `SyncEntityMappers.kt:660`) → `MoistureLogRequest`:
`reading=moistureContent, removed, location, drying_goal, idempotency_key, updated_at`. Create endpoint:
`POST /api/rooms/{roomId}/damage-materials/{damageMaterialId}/logs`.

**Device DB values** (pulled 2026-06-07): all four logs have `moistureContent = 8.0`, `removed = 0`,
`dryingGoal = 8.0`.

**Backend create rules** (`StoreRoomDamageMaterialRoomLogsRequest.php`):
```php
'reading'     => 'numeric|between:0,999|regex:/^\d{0,3}(\.\d{1,2})?$/',
'removed'     => 'required_without:reading|boolean',
'room_id'     => Rule::exists('damage_material_room', 'room_id')->where(…),     // injected from route room
'damage_type' => Rule::exists('damage_types', 'id')->where(…drying-eligible…),  // injected from route material
```

**Ruled out:** `reading = 8.0` passes the regex (`^\d{0,3}(\.\d{1,2})?$`) and `between:0,999`; `removed`
is always sent (non-null) so `required_without:reading` is satisfied. So it is **not** a reading-format
or removed-presence failure.

**Most likely cause (unconfirmed):** one of the two route-derived `exists` checks —
- `damage_type` → *"The damage type of this material is not eligible for drying"* (the material's damage
  type isn't a drying-eligible type), or
- `room_id` → *"The select room damage material does not exist"* (no `damage_material_room` row for this
  room+material — e.g. the damage material was created/linked offline and isn't reconciled server-side).

**Why it's unconfirmed:** the push handler logged only `HTTP 422`, never the response `details`, so the
exact failing field/message was unavailable on-device. (cf. RP-BUG-033 `dryingGoal` history — note the
create endpoint does **not** validate `drying_goal` at all, so the goal is silently ignored on create;
tracked separately, not this 422.)

## Fix — part 1 (implemented 2026-06-07): capture the 422 body

`MoistureLogPushHandler.handleUpsert` now drains and logs the 422 response body (truncated to 500 chars)
both locally and on the (now-working, RP-BUG-045) remote log, so the next occurrence names the failing
rule:
```kotlin
val detail = runCatching { (e as? HttpException)?.response()?.errorBody()?.string() }.getOrNull()?.take(500)
Log.w(SYNC_TAG, "Dropping moisture log ${log.uuid}: server validation error (422); detail=$detail")
ctx.remoteLogger?.log(LogLevel.WARN, SYNC_TAG, "Moisture log dropped - 422 validation error",
    mapOf("logUuid" to log.uuid, "serverId" to …, "detail" to (detail ?: "unavailable")))
```
Terminal DROP path, so draining the error body is safe. The validation `details` are field messages
(e.g. "not eligible for drying"), not raw user content.

## Fix — part 2 (PENDING): the actual rejection

Deliberately **not** fixed blind. Once the captured `detail` identifies the field:
- If `damage_type` not drying-eligible → either the client shouldn't allow a moisture reading on a
  non-drying material, or the material/damage-type mapping is wrong client-side.
- If `room_id`/`damage_material_room` missing → the offline-create ordering (material/room link must sync
  before its logs) is the real bug — a push-time SKIP-until-parent-ready guard, like other child handlers.
- Also reconsider whether a hard `DROP` is right at all: a 422 that's actually a *transient ordering*
  problem should SKIP/RETRY, not silently discard the reading (data loss).

## Observability

### Current Signals
- Local console logs: `Dropping moisture log … server validation error (422); detail=…` (now includes body).
- Remote logs: `Moisture log dropped - 422 validation error` with `detail` (works now that RP-BUG-045 is fixed).
- Sentry: none.

### Gaps
- Until a fresh repro is captured with the new logging, the exact failing rule is still unknown.
- `DROP`-on-422 may be discarding recoverable readings (data loss) if the 422 is an ordering artifact.

### Proposed Instrumentation
- (done) log the 422 body. Next: once the field is known, add a targeted SKIP/RETRY vs DROP decision.

### Success Criteria
- A repro now yields the failing field in logs; the root rule is identified and Part 2 fixes it so
  legitimate readings sync (or are correctly deferred) instead of being dropped.
