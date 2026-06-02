# RP-CD Coding Rules

> **Purpose:** Normative coding rules for RocketPlan Android. Referenced by `BUG_TRACKER.md` — `RP-FR-###` bugs are filed when code violates one of these rules without (yet) a demonstrated user-visible failure.
>
> **Authority:** If this doc conflicts with inline comments, this doc wins. If it conflicts with `ARCHITECTURE.md`, raise it — one of the two is wrong and must be fixed.
>
> **Seeding:** Rules `RP-CD-001` through `RP-CD-013` are extracted from existing conventions in `ARCHITECTURE.md` §11 ("Key Patterns & Conventions") and from review feedback captured in project memory. Add new rules with the next free `RP-CD-###` and never reuse numbers — even for retired rules.

---

## How to use these rules

- **Filing an `RP-FR-###` bug** → cite the rule(s) violated. If no rule covers it, either propose a new rule here or downgrade the bug to `RP-HD-###` (preventive guard).
- **Adding a rule** → must be enforceable in code review, must have a concrete violation pattern, must have a rationale. No aspirational rules ("we should generally...").
- **Retiring a rule** → mark `Status: retired` with the date and reason. Do not delete; the number is permanent so existing `RP-FR-###` references stay readable.

Each rule has the form:

```
### RP-CD-### — <short title>
Status: active | retired (<date>, <reason>)
Rule: <one-sentence statement of what is required or forbidden>
Why: <rationale — what breaks if this rule is violated>
How to apply: <where and when to enforce>
Violations: <link to known RP-FR-### bugs that cite this rule>
```

---

## Catalog

### RP-CD-001 — Soft-delete via `isDeleted` + `isDirty`
Status: active
Rule: Syncable entities are never hard-deleted from Room by UI/repo code. Set `isDeleted = 1`, `isDirty = 1`, and let the push handler issue the server `DELETE`; only the handler removes the row after server confirmation.
Why: Hard-delete loses the change before it can be pushed, breaking offline-first guarantees. Two-phase delete also lets us undo and audit.
How to apply: Any time you would call `dao.delete(entity)` directly, replace with the soft-delete update. Exception: rows that have never been pushed (`syncStatus = PENDING` and `serverId == null`) may be hard-deleted because the server never saw them.

### RP-CD-002 — Server updates must not clobber local dirt
Status: active
Rule: When applying a server payload to Room, skip or merge if the local row has `isDirty = 1`. Never blindly overwrite a dirty row.
Why: A pull that runs while a push is queued will drop the user's unsynced edit.
How to apply: All `*SyncService` upsert paths must check `isDirty` before replacing. Mappers preserve `isDirty` and `syncStatus` through `toEntity()` (see `SyncEntityMappers.kt`).

### RP-CD-003 — All date parsing goes through `DateUtils`
Status: active
Rule: Use `util/DateUtils.kt` for every ISO 8601 parse/format. Do not instantiate `SimpleDateFormat`, `DateTimeFormatter`, or `Instant.parse` directly in feature code.
Why: Server uses microsecond ISO 8601 (`yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`). Ad-hoc formatters silently truncate, produce wrong `updated_at`, and cause 409 storms.
How to apply: Grep for `SimpleDateFormat` / `DateTimeFormatter` in `data/` and `realtime/` — they should not appear outside `DateUtils`.
Known violators: `SupportSyncService` (memory note, Feb 2026 review).

### RP-CD-004 — Push handlers return `OperationOutcome`, never throw
Status: active
Rule: Push-handler entry points must return a `data/repository/sync/handlers/OperationOutcome` value. Translate exceptions into `RETRY` / `DROP` / `CONFLICT_PENDING` / `SKIP` rather than letting them escape.
Why: `SyncQueueProcessor` interprets outcomes to decide retry/backoff. Uncaught exceptions stall the queue or trigger generic retries that lose semantic information (e.g. a 422 that should `DROP` gets retried forever).
How to apply: Every `pushPending*` function ends in a single `OperationOutcome` return path. Use `runCatching` only when followed by explicit outcome mapping — never `getOrNull()` that swallows the error.

### RP-CD-005 — 409 conflicts: read body before any other consumer
Status: active
Rule: A handler that calls `handle409Conflict` / `extractUpdatedAt` must not invoke `errorBody()?.string()` earlier in the same flow. The error body is single-use.
Why: `ResponseBody.string()` drains the buffered source; the second reader gets an empty string and conflict recovery silently SKIPs instead of retrying with the fresh timestamp.
How to apply: 409 handling lives only in `SyncHandlerUtils`. Do not log or sample the body in `.onFailure` blocks above the conflict path.
Known violators: `EquipmentPushHandler.pushPendingEquipmentUpsert` line 219 (memory note, Feb 2026 review).

