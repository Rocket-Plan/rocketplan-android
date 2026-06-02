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
last_updated: 2026-05-18
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

_To be filled in as the audit proceeds. Format: `- RP-CD-### at <file>:<line> → filed as RP-FR-###`._

(none yet)

## Known starting points (from project memory)

These are violations already noted in conversation memory; file them as the first batch of children so they don't get lost:

| Rule | Location | Notes |
|------|----------|-------|
| `RP-CD-003` | `SupportSyncService` | Uses its own date parser instead of `DateUtils` |
| `RP-CD-005` | `EquipmentPushHandler.pushPendingEquipmentUpsert` (~line 219) | `.onFailure` consumes `errorBody()?.string()` before `handle409Conflict` can read it |
| `RP-CD-006` | `EquipmentDto.type` | Missing `@SerializedName("name")` |
| `RP-CD-007` | Several `LocalDataService.replace*` methods | Not wrapped in `@Transaction` |
| `RP-CD-008` | `OfflineSyncQueueEntity.payload`, `OfflineConflictResolutionEntity` | `ByteArray` in `data class` |
| `RP-CD-011` | Several delete handlers | Missing `DeletionTombstoneCache.clearTombstone()` |
| `RP-CD-012` | `RetrofitClient` | Placeholder cert pins |

Also unrelated to a specific `RP-CD` rule but worth handling under the same sweep:

- `UpdatedRecordsResponse` is missing `moistureLogs` field → incremental sync misses them. File as `RP-BUG-###`.
- `RoomDetailViewModel` has dead code after `collect` in init block → `RP-FR` if it reaches a rule, otherwise `RP-HD`.
