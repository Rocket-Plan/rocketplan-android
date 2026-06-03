---
bug_id: RP-FR-002
aliases: []
title: EquipmentPushHandler drains 409 body before conflict recovery (RP-CD-005)
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
related_test: null
parent: RP-HD-001
violates: RP-CD-005
priority: P2
last_updated: 2026-06-02
---

# RP-FR-002: Equipment 409 conflict body drained before `handle409Conflict`

> Filed by the `RP-HD-001` RP-CD audit sweep. Violates **`RP-CD-005` — 409 conflicts: read body before any other consumer**.

## Rule violated

`RP-CD-005`: a handler that calls `handle409Conflict` / `extractUpdatedAt` must not invoke
`errorBody()?.string()` earlier in the same flow. `ResponseBody.string()` drains the buffered
source; the second reader gets an empty string and conflict recovery silently `SKIP`s instead of
retrying with the fresh timestamp.

## Affected code

`app/src/main/java/com/example/rocketplan_android/data/repository/sync/handlers/EquipmentPushHandler.kt`

The `handle409Conflict` retry path **was added** (correct: `handleUpsert` catch at line 65–66 →
`handle409Conflict` at line 99 → `error.extractUpdatedAt(ctx.gson)` at line 112). But it is
defeated upstream:

```kotlin
// pushPendingEquipmentUpsert(...)  — runs INSIDE the try, before the catch
}.onFailure { error ->
    val errorBody = (error as? retrofit2.HttpException)?.response()?.errorBody()?.string() // line 219 — DRAINS body
    Log.w(SYNC_TAG, "... Failed to push equipment ${equipment.uuid}: $errorBody", error)
}.getOrElse { throw it }   // line 221 — rethrows the same exception up to handleUpsert's catch
```

When a 409 occurs, `pushPendingEquipmentUpsert`'s `.onFailure` (line 218–220) reads
`errorBody()?.string()` for the log line, then rethrows. `handleUpsert` catches it and calls
`handle409Conflict`, but `extractUpdatedAt` now reads an **empty** body → `freshUpdatedAt == null`
→ `OperationOutcome.SKIP` (line 113–115). The retry never fires.

## Impact (latent, describable)

An offline equipment edit that conflicts with a concurrently-modified server copy will **silently
`SKIP` every cycle and never sync**. Filed as `RP-FR` because the failure mode is argued from code
shape and not yet observed in Sentry/QA — but it has a concrete user-visible description, so promote
to `RP-BUG` if observed. (This matches the prior project-memory note about equipment 409 retry being
broken.)

## Suggested remediation

Per `RP-CD-005`: do not read the body in the `.onFailure` log above the conflict path. Either drop
the body from the log line, or peek it non-destructively (`response()?.errorBody()?.source()?.peek()`)
so `extractUpdatedAt` still sees the buffered bytes. 409 body handling should live only in
`SyncHandlerUtils`.

## Resolution — fixed 2026-06-02

`EquipmentPushHandler.pushPendingEquipmentUpsert` `.onFailure` block now guards the body read so the
single-use 409 body is never drained before `handle409Conflict`:

```kotlin
}.onFailure { error ->
    // RP-CD-005: a 409 error body is single-use and is consumed downstream by
    // handle409Conflict/extractUpdatedAt. Never drain it here ...
    val errorBody = if (error.isConflict()) null
    else (error as? retrofit2.HttpException)?.response()?.errorBody()?.string()
    Log.w(SYNC_TAG, "... Failed to push equipment ${equipment.uuid}: $errorBody", error)
}.getOrElse { throw it }
```

For a 409 the body is left intact, so `handle409Conflict` → `extractUpdatedAt` reads the real
`updated_at` and the upsert retries instead of silently `SKIP`ping. Diagnostics for non-conflict
failures (422/5xx) are preserved unchanged. No schema or API change.

## Observability

### Current Signals
- Local console logs: line 220 logs the (already-drained) body; line 106/114 log the conflict + extraction failure.
- Remote logs: `remoteLogger` WARN "Equipment update 409 conflict" (line 107–110).
- Sentry: Not captured.

### Gaps
- The "Could not extract updated_at from 409 body" WARN (line 114) currently looks like a server
  problem; it is actually self-inflicted by the upstream drain. After the fix, that WARN firing
  again would indicate a genuine malformed-body case.

### Success Criteria
- An equipment upsert that receives a 409 retries with the server's fresh `updated_at` and reaches
  `SUCCESS` (or records a conflict on a true double-409), never silently `SKIP`ping on the first 409.