### RP-CD-006 — DTO fields use explicit `@SerializedName`
Status: active
Rule: Every DTO property that maps a server JSON key must declare `@SerializedName("server_key")`. Do not rely on Gson's field-name matching.
Why: ProGuard/R8 renames fields; reflection-based name matching breaks silently in release builds. Server snake_case rarely matches Kotlin camelCase anyway.
How to apply: Every property in `data/api/dto/*.kt` has the annotation, even when the names appear to match.
Known violators: `EquipmentDto.type` missing `@SerializedName("name")` (memory note, Feb 2026 review).

### RP-CD-007 — `replace*` batch ops are wrapped in `@Transaction`
Status: active
Rule: Any DAO/service method that performs a delete-then-insert (or multiple writes that must be atomic) must be annotated `@Transaction` or wrapped in `db.withTransaction { }`.
Why: A crash or process death mid-batch leaves the table half-populated. Without a transaction, observers fire on the partial state and the UI sees data flicker.
How to apply: `LocalDataService.replace*` and any service-layer batch write. If you cannot annotate (e.g. cross-DAO), use `withTransaction`.
Known violators: Several `LocalDataService.replace*` methods (memory note, Feb 2026 review).

### RP-CD-008 — No `ByteArray` in `@Entity` data classes
Status: active
Rule: Do not declare `ByteArray` properties on Room `@Entity` data classes. Use `String` (base64) or a separate side-table.
Why: Kotlin's generated `equals`/`hashCode` for `data class` use reference equality on arrays, so Room change detection misfires — duplicates, lost updates, and broken `DISTINCT` queries.
How to apply: Move binary payloads out of entities, or override `equals`/`hashCode` explicitly. Sync queue payload is the most common offender — store as `String` (JSON) or a foreign-keyed blob table.
Known violators: `OfflineSyncQueueEntity.payload`, `OfflineConflictResolutionEntity` (memory note, Feb 2026 review).

### RP-CD-009 — Single-source services live on `RocketPlanApplication`
Status: active
Rule: Services that hold lifecycle state (Pusher connection, sync queue, queue managers, secure storage) are constructed once in `RocketPlanApplication` and accessed by feature code via the Application property. Do not `new` them inside ViewModels, Fragments, or repositories.
Why: We have no Dagger/Hilt. The Application class is the de-facto service locator; duplicating construction creates split-brain instances (multiple Pusher sockets, divergent queues).
How to apply: When you need a service, add a property to `RocketPlanApplication` and resolve via `(requireActivity().application as RocketPlanApplication).serviceName`.

### RP-CD-010 — Pusher: disconnect + clear realtime managers on logout
Status: active
Rule: `performSignOut` must call `pusherService.disconnect()` and clear every realtime manager's subscriptions before clearing auth state.
Why: A leftover socket reconnects with the next user's session in a confused state and delivers stale events to the new account. Also leaks bandwidth/battery.
How to apply: `MainActivity.performSignOut` is the canonical sign-out path. Any other sign-out trigger (token expiry, kill switch) must reuse it or replicate the cleanup.

### RP-CD-011 — Tombstone cache cleared after successful delete push
Status: active
Rule: When a delete push succeeds, the handler must call `DeletionTombstoneCache.clearTombstone(entityType, entityId)`.
Why: Tombstones suppress re-creation from incoming server payloads while the delete is in flight. Leaving them set after success blocks legitimate future re-creations of the same ID.
How to apply: Every delete branch in every push handler. Code review checklist item.
Known violators: Several delete handlers missing the call (memory note, Feb 2026 review).

### RP-CD-012 — Production cert pins are real, not placeholders
Status: active
Rule: `RetrofitClient` certificate pins must be actual SHA-256 pins of the production cert chain (or pinning must be deliberately disabled, with a comment explaining why). Placeholder strings like `"sha256/AAAA..."` are forbidden in any committable code path that release builds can hit.
Why: Placeholder pins fail at TLS handshake the moment a release build talks to the real server — every request crashes. Real-but-stale pins also crash on cert rotation; track rotation alongside the value.
How to apply: Production build configuration must read pins from a verified source. Add a unit test that asserts pin format and length.
Known violators: `RetrofitClient` (memory note, Feb 2026 review).

### RP-CD-013 — Logging endpoint is intentionally `/api/logs/ios`
Status: active
Rule: The remote-log POST goes to `/api/logs/ios` even on Android. Do not "fix" this to `/api/logs/android`.
Why: Backend deliberately consolidated mobile logs on the single endpoint; the path is a name, not a platform discriminator. The DTO carries a `platform` field.
How to apply: If a future review surfaces this as a smell, link this rule. Endpoint rename requires a coordinated backend change.

---

## Adding a rule

1. Pick the next free `RP-CD-###`.
2. Fill in the template above. Concrete violation pattern + concrete rationale, or do not add it.
3. If the rule retires/replaces an older rule, set the older rule's status to `retired` with the new rule's number.
4. If the rule was prompted by a real bug, link the `RP-FR-###` (or `RP-BUG-###`) under "Violations".
5. Mention the new rule in `BUG_TRACKER.md`'s field-definitions section only if it changes how the framework itself is described — individual rules are not listed there.
