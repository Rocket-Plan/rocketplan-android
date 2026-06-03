---
bug_id: RP-HD-001
aliases: []
title: Audit every codebase touchpoint against every RP-CD rule
type: hardening
classification: pre_existing_latent
source: internal
evidence: preventive
found_in: "1.0.00"
fixed_in: null
released_in: null
state: investigating
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P2
last_updated: 2026-06-02
---

# Hardening: Codebase audit against `RP-CD` rules

## Why this is `RP-HD` and not `RP-FR`

`RP-FR-###` is filed per concrete violation of a single rule. This ticket is the **sweep** that produces those filings — it has no single failure mode of its own. Each violation discovered here gets a separate `RP-FR-###` (or `RP-BUG-###` if a user-visible failure mode is established) and is linked back from this doc.

## Scope

Walk every file/path in scope below and check it against the full `RP-CD` rule catalog in `docs/architecture/RP-CD_rules.md`. For each violation found, file a child ticket and link it here.

### Audit surfaces (by rule)

| Rule | Audit target |
|------|--------------|
| `RP-CD-001` Soft-delete | All call sites of `dao.delete*` and `localDataService.delete*` outside push handlers |
| `RP-CD-002` Don't clobber dirt | Every `*SyncService` upsert path and every `toEntity()` mapper |
| `RP-CD-003` `DateUtils` only | `grep -rn "SimpleDateFormat\|DateTimeFormatter\|Instant.parse"` across `app/src/main`, excluding `util/DateUtils.kt` |
| `RP-CD-004` `OperationOutcome` | Every `pushPending*` function in `data/repository/sync/handlers/` |
| `RP-CD-005` 409 body single-use | Every `.onFailure` block above a `handle409Conflict` call in push handlers |
| `RP-CD-006` `@SerializedName` | Every property in `data/api/dto/*.kt` |
| `RP-CD-007` `@Transaction` | All `LocalDataService.replace*` and any multi-write service methods |
| `RP-CD-008` No `ByteArray` in entities | `data/local/entity/*.kt` |
| `RP-CD-009` Services on `RocketPlanApplication` | Constructors of long-lived services — must be invoked only from `RocketPlanApplication` |
| `RP-CD-010` Pusher cleanup on logout | `MainActivity.performSignOut` and any other sign-out trigger (token expiry, kill switch) |
| `RP-CD-011` Tombstone clear after delete | Delete branches of every push handler |
| `RP-CD-012` Real cert pins | `RetrofitClient` + release build configuration |
| `RP-CD-013` `/api/logs/ios` is intentional | `RemoteLogger` endpoint references |

## Process

1. For each rule, run the audit query above and produce a list of candidate sites.
2. Inspect each candidate. If it violates the rule:
   - File a new `RP-FR-###` (architectural violation, no demonstrated user impact) or `RP-BUG-###` (user-visible failure can be described).
   - Add the new ID to `BUG_TRACKER.md` and to the "Findings" section below with the rule it violates.
3. If a candidate doesn't violate the rule but reads as if it might, consider whether the rule wording needs to be sharpened in `RP-CD_rules.md`.
4. When all 13 rules are walked, set this ticket to `closed` with `release_state: n/a` (it ships nothing itself; the children carry the work).

## Findings

Format: `- RP-CD-### at <file>:<line> → filed as RP-FR-###`.

- `RP-CD-005` at `data/repository/sync/handlers/EquipmentPushHandler.kt:218-221` (`.onFailure` drains `errorBody()?.string()` before `handle409Conflict` → `extractUpdatedAt` reads an empty body → silent `SKIP`) → filed as **[RP-FR-002](RP-FR-002_equipment_409_body_drain.md)** → **FIXED 2026-06-02**.
- `RP-CD-002` at `data/repository/mapper/SyncEntityMappers.kt` (9 `toEntity()` mappers force `isDirty=false`) + `LocalDataService.saveProjects:693` (blind upsert, no dirty guard) → filed as **[RP-FR-003](RP-FR-003_pull_sync_clobbers_dirty_rows.md)** (P1, needs per-entity pull-path trace before fix).
- `RP-CD-004` at 8 push-handler `else throw e` sites (`EquipmentPushHandler:75,95`; `TimecardPushHandler:55,75`; `MoistureLogPushHandler:49,69`; `PropertyPushHandler:69`; `SupportPushHandler:85`) → filed as **[RP-FR-004](RP-FR-004_push_handlers_throw_instead_of_outcome.md)** (P3, low severity — `SyncQueueProcessor` `runCatching` safety net catches throws, so no queue stall).
- `RP-CD-007` at `LocalDataService.replaceWorkScopeCatalogItems:220` (clear + upsert not wrapped in `withTransaction`, unlike sibling `replace*` methods) → **FIXED directly 2026-06-02** (wrapped in `database.withTransaction {}`); too small for its own child ticket.

### Resolved on verification (not violations)

- `RP-CD-008` — `OfflineSyncQueueEntity` and `OfflineConflictResolutionEntity` declare `ByteArray`
  fields but **already override `equals`/`hashCode`** with `contentEquals`/`contentHashCode`
  (`OfflineEntities.kt:757-798`, `831-872`) — the second compliant remedy in the rule. Change
  detection is not at risk; no migration warranted. Tracked + closed as
  **[RP-FR-001](RP-FR-001_bytearray_in_entities.md)** (closed: already mitigated).

