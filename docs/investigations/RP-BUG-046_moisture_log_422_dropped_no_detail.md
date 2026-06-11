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
related_plan: docs/plans/plan_rp_bug_046_moisture_422_re_enqueue_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_046_2026-06-08.md
related_test: null
last_updated: 2026-06-08
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

**Review follow-up (2026-06-07):** the body-capture was extended to the **409 → retry → 422** path in
`handle409Conflict()` (it previously dropped without the body — a diagnosability gap). Both drop sites now
log `detail=…`. Tests added in `MoistureLogPushHandlerTest`: `handleUpsert logs 422 response body detail
when dropping` and `handle409Conflict logs 422 response body detail when dropping after retry`.

**Review follow-up (2026-06-08):** RP-BUG-048 removed the duplicate-material trigger for fresh failures,
but RP-BUG-046 is still open because every upsert 422 still returns `OperationOutcome.DROP`. The queue
processor deletes the sync op on `DROP`, while the moisture-log row itself stays `PENDING`/dirty and there
is no repair sweep that re-enqueues orphaned pending logs. So the historical "silent strand" data-loss mode
still exists until 422s become recoverable/surfaced and existing queue-less rows are repaired.

## Fix — part 2 (PENDING): the actual rejection

Deliberately **not** fixed blind. Once the captured `detail` identifies the field:
- If `damage_type` not drying-eligible → either the client shouldn't allow a moisture reading on a
  non-drying material, or the material/damage-type mapping is wrong client-side.
- If `room_id`/`damage_material_room` missing → the offline-create ordering (material/room link must sync
  before its logs) is the real bug — a push-time SKIP-until-parent-ready guard, like other child handlers.
- Also reconsider whether a hard `DROP` is right at all: a 422 that's actually a *transient ordering*
  problem should SKIP/RETRY, not silently discard the reading (data loss).

## Update 2026-06-07 — DROP strands the reading (confirmed data loss)

After installing the diagnostic build and re-syncing, the device state shows the **drop is worse than
"the reading didn't sync"**:
- The **sync queue is empty** (`offline_sync_queue` has no rows), yet the 4 room-6804 readings are still
  `offline_moisture_logs.syncStatus = PENDING`.
- Every reading that *pulled down* (RP-BUG-047 fix) is `SYNCED` with a **positive** server `materialId`;
  the 4 stuck ones carry **negative** local `materialId`s (`-1780893355626`, …) though their material
  resolves to server id `512922`.

So the earlier `OperationOutcome.DROP` on 422 **removed the queue op but left the row PENDING** → the
reading is **orphaned and un-retryable**: it will never push again and shows as forever-pending. This is
silent **data loss**, independent of *which* validation rule fired.

**Implication for the fix:** the DROP-vs-keep decision is now the primary bug, not just a detail. A 422
that is actually a *recoverable* condition (missing `damage_material_room` link / ordering) must not be
silently discarded. Options: keep the op retryable (bounded), mark the row with an error/conflict state
the UI can surface, or re-enqueue — but do **not** strand it in PENDING with no op.

**Capturing the exact rule:** the 4 stranded logs can no longer be replayed (no queue op), so the precise
422 `details` must be captured from a **fresh reading authored on the same affected material/room**
(room 6804 / material 512922 "Concrete") with the diagnostic build installed.

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