### Rule-wording clarifications (Process step 3 — candidate reads as a violation but isn't)

- `RP-CD-003` — display formatting in the UI layer is **out of scope**. `SimpleDateFormat` appears in
  ~7 `ui/` sites (`SyncStatusFragment`, `ProjectNotesViewModel`, `PhotoViewerViewModel`,
  `RoomDetailViewModel`, `ProjectLossInfoViewModel`, `TimecardAdapter`), but every one is a
  `.format(Date)` call rendering an already-parsed value for display — none `.parse()` a server ISO
  string, so the microsecond-truncation / 409-storm failure mode the rule guards against cannot
  occur. The rule's own "How to apply" scopes the grep to `data/` and `realtime/`. **No ticket
  filed.** Consider sharpening `RP-CD-003` to state explicitly that UI display formatting is exempt.

## Known starting points — current status (verified 2026-06-02)

The seeds below came from the Feb 2026 project-memory notes. Most were resolved by the
`bug-fixes-planned-2026-06-02` batch (PR #3, commit `5ee25dc`); re-verified against current code:

| Rule | Original seed | Status (verified) |
|------|---------------|-------------------|
| `RP-CD-003` | `SupportSyncService` own date parser | ✅ **Fixed** — now uses `DateUtils.parseApiDate` (`SupportSyncService.kt:263-284`). UI cluster is a non-violation (see clarification above). |
| `RP-CD-005` | `EquipmentPushHandler` ~line 219 drains 409 body | ❌ **Open** — `handle409Conflict` retry was added but is still defeated by the line-219 body drain → **RP-FR-002**. |
| `RP-CD-006` | `EquipmentDto.type` missing `@SerializedName("name")` | ✅ **Fixed** — `EquipmentDto.type` now carries `@SerializedName("name")` (`OfflineDtos.kt:658-659`). |
| `RP-CD-007` | Several `LocalDataService.replace*` not in `@Transaction` | ⏳ **Not yet audited** — needs a pass over `LocalDataService.replace*` + multi-write service methods. |
| `RP-CD-008` | `OfflineSyncQueueEntity.payload`, `OfflineConflictResolutionEntity` | ❌ **Open** — three `ByteArray` fields remain → **RP-FR-001**. |
| `RP-CD-011` | Several delete handlers missing `clearTombstone()` | ⏳ **Not yet audited** — equipment delete clears its tombstone (`EquipmentPushHandler.kt:246,257`); other handlers unverified. |
| `RP-CD-012` | `RetrofitClient` placeholder cert pins | ✅ **Fixed** — real `CertificatePinner` built from config pins, applied only when non-empty (`RetrofitClient.kt:111-127`). Resolved with RP-BUG-011. |

Also unrelated to a specific `RP-CD` rule, still open under this sweep:

- `UpdatedRecordsResponse` missing `moistureLogs` field → incremental sync misses them. **Still to
  file as `RP-BUG-###`** (confirm against current `UpdatedRecordsResponse` first).
- `RoomDetailViewModel` dead code after `collect` in init block → `RP-FR` if it reaches a rule,
  otherwise `RP-HD`. **Still to triage.**

## Audit completion status (2026-06-02)

All 13 `RP-CD` rules have now been walked.

| Rule | Verdict |
|------|---------|
| `RP-CD-001` Soft-delete | CLEAN — delete paths set `isDeleted`/`isDirty`; cascades are transactional |
| `RP-CD-002` Don't clobber dirt | VIOLATION → **RP-FR-003** (P1) |
| `RP-CD-003` DateUtils only | CLEAN (data/realtime); UI `.format()` cluster is a non-violation (clarification above) |
| `RP-CD-004` Return OperationOutcome | VIOLATION (low sev) → **RP-FR-004** (P3) |
| `RP-CD-005` 409 body single-use | VIOLATION → **RP-FR-002** (fixed) |
| `RP-CD-006` `@SerializedName` | CLEAN — `EquipmentDto.type` now annotated |
| `RP-CD-007` `@Transaction` | VIOLATION → fixed directly (`replaceWorkScopeCatalogItems`) |
| `RP-CD-008` No `ByteArray` in entities | CLEAN — explicit `contentEquals`/`hashCode` overrides; RP-FR-001 closed |
| `RP-CD-009` Services on `RocketPlanApplication` | CLEAN |
| `RP-CD-010` Pusher cleanup on logout | CLEAN — `performSignOut` disconnects + clears realtime managers |
| `RP-CD-011` Tombstone clear after delete | CLEAN — all delete handlers clear tombstones |
| `RP-CD-012` Real cert pins | CLEAN — real `CertificatePinner` from config |
| `RP-CD-013` `/api/logs/ios` intentional | CLEAN — confirmed intentional |

Two leftover non-`RP-CD` notes from the original doc were also triaged and found to be
**non-issues** against current code: `UpdatedRecordsResponse` **does** include `moistureLogs`
(`@SerializedName("moisture_logs")`, wired into `hasAnyUpdates()` + deletion sync), and
`RoomDetailViewModel` has **no dead code** after its `.collect`/`.collectLatest` blocks.

**Remaining open child work:** RP-FR-003 (P1 dirty-clobber trace) and RP-FR-004 (P3 handler outcome
tightening). Once both are resolved this umbrella can be `closed`. Left `investigating` until then.
