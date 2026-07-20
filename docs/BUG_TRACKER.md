# RocketPlan Bug Tracker

> **Single source of truth for all user-facing bugs, crashes, hangs, and functional defects.**
> Every investigation doc, plan, and review must link back here.
> Investigation docs carry YAML front matter that mirrors the fields below.
>
> **Matching a new report or checking regression risk?** Start in [`SYMPTOM_INDEX.md`](SYMPTOM_INDEX.md) — concrete user-visible symptoms ("room card spinner never stops", "room shows N photos but none load") mapped to the most likely existing ticket. Faster than scanning this file's prose rows.

---

## Shipping Status

| Version | Build | Status |
|---------|-------|--------|
| **1.30** | 35 | ✅ **Released to Production** (100%, 2026-06-08 5:07 PM). Bundles **RP-BUG-043, 044, 045, 047, 048**. Replaced 34 (Play-rejected for unused READ_MEDIA_IMAGES/VIDEO — removed, RP-CD-023; app-wide flag cleared by superseding old 1.29 builds on all tracks). |
| **1.30** | 34 | Superseded by 35 (rejected: media-permission policy). |
| **1.29** | 32 / 29 | Previous production builds (29 was the broadly-live version), superseded by 1.30 (35). |
| **1.0.00** | — | — |

---

## How to Use This Tracker

### Field Definitions

**State** — where the fix is:
- `investigating` — root cause not confirmed
- `open` — root cause confirmed, but no fix currently planned or assigned
- `planned` — root cause confirmed, fix in progress or written
- `fixed` — fix committed to branch
- `monitoring` — root cause known, no fix landed, but not actively firing. Watch Sentry; promote to `open`/`planned` if it recurs, or `closed` after a defined no-recurrence window.
- `closed` — no fix needed (ignored, not actionable)

**Release State** — whether the fix has shipped to users:
- `unreleased` — fix exists in branch but not in a shipped version
- `released` — fix shipped to users
- `n/a` — not applicable (no fix, or closed)

**Classification**:
- `pre_existing_latent` — bug predated current milestone but was latent; became reachable when navigation or architecture changed
- `new_code_bug` — introduced in new functionality at the same time the functionality was written
- `regression` — a fix for another bug caused this bug
- `pre_existing_worsened` — existed before, amplified by increased scale or data pressure

**Source**: `sentry` | `qa` | `review` | `customer` | `internal`

**Found Timestamp** (`found_at`) — record the exact moment the bug was first observed or first registered, in repo-local Pacific time (`America/Los_Angeles`) with the correct seasonal abbreviation:
- `PST` for Pacific Standard Time
- `PDT` for Pacific Daylight Time

Use an exact timestamp, not just a date. Format: `YYYY-MM-DD HH:MM:SS PST` / `YYYY-MM-DD HH:MM:SS PDT`. If the true first-observed time is known (Sentry, QA notes, `adb logcat`, backend `log_entries`), record that; if only the registration time is known, record it and say so in the investigation body. The table **Found** column holds the build/version (`found_in`); the precise timestamp lives in front-matter `found_at` + the investigation body. (Mirrors the iOS tracker convention.)

**ID Conventions** — prefix is chosen by **failure concreteness**, not by where the issue was discovered. (Discovery is captured in `Source`; evidence strength is captured in `Evidence` — see below.)

- `ROCKET-PLAN-ANDROID-*` — Sentry short ID. Use when there's a crash event regardless of how concrete the failure is. Sentry IDs always win the prefix lottery.
- `RP-BUG-###` — **Concrete defect.** A plausible user-visible failure mode exists with a symptom that can be described or a reproducer that can be written, even if not yet observed in Sentry. The investigation doc has a "Symptom" section that names what the user sees.
- `RP-FR-###` — **Feature Request.** A net-new capability or enhancement to build (e.g. a new screen/flow). *Also historically used for architectural / rule violations (`RP-CD-###` code-shape concerns with no demonstrated user-visible failure); existing `RP-FR-001..008` carry that older sense.*
- `RP-HD-###` — **Hardening / preventive guard.** Defense-in-depth check, capacity tuning, or operational safeguard. Not currently broken; the guard is preventive. Often "the primary defense already works; this is a second line."

**The boundary between `RP-BUG` and `RP-FR`:** if you can finish the sentence *"the user will see X when Y happens"* with concrete X and Y, it's `RP-BUG`. If the closest you can get is *"this code violates RP-CD-### and could surface a problem if Z is also true"*, it's `RP-FR`. If the code is fine today and the change is purely preventive, it's `RP-HD`.

**Source** (orthogonal to prefix — describes how the issue was found):
`sentry` · `qa` · `review` · `customer` · `internal`

**Evidence** (orthogonal to prefix — describes the strength of the failure-mode claim):
- `observed` — user/Sentry/QA report exists
- `reproducible` — internal reproducer demonstrated
- `inferred` — failure mode argued from code shape, not yet reproduced
- `preventive` — no failure mode today; rule or guard is being added defensively

**Aliases / Related IDs**: Other crash IDs that share the same root cause and are resolved by the same fix. Use the lowest-numbered or earliest-reported ID as canonical.

**Regression Of**: The canonical bug ID whose fix introduced this bug. Empty for most bugs.

**Observability**: Every investigation doc should contain a visible `## Observability` section that explains current signals, known gaps, planned instrumentation, and how production log noise will be controlled. Prefer local `Log.debug` for verbose tracing and reserve remote logging for low-volume, actionable diagnostics.

### Templates

#### Investigation doc front matter

```yaml
---
# Use bug_id for single-bug docs; use bug_ids (list) for docs that cover multiple canonical bugs
bug_id: ROCKET-PLAN-ANDROID-XXXX
# bug_ids: [ROCKET-PLAN-ANDROID-XXXX, ROCKET-PLAN-ANDROID-YYYY]
aliases: []   # non-canonical IDs resolved by the same fix (not separate canonical bugs)
title: Short description
type: crash | hang | threading | memory | ui_bug | performance | functional
classification: pre_existing_latent | new_code_bug | regression | pre_existing_worsened
source: sentry | qa | review | customer | internal
found_in: "1.0.XX+XXX"
found_at: "YYYY-MM-DD HH:MM:SS PST"
fixed_in: null
released_in: null
state: investigating | open | planned | fixed | closed
release_state: unreleased | released | n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
last_updated: YYYY-MM-DD
---
```

#### Investigation doc required observability section

```md
## Observability

### Current Signals
- Local console logs:
- Remote logs:
- Sentry:
- Existing metrics/watchdogs:

### Gaps
- What failure is currently silent?
- What is ambiguous today?

### Proposed Instrumentation
- Local debug logs to add:
- Remote logs to add:
- Log category names:
- Key fields:
- Sampling / throttling:
- Build/env gating:

### Success Criteria
- How we'll know the fix worked in QA
- How we'll detect recurrence in the wild
```

Remote logging guidance:
- Prefer one-shot, threshold-crossing, or terminal-state logs over repetitive progress spam.
- Do not send raw user-entered text, secrets, tokens, or full URLs with query parameters.
- In prod, prefer warning/error-level operational logs or explicitly throttled/sampled categories.
- If a log is only useful during local investigation, keep it console-only with `Log.debug`.

#### Lifecycle state transitions

| Step | Action | State → |
|------|--------|---------|
| Bug discovered | Register in tracker + create investigation doc | `investigating` |
| Root cause confirmed, no fix planned yet | Update tracker | `open` |
| Root cause confirmed + fix planned | Add to `docs/plans/` | `planned` |
| Fix committed | Update `fixed_in` in tracker + investigation front matter | `fixed` |
| Version ships | Update `released_in` + release state | `fixed` / `released` |
| Bug not actionable | Add note, mark closed | `closed` |

---

## Bug Registry

### Canonical Bugs

Column key: **Class.** = Classification · **Rel** = Release State · **Reg. Of** = Regression Of

| ID | Priority | Aliases | Title | Type | Class. | Found | Fixed | State | Rel | Reg. Of | Investigation |
|----|----------|---------|-------|------|--------|-------|-------|-------|-----|---------|---------------|
| `RP-BUG-001` | P0 | — | Destructive Migration Enabled in Production | functional | pre_existing_latent | 1.0.00 | — | fixed | n/a | — | [RP-BUG-001](investigations/RP-BUG-001_destructive_migration.md) · [plan](plans/plan_critical_p0_001_006_2026-05-13.md) · [review](reviews/code_review_RP-BUG-001_006_2026-05-18.md) |
| `RP-BUG-002` | P0 | — | printStackTrace() Leaks Sensitive Data in Production | functional | new_code_bug | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-002](investigations/RP-BUG-002_printstacktrace_leak.md) · [plan](plans/plan_rp_bug_002_018_logging_pii_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-003` | P1 | — | PriorityQueue Thread-Safety Violation in SyncQueueManager | crash | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-003](investigations/RP-BUG-003_priorityqueue_threadsafety.md) · [plan](plans/plan_rp_bug_003_010_015_sync_queue_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-004` | P2 | — | Silent Fallback When ConnectivityManager Unavailable | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-004](investigations/RP-BUG-004_connectivity_silent_fallback.md) · [plan](plans/plan_rp_bug_004_connectivity_silent_fallback_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-005` | P0 | — | Non-Blocking Write to EncryptedSharedPreferences for Auth Token | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-005](investigations/RP-BUG-005_auth_token_apply.md) · [plan](plans/plan_rp_bug_005_auth_token_apply_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-006` | P0 | — | Race Condition in Auth Token Migration on Startup | functional | pre_existing_latent | 1.0.00 | — | fixed | n/a | — | [RP-BUG-006](investigations/RP-BUG-006_auth_token_race.md) · [plan](plans/plan_critical_p0_001_006_2026-05-13.md) · [review](reviews/code_review_RP-BUG-001_006_2026-05-18.md) |
| `RP-BUG-007` | P2 | — | Unshared OkHttpClient Configuration for Photo Cache | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-007](investigations/RP-BUG-007_photo_http_client.md) · [plan](plans/plan_rp_bug_007_011_network_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-008` | P0 | — | Sync Checkpoints Stored in Cleartext SharedPreferences | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-008](investigations/RP-BUG-008_sync_checkpoint_cleartext.md) · [plan](plans/plan_rp_bug_008_sync_checkpoint_cleartext_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-009` | P1 | — | Bitmap Memory Leak in Thumbnail Generation | memory | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-009](investigations/RP-BUG-009_bitmap_memory_leak.md) · [plan](plans/plan_rp_bug_009_019_022_photo_cache_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-010` | P1 | — | Unbounded Photo Sync Job Blocking Other Operations | hang | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-010](investigations/RP-BUG-010_sync_job_blocking.md) · [plan](plans/plan_rp_bug_003_010_015_sync_queue_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-011` | P2 | — | Certificate Pinning Not Implemented | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-011](investigations/RP-BUG-011_cert_pinning.md) · [plan](plans/plan_rp_bug_007_011_network_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-012` | P2 | — | Untracked Coroutine Scope in Application Startup | threading | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-012](investigations/RP-BUG-012_application_coroutine_scope.md) · [plan](plans/plan_rp_bug_012_application_coroutine_scope_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-013` | P3 | — | Swallowed Exception in ImageProcessorRetryWorker | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-013](investigations/RP-BUG-013_retry_worker_cancellation.md) · [plan](plans/plan_rp_bug_013_017_020_image_queue_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-014` | P1 | — | Realm-like ID Pattern Fragile Migration Logic | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-014](investigations/RP-BUG-014_id_migration_fragile.md) · [plan](plans/plan_rp_bug_014_016_migration_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-015` | P2 | — | Debounce May Not Prevent Rapid Re-enqueues | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-015](investigations/RP-BUG-015_debounce_rapid_enqueues.md) · [plan](plans/plan_rp_bug_003_010_015_sync_queue_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-016` | P1 | — | Missing Migration 27_28 in Database Version Sequence | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-016](investigations/RP-BUG-016_missing_migration.md) · [plan](plans/plan_rp_bug_014_016_migration_2026-06-01.md) · [review](reviews/code_review_planned_batch_2026-06-02.md) |
| `RP-BUG-017` | P2 | — | ImageProcessorQueueManager Shutdown Doesn't Wait for In-Flight Requests | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-017](investigations/RP-BUG-017_shutdown_inflight_requests.md) · [plan](plans/plan_rp_bug_013_017_020_image_queue_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-018` | P2 | — | Session Object Printed in Debug Logging May Contain Sensitive Data | functional | new_code_bug | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-018](investigations/RP-BUG-018_session_logging.md) · [plan](plans/plan_rp_bug_002_018_logging_pii_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-019` | P2 | — | Potential ConcurrentModificationException in PhotoCacheManager | crash | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-019](investigations/RP-BUG-019_concurrent_modification.md) · [plan](plans/plan_rp_bug_009_019_022_photo_cache_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-020` | P2 | — | Error Handling Swallows Failures in ImageProcessorQueueManager | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-020](investigations/RP-BUG-020_queue_error_swallowed.md) · [plan](plans/plan_rp_bug_013_017_020_image_queue_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-021` | P3 | — | Hardcoded Magic Numbers for Retry Configuration | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-021](investigations/RP-BUG-021_magic_numbers.md) · [plan](plans/plan_rp_bug_021_magic_numbers_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-022` | P3 | — | Inefficient LRU Calculation in Photo Cleanup | performance | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-022](investigations/RP-BUG-022_lru_cleanup.md) · [plan](plans/plan_rp_bug_009_019_022_photo_cache_2026-06-01.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-023` | P3 | — | TODO Comments for Incomplete Features in PeopleFragment | functional | pre_existing_latent | 1.0.00 | — | closed | n/a | — | [RP-BUG-023](investigations/RP-BUG-023_todo_incomplete_features.md) · [assessment](plans/plan_rp_bug_023_todo_incomplete_features_2026-06-01.md) |
| `RP-BUG-024` | P2 | — | Pusher throttledErrorTimestamps Unbounded Growth | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-024](investigations/RP-BUG-024_pusher_throttle_map.md) · [plan](plans/plan_rp_bug_024_pusher_throttle_map_2026-05-18.md) · [review](reviews/code_review_rp_bug_024_027_2026-05-18.md) |
| `RP-BUG-025` | P2 | — | LocalDataService.currentCompanyId Throws If Accessed Before Login | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-025](investigations/RP-BUG-025_current_company_id_npe.md) · [plan](plans/plan_rp_bug_025_current_company_id_2026-05-18.md) · [review](reviews/code_review_rp_bug_024_027_2026-05-18.md) |
| `RP-BUG-026` | P2 | — | SecureStorage.clearAll() Doesn't Clear migrationDeferred | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-026](investigations/RP-BUG-026_clearall_migration_deferred.md) · [plan](plans/plan_rp_bug_026_clearall_migration_deferred_2026-05-18.md) · [review](reviews/code_review_rp_bug_024_027_2026-05-18.md) |
| `RP-BUG-027` | P3 | — | Room SyncStatus Enum Comparisons Use String Parsing | functional | pre_existing_latent | 1.0.00 | — | fixed | unreleased | — | [RP-BUG-027](investigations/RP-BUG-027_syncstatus_string_parse.md) · [plan](plans/plan_rp_bug_027_syncstatus_storage_mapping_2026-05-18.md) · [review](reviews/code_review_rp_bug_024_027_2026-05-18.md) |
| `RP-BUG-028` | P0 | — | Legacy Auth-Token Migration Never Runs (Dead Wiring) | functional | regression | 1.0.00 | — | fixed | unreleased | RP-BUG-006 | [RP-BUG-028](investigations/RP-BUG-028_legacy_token_migration_unwired.md) · [review](reviews/code_review_bug_bundle_2_2026-06-01.md) |
| `RP-BUG-029` | P3 | — | Android deletion sync marks omitted deleted properties but does not reconcile child locations/rooms, leaving orphaned stale locations when backend omits cascade children | functional | pre_existing_latent | Parity review 2026-06-04 vs iOS RP-BUG-268 / backend MONGOOSE-BUG-013 | 1.29 (32) | fixed | unreleased | — | [RP-BUG-029](investigations/RP-BUG-029_deleted_property_omitted_child_location_orphan.md) · iOS `RP-BUG-268` · backend `MONGOOSE-BUG-013` |
| `RP-BUG-030` | P2 | — | ImageProcessorQueueManager.resolveServerRoomId returns local ID instead of blocking when room not synced — photo uploads go to wrong room | functional | pre_existing_latent | Parity review 2026-06-03 vs iOS RP-BUG-065 | 1.29 (32) | fixed | unreleased | — | [RP-BUG-030](investigations/RP-BUG-030_image_processor_room_id_zero.md) · iOS `RP-BUG-065` |
| `RP-BUG-031` | P2 | — | RoomPushHandler.handleUpdate returns SUCCESS after retry failures — operation removed from queue but server never updated, room stays stale | functional | pre_existing_latent | Parity review 2026-06-03 vs iOS RP-BUG-012 | 1.29 (32) | fixed | unreleased | — | [RP-BUG-031](investigations/RP-BUG-031_room_update_success_on_failure.md) · iOS `RP-BUG-012` |
| `RP-BUG-032` | P2 | — | OfflineMaterialEntity missing projectId field — materials cannot be scoped to project during sync, global observe returns all materials | functional | pre_existing_latent | Parity review 2026-06-03 vs iOS RP-BUG-160 / RP-BUG-177 | 1.29 (32) | fixed | unreleased | — | [RP-BUG-032](investigations/RP-BUG-032_material_entity_missing_project_id.md) · iOS `RP-BUG-160` · iOS `RP-BUG-177` |
| `RP-BUG-033` | P3 | — | MoistureLogRequest missing dryingGoal field — user-set drying goals not persisted to server, silently lost on sync | functional | pre_existing_latent | Parity review 2026-06-03 vs iOS RP-BUG-124 | 1.29 (32) | fixed | unreleased | — | [RP-BUG-033](investigations/RP-BUG-033_moisture_log_missing_drying_goal.md) · iOS `RP-BUG-124` |
| `RP-BUG-034` | P2 | — | PropertyPushHandler sends propertyTypeId=0 for offline-created properties — server returns 422 and operation silently dropped | functional | pre_existing_latent | Parity review 2026-06-03 vs iOS RP-BUG-166 | 1.29 (32) | fixed | unreleased | — | [RP-BUG-034](investigations/RP-BUG-034_property_type_id_missing.md) · iOS `RP-BUG-166` |
| `RP-BUG-035` | P2 | — | WorkScopeSyncService.syncRoomWorkScopes fetches from API without merging pending local creates — locally-staged items vanish on refresh | functional | pre_existing_latent | Parity review 2026-06-03 vs iOS RP-BUG-027 | 1.29 (32) | fixed | unreleased | — | [RP-BUG-035](investigations/RP-BUG-035_workscope_pending_create_merge_gap.md) · iOS `RP-BUG-027` |
| `RP-BUG-048` | P2 | — | RocketDry creates a new local material per reading/goal (no name-based dedup) — repeated readings on one material spawn duplicate local materials that collapse to one server id but are never deduped locally, stranding child moisture readings (root of RP-BUG-046) | functional | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-BUG-048](investigations/RP-BUG-048_duplicate_local_materials_collapse_to_one_serverid.md) · [review](reviews/code_review_rp_bug_048_2026-06-08.md) · [test](../app/src/test/java/com/example/rocketplan_android/data/local/dao/MaterialByNameInRoomDaoTest.kt) · create-side prevention + collapse/backfill repair both landed · found by scripts/check_sync_duplicates.sh |
| `RP-BUG-047` | P2 | — | Room moisture-log pull sends include=photo,moisture_log — backend rejects the invalid "moisture_log" relation with HTTP 400 for every room, so moisture readings never download (iOS sends include=photo) | functional | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-BUG-047](investigations/RP-BUG-047_moisture_log_pull_400_invalid_include.md) · [review](reviews/code_review_rp_bug_046_047_2026-06-07.md) · [test](../app/src/test/java/com/example/rocketplan_android/data/repository/sync/ProjectMetadataSyncServiceTest.kt) · iOS DamageService.getRoomMoistureLogs · ProjectMetadataSyncService.kt:252 |
| `RP-BUG-046` | P2 | — | Offline-created moisture logs rejected with HTTP 422 and silently dropped (reading 8.0 is valid → likely route-derived damage_type drying-eligibility or room_id existence); handler logged only "HTTP 422" not the body, so the failing rule couldn't be diagnosed | functional | pre_existing_latent | 1.0.00 | — | investigating | unreleased | — | [RP-BUG-046](investigations/RP-BUG-046_moisture_log_422_dropped_no_detail.md) · [plan](plans/plan_rp_bug_046_moisture_422_re_enqueue_2026-06-11.md) · [review](reviews/code_review_rp_bug_046_2026-06-08.md) · RP-BUG-048 removed the duplicate-material trigger; part 1 landed (`280c444`): 422 now defer-SKIPs (bounded→FAILED, no silent drop) + startup repair sweep re-enqueues orphaned PENDING logs; part 2 (exact rejecting rule) still investigating pending device repro · MoistureLogPushHandler.kt / SyncQueueProcessor.kt |
| `RP-BUG-045` | P2 | — | Remote log batches containing a WARN-level entry are rejected by the backend (HTTP 400 — sends "WARN", backend requires "WARNING") and the whole batch is dropped — warning-class telemetry never reaches LogEntry (incl. the RP-BUG-044 partial-failure log) | functional | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-BUG-045](investigations/RP-BUG-045_remote_log_warn_level_rejected_batch_dropped.md) · [test](../app/src/test/java/com/example/rocketplan_android/logging/LogLevelWireTest.kt) · RemoteLogger.kt / backend IosLoggingController.php:87 |
| `RP-BUG-044` | P2 | — | syncAllRoomPhotos swallows per-room photo-fetch failures — segment returns success with 0 photos, no retry, room left with photoCount>0 and no local photos | functional | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-BUG-044](investigations/RP-BUG-044_sync_all_room_photos_swallows_per_room_failures.md) · [plan](plans/plan_rp_bug_044_sync_all_room_photos_partial_failure_2026-06-07.md) · [test](../app/src/test/java/com/example/rocketplan_android/data/repository/sync/PhotoSyncServiceTest.kt) · pairs with RP-BUG-043 |
| `RP-BUG-043` | P2 | — | pendingPhotoSyncs leaks when the CONTENT_ONLY follow-up is coalesced/dropped by enqueue (key ignores mode) — room-card spinner hangs forever and the project's photos never download | hang | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-BUG-043](investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md) · [plan](plans/plan_rp_bug_043_pending_photo_syncs_leak_2026-06-07.md) · [review](reviews/code_review_rp_bug_043_2026-06-07.md) · [test](../app/src/test/java/com/example/rocketplan_android/data/sync/SyncQueueManagerPhotoSyncFlagTest.kt) |
| `RP-BUG-042` | P3 | — | Global reference data (damage/claim/scope/project types) not seeded before Phase 2 — offline pickers can be empty until a project's metadata has synced (iOS seeds globally up front) | functional | pre_existing_latent | iOS parity review 2026-06-07 | 1.0.00 | fixed | unreleased | — | [RP-BUG-042](investigations/RP-BUG-042_reference_data_not_seeded_before_phase2.md) · iOS `AppViewModel` RP-BUG-177 |
| `RP-BUG-041` | P3 | — | Android shows no per-item cloud/download indicator — users can't tell which content isn't downloaded yet (cloud-down) or has unsynced local changes (cloud-up) like iOS does | ui_bug | pre_existing_latent | iOS parity review 2026-06-07 | 1.0.00 | fixed | unreleased | — | [RP-BUG-041](investigations/RP-BUG-041_no_per_item_download_sync_cloud_indicator.md) · iOS `ProjectListPageViewModel` / `FileDownloaderManager` |
| `RP-BUG-040` | P2 | — | Offline delete of a server-modified entity fails — delete handlers send a stale updated_at and the backend returns 409; Group A (Note/AtmosphericLog/Room/Location/Property) retry the stale timestamp until abandoned, Group B (Equipment/MoistureLog) silently swallow it as success (Photo safe) | functional | pre_existing_latent | 409 sweep 2026-06-07 (backend verified) | 1.0.00 | fixed | unreleased | — | [RP-BUG-040](investigations/RP-BUG-040_delete_409_stale_timestamp_retry_loop.md) · rule RP-CD-005 |
| `RP-BUG-039` | P2 | — | Timecard down-sync is unwired — getTimecards/saveTimecards exist but are never called, so server-side timecards (admin/web/other-device) never appear locally (iOS pulls them; would also need serverId reconcile to avoid RP-BUG-038 duplicates once wired) | functional | pre_existing_latent | sweep + iOS parity 2026-06-07 | 1.0.00 | fixed | unreleased | — | [RP-BUG-039](investigations/RP-BUG-039_timecard_downsync_unwired.md) · iOS `TimecardService.getTimecards` · related [RP-BUG-038](investigations/RP-BUG-038_material_duplicate_on_metadata_refresh.md) |
| `RP-BUG-038` | P2 | — | Offline-created materials duplicate after metadata refresh — material pull maps to a server-id PK with no serverId reconciliation, and the server-minted uuid differs from the local uuid (missed by the RP-BUG-037 sweep) | functional | pre_existing_latent | model review 2026-06-07 (backend + Room probe verified) | 1.0.00 | fixed | unreleased | — | [RP-BUG-038](investigations/RP-BUG-038_material_duplicate_on_metadata_refresh.md) · related [RP-BUG-037](investigations/RP-BUG-037_offline_create_duplicate_on_metadata_refresh.md) |
| `RP-BUG-037` | P2 | — | Offline-created notes/equipment/moisture logs/atmospheric logs duplicate after metadata refresh — pull maps server rows to a server-id PK with no serverId reconciliation, and the server-minted uuid differs from the local uuid so the unique index does not collapse them | functional | pre_existing_latent | RP-BUG-036 class sweep 2026-06-07 (backend + Room probe verified) | 1.0.00 | fixed | unreleased | — | [RP-BUG-037](investigations/RP-BUG-037_offline_create_duplicate_on_metadata_refresh.md) · probe [UpsertIdentityProbeTest](../../app/src/test/java/com/example/rocketplan_android/data/local/dao/UpsertIdentityProbeTest.kt) · related [RP-BUG-036](investigations/RP-BUG-036_support_duplicate_on_refresh.md) |
| `RP-BUG-036` | P2 | — | Support conversations/messages duplicate on refresh — pull does no serverId reconciliation and client UUID is never sent, so a second row is inserted per server id | functional | pre_existing_latent | RP-CD-014 verification trace 2026-06-07 | 1.0.00 | fixed | unreleased | — | [RP-BUG-036](investigations/RP-BUG-036_support_duplicate_on_refresh.md) · [plan](plans/plan_rp_bug_036_support_identity_reconcile_2026-06-07.md) · rule [RP-CD-014](../architecture/RP-CD_rules.md) |
| `RP-FR-001` | P2 | — | ByteArray properties in Room @Entity data classes (RP-CD-008) | functional | pre_existing_latent | 1.0.00 | — | closed | n/a | — | [RP-FR-001](investigations/RP-FR-001_bytearray_in_entities.md) · parent [RP-HD-001](investigations/RP-HD-001_rp_cd_rule_audit.md) |
| `RP-FR-002` | P2 | — | EquipmentPushHandler drains 409 body before conflict recovery (RP-CD-005) | functional | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-FR-002](investigations/RP-FR-002_equipment_409_body_drain.md) · parent [RP-HD-001](investigations/RP-HD-001_rp_cd_rule_audit.md) |
| `RP-FR-003` | P1 | — | Pull-sync save path can overwrite locally-dirty rows (RP-CD-002) | functional | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-FR-003](investigations/RP-FR-003_pull_sync_clobbers_dirty_rows.md) · parent [RP-HD-001](investigations/RP-HD-001_rp_cd_rule_audit.md) |
| `RP-FR-004` | P3 | — | Some push handlers throw instead of returning OperationOutcome (RP-CD-004) | functional | pre_existing_latent | 1.0.00 | 1.0.00 | fixed | unreleased | — | [RP-FR-004](investigations/RP-FR-004_push_handlers_throw_instead_of_outcome.md) · parent [RP-HD-001](investigations/RP-HD-001_rp_cd_rule_audit.md) |
| `RP-FR-005` | P3 | — | Support message attachments store server message id in local FK field, so future attachment reads will not reconcile (latent — attachment rendering not wired) | functional | pre_existing_latent | RP-BUG-036 identity trace 2026-06-07 | 1.0.00 | fixed | unreleased | — | [RP-FR-005](investigations/RP-FR-005_support_attachment_message_id_mismatch.md) · related [RP-BUG-036](investigations/RP-BUG-036_support_duplicate_on_refresh.md) |
| `RP-FR-008` | P3 | — | No "block Phase 2 metadata sync while editing a form" gate like iOS Phase2GatingService — Android relies on preserveDirty only (data-loss covered; jank motivation is iOS Core-Data-specific) | functional | pre_existing_latent | iOS parity review 2026-06-07 | — | closed | n/a | — | [RP-FR-008](investigations/RP-FR-008_no_phase2_edit_gate.md) · iOS `Phase2GatingService` (RP-BUG-098) |
| `RP-FR-006` | P3 | — | Support message attachments duplicate on refresh — attachment pull does no serverId reconciliation, so re-pull inserts a new row per server attachment id (latent — attachment rendering not wired) | functional | pre_existing_latent | RP-FR-005 fix 2026-06-07 | 1.0.00 | fixed | unreleased | — | [RP-FR-006](investigations/RP-FR-006_support_attachment_duplicate_on_refresh.md) · related [RP-BUG-036](investigations/RP-BUG-036_support_duplicate_on_refresh.md) · [RP-FR-005](investigations/RP-FR-005_support_attachment_message_id_mismatch.md) |
| `RP-FR-009` | P2 | — | Floorplan sketching — canvas editor (freehand draw → straighten to lines → resize rooms by editing wall lengths), append-only revisions + restore, room binding to real Rooms; consumes backend Floorplan API; gated behind `floorplan_enabled` | feature | new_feature | spec 2026-06-14 | — | open | unreleased | — | [RP-FR-009](investigations/RP-FR-009_floorplan_sketching.md) · sibling iOS RP-FR-016 · GH #5 |
| `RP-FR-018` | P2 | — | ~~Equipment Move & Tracking (Android client) — count-based move room→room / transfer project→project + movement history against MONGOOSE-FR-014 move/transfer/movements endpoints~~ **SUPERSEDED by `RP-FR-019` (serialized equipment).** The count-based move/transfer model and its backend endpoints (`/equipment-rooms/{id}/move`, `/transfer`, `/movements`, `/projects/{p}/equipment-movements`) no longer exist on the backend; the serialized-asset subsystem replaces this feature entirely. Implementation merged in `9f6b540` was **reverted** (`dea4ff3`, branch `cleanup/remove-count-equipment-movement`); archived at `backup/android-count-equipment-move-transfer-2026-07-13` | feature | new_feature | spec 2026-07-09 | — | superseded | n/a | — | superseded by `RP-FR-019` · reverted in `dea4ff3` · archive branch `backup/android-count-equipment-move-transfer-2026-07-13` · siblings iOS `RP-FR-023` / Webapp `WEBAPP-FR-004` |
| `RP-FR-019` | P2 | — | Serialized Equipment (Android client) — greenfield flag-gated subsystem replacing count-based equipment. Each unit is an individually-tracked asset in a company pool; deploy to room, move between same-company jobs, check-out to pool, placement history, register/edit/retire. Consumes serialized endpoints: `GET/POST /companies/{company}/equipment-assets`, `GET/PUT/DELETE /equipment-assets/{id}`, `GET/POST /equipment-assets/{id}/placements`, `POST /equipment-assets/{id}/move`, `POST /equipment-assets/{id}/check-out`, `GET /rooms/{room}/equipment-assets`, `GET /companies/{company}/equipment-asset-timeline`. Separate models/DAOs/sync (`OfflineEquipmentAssetEntity`/`OfflineEquipmentPlacementEntity`) — NOT layered onto `OfflineEquipmentEntity`. Feature flag `serializedEquipment` cached **per-company** (multi-company users differ); unknown/failed mode mounts no write UI (retryable) + golden-fixture parse tests. **Does NOT fix `RP-BUG-279`** — the legacy (flag-OFF) equipment write-sync still targets the dead `PUT`/`DELETE /api/equipment/{id}` routes; that corrected-pivot fix was descoped and RP-BUG-279 stays open | feature | new_feature | spec 2026-07-13 | — | in_progress | unreleased | — | supersedes `RP-FR-018` · does NOT fix `RP-BUG-279` (descoped — see that row) · backend `mongoose:MONGOOSE-FR-014` / `WEBAPP-FR-007` (timeline) · siblings iOS `RP-FR-023` · [plan](plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md) · [on-device checklist](testing/TEST_CHECKLIST_RP-FR-019_on-device_2026-07-14.md) · branch `feat/RP-FR-019-serialized-equipment` (PR #8, draft; stacked on cleanup PR #7) · **Full data/sync/gating impl complete + 8 review rounds + self-review applied**: contract, persistence+migration, full sync spine (register/update/retire/deploy/move/check-out) with authoritative pull + two-axis merge, per-company observable mode + write-boundary gate, legacy-wide RocketDry cutover gate, register-catalog + move-room pickers — all unit-tested, APK builds clean. Remaining (external/verification): on-device enabled-company E2E, instrumented Fragment/nav transition tests, backend company-scoped mode endpoint / push (currently 60s poll) |
| `RP-FR-024` | P3 | — | RP-FR-019 equipment DTOs omit explicit `@SerializedName` on ~18 name-matching fields (RP-CD-006) — safe today only because `proguard-rules.pro:72` keeps `data.model.offline.**` from R8 renaming; add annotations as defense-in-depth. No user-visible failure (rule-shape). **PARTIAL 2026-07-17**: timeline DTOs + `idempotency` mismatch annotated; 11 core `EquipmentAssetDto`/`EquipmentAssetPlacementDto` matching-name fields (`id,uuid,name,manufacturer,model,status,vendor,note`; placement `id,uuid,note`) still bare — full conformance not yet reached | functional | new_code_bug | feat/RP-FR-019 (35-dev) | — | planned | unreleased | — | [RP-FR-024](investigations/RP-FR-024_equipment_dtos_missing_serializedname_2026-07-17.md) · [plan](plans/plan_rp_fr_024_equipment_serializedname_2026-07-17.md) |
| `RP-FR-025` | P3 | — | Legacy count-based `EquipmentPushHandler.handle409Conflict` (`:180`) does `throw retryError` instead of returning `OperationOutcome.RETRY` (RP-CD-004) — absorbed by the processor `runCatching` so no queue stall; contract-hygiene only. Legacy code superseded by RP-FR-019; deprioritize if legacy equipment is being retired | functional | pre_existing_latent | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-FR-025](investigations/RP-FR-025_legacy_equipment_pushhandler_throws_2026-07-17.md) · [plan](plans/plan_rp_fr_025_legacy_pushhandler_outcome_2026-07-17.md) |
| `RP-FR-026` | P2 | — | RP-FR-019 has no **company serialized-asset pool screen** — Android only shows the per-room asset list (`SerializedRoomEquipmentFragment`), where the "available" pool is scoped to what can be deployed into the current room. There is no standalone, paginated company-wide pool (browse/search all units across statuses `available/deployed/maintenance/retired`, register from the pool). The `GET /companies/{c}/equipment-assets` endpoint is already wired for reads. iOS ships this as `SerializedEquipmentContentView` (paginated, pull-to-refresh, load-more, offline cache) | feature | new_feature | iOS parity review 2026-07-18 | `feat/RP-FR-019-serialized-equipment` | fixed | unreleased | — | [RP-FR-026](investigations/RP-FR-026_no_company_asset_pool_screen_2026-07-18.md) · iOS `SerializedEquipmentContentView` (RP-BUG-344) · parent [RP-FR-019](plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md) · [plan](plans/plan_rp_fr_026_company_asset_pool_2026-07-18.md) |
| `RP-FR-027` | P3 | — | RP-FR-019 has no **serialized asset detail screen** — Android exposes lifecycle actions inline on the room list rows but no dedicated per-unit detail view showing full metadata (manufacturer/model/serial/asset_tag/purchase/vendor/warranty/rental fields already in the DTO), current placement, and all lifecycle actions in one place with 409-stale reload. iOS ships `SerializedAssetDetailView` | feature | new_feature | iOS parity review 2026-07-18 | feat/RP-FR-019-serialized-equipment | fixed | unreleased | — | [RP-FR-027](investigations/RP-FR-027_no_asset_detail_screen_2026-07-18.md) · iOS `SerializedAssetDetailView` (RP-BUG-344) · parent [RP-FR-019](plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md) · [plan](plans/plan_rp_fr_027_asset_detail_screen_2026-07-18.md) |
| `RP-FR-028` | P3 | — | RP-FR-019 has no **placement-history UI** — the `GET /equipment-assets/{id}/placements` endpoint + `EquipmentAssetPlacementDto` are wired, but no screen renders a unit's deploy→move→check-out timeline (which rooms/projects, date_in/date_out, open vs closed). Users cannot see where a unit has been. iOS ships `SerializedPlacementHistoryView` | feature | new_feature | iOS parity review 2026-07-18 | feat/RP-FR-019-serialized-equipment | fixed | unreleased | — | [RP-FR-028](investigations/RP-FR-028_no_placement_history_ui_2026-07-18.md) · iOS `SerializedPlacementHistoryView` (RP-BUG-344) · parent [RP-FR-019](plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md) · [plan](plans/plan_rp_fr_028_placement_history_ui_2026-07-18.md) |
| `RP-FR-029` | P2 | — | RP-FR-019 serialized asset **edit/update is unreachable** — `EquipmentAssetPushHandler` supports the UPDATE op (`OfflineSyncApi.updateEquipmentAsset` / `toUpdateRequest` w/ optimistic-lock), but there is **no repo entry point** (`OfflineSyncRepository` exposes only register/deploy/retire/move/checkOut, no `updateEquipmentAssetOffline`) and **no UI** to invoke it. Once a unit is registered its serial_number/asset_tag/status(available↔maintenance)/note/metadata can never be corrected on Android. iOS ships `SerializedEditView` | feature | new_feature | iOS parity review 2026-07-18 | feat/RP-FR-019-serialized-equipment | fixed | unreleased | — | [RP-FR-029](investigations/RP-FR-029_asset_edit_unreachable_2026-07-18.md) · iOS `SerializedEditView` (RP-BUG-344) · parent [RP-FR-019](plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md) · [plan](plans/plan_rp_fr_029_asset_edit_2026-07-18.md) |
| `RP-FR-030` | P3 | — | RP-FR-019 has no **placement date-correction** — Android cannot fix a wrong `date_in`/`date_out` on a placement (no endpoint wired, no UI). iOS added the backend `PATCH /api/equipment-asset-placements/{id}` + `SerializedPlacementEditView` (UTC date-only correction, RP-BUG-345 fixed an off-by-one). Verify the endpoint exists on the current backend spec before wiring (API Contract Discipline) | feature | new_feature | iOS parity review 2026-07-18 | — | planned | unreleased | — | [RP-FR-030](investigations/RP-FR-030_no_placement_date_correction_2026-07-18.md) · iOS `SerializedPlacementEditView` / `correctPlacement` · parent [RP-FR-019](plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md) · [plan](plans/plan_rp_fr_030_placement_date_correction_2026-07-18.md) |
| `RP-FR-031` | P3 | — | RP-FR-019 has no **placement delete** — Android cannot remove an erroneous placement record (no endpoint wired, no UI). iOS added the backend `DELETE /api/equipment-asset-placements/{id}` + swipe-to-delete in the history view. Verify the endpoint exists on the current backend spec before wiring (API Contract Discipline) | feature | new_feature | iOS parity review 2026-07-18 | — | planned | unreleased | — | [RP-FR-031](investigations/RP-FR-031_no_placement_delete_2026-07-18.md) · iOS `deletePlacement` (RP-BUG-344) · parent [RP-FR-019](plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md) · [plan](plans/plan_rp_fr_031_placement_delete_2026-07-18.md) |
| `RP-HD-001` | P2 | — | Audit every codebase touchpoint against every RP-CD rule | hardening | pre_existing_latent | 1.0.00 | — | closed | n/a | — | [RP-HD-001](investigations/RP-HD-001_rp_cd_rule_audit.md) |
| `RP-HD-002` | P2 | — | Guard SecureStorage migration body against non-cancellation exceptions | hardening | new_code_bug | 1.0.00 | — | fixed | unreleased | — | [RP-HD-002](investigations/RP-HD-002_secure_storage_migration_exception_guard.md) · [plan](plans/plan_rp_hd_002_003_secure_storage_2026-05-18.md) · [review](reviews/code_review_RP-HD-002_003_2026-05-18.md) |
| `RP-HD-003` | P3 | — | Document ordering of legacy-token clear vs encrypted save in SecureStorage | hardening | new_code_bug | 1.0.00 | — | fixed | unreleased | — | [RP-HD-003](investigations/RP-HD-003_secure_storage_clear_save_ordering.md) · [plan](plans/plan_rp_hd_002_003_secure_storage_2026-05-18.md) · [review](reviews/code_review_RP-HD-002_003_2026-05-18.md) |
| `RP-HD-004` | P2 | — | Add unit-test coverage for the 2026-06 sync-fix batch (RP-BUG-029..035, RP-FR-003/004, RP-BUG-031) | hardening | pre_existing_latent | 1.29 (32) | 1.29 (32) | fixed | unreleased | — | [RP-HD-004](investigations/RP-HD-004_test_coverage_sync_fix_batch.md) |
| `RP-BUG-269` | P1 | — | Company activation (refreshUserContext → setActiveCompany, POST /api/active-company) is not gated behind SMS verification — client has no sms_verified_at awareness, so an unverified user with a company fires the call on every login/startup, backend 403s "sms code is not verified", and unauthorizedInterceptor force-signs-out the user (iOS RP-BUG-330 counterpart) | functional | pre_existing_latent | 1.30 (35) | 90078ec | fixed | unreleased | — | [RP-BUG-269](investigations/RP-BUG-269_company_activation_not_gated_behind_sms_verification_2026-06-10.md) · [plan](plans/plan_rp_bug_269_sms_gate_company_activation_2026-06-10.md) · [review](reviews/code_review_rp_bug_269_270_2026-06-10.md) |
| `RP-BUG-270` | P2 | — | Invite-based company joining is not implemented on Android — no invite-redirect deep-link handler (manifest only registers oauth2), onNewIntent handles only OAuth, JoinCompanyViewModel is an empty stub, "Join Company" offers only "Create instead"; an invited user cannot join the company (iOS RP-BUG-327/328 area — feature gap, not the iOS parser defect) | functional | feature_gap | 1.30 (35) | 90078ec (in-app join; email-link auto-open gated on assetlinks.json) | fixed | unreleased | — | [RP-BUG-270](investigations/RP-BUG-270_invite_based_company_join_not_implemented_2026-06-10.md) · [plan](plans/plan_rp_bug_270_invite_company_join_2026-06-10.md) · [review](reviews/code_review_rp_bug_269_270_2026-06-10.md) |
| `RP-BUG-271` | P1 | — | Post-verify routing keys on uncached companyId — RP-BUG-269 gating never caches companyId for an unverified user and verify() never re-runs refreshUserContext, so the "skip Create/Join chooser" branch is dead and an existing-company user is dumped into the chooser after SMS verify (iOS RP-BUG-331/333) | functional | new_code_bug | 1.30 (35) | 6634854 | fixed | unreleased | RP-BUG-269 | [RP-BUG-271](investigations/RP-BUG-271_post_verify_routing_skips_existing_company_2026-06-10.md) · [plan](plans/plan_rp_bug_271_post_verify_routing_2026-06-11.md) · [review](reviews/code_review_rp_bug_269_270_2026-06-10.md) |
| `RP-BUG-272` | P2 | — | AccountType screen does not resolve a pending invite (no checkForInvitiation equivalent); invite auto-join lives only in MainActivity at launch, so an invited user going through fresh signup sees Create/Join instead of auto-joining (iOS RP-BUG-328) | functional | feature_gap | 1.30 (35) | 6634854 | fixed | unreleased | — | [RP-BUG-272](investigations/RP-BUG-272_account_type_does_not_resolve_pending_invite_2026-06-10.md) · [plan](plans/plan_rp_bug_272_accounttype_resolve_invite_2026-06-11.md) |
| `RP-BUG-273` | P2 | — | No logout/exit on authenticated signup screens (phoneVerification/smsCodeVerify/accountType/joinCompany/finalDetails); checkAuthenticationStatus pops emailCheck inclusive so a force-routed user has no back stack and is stranded, can't sign in as someone else (iOS RP-BUG-329) | functional | pre_existing_worsened | 1.30 (35) | 6634854 | fixed | unreleased | RP-BUG-269 | [RP-BUG-273](investigations/RP-BUG-273_no_logout_on_signup_screens_strands_user_2026-06-10.md) · [plan](plans/plan_rp_bug_273_logout_on_signup_screens_2026-06-11.md) |
| `RP-BUG-274` | P3 | — | No SMS-403 retry / in-session verified flag — RetrofitClient only exempts the stale post-verify 403 from sign-out (no retry), so a just-verified user's setActiveCompany 403s inside runCatching and lands company-less until a later refresh (iOS RP-BUG-333; backend MONGOOSE-BUG-028) | functional | pre_existing_latent | 1.30 (35) | 6634854 | fixed | unreleased | — | [RP-BUG-274](investigations/RP-BUG-274_no_sms_403_retry_after_verify_2026-06-10.md) · [plan](plans/plan_rp_bug_274_sms_403_retry_2026-06-11.md) |
| `RP-BUG-275` | P3 | — | Launch-time invite auto-join ignores addCompanyUser/setActiveCompany Results and clears the pending invite on resolve-success regardless, so a failed join is silently dropped (no retry) and the user is navigated to projects without membership | functional | new_code_bug | 1.30 (35) | 6634854 | fixed | unreleased | RP-BUG-270 | [RP-BUG-275](investigations/RP-BUG-275_invite_autojoin_swallows_failures_2026-06-10.md) · [plan](plans/plan_rp_bug_275_invite_autojoin_failures_2026-06-11.md) |
| `RP-BUG-276` | P3 | — | No company-approval gate — Android Company has no isApproved and MainActivity routes to projects whenever companyId is set, so a member of an unapproved company enters the app instead of a pending-approval screen (iOS .companyApproval / WelcomeBack) | functional | feature_gap | 1.30 (35) | — | planned | unreleased | — | [RP-BUG-276](investigations/RP-BUG-276_no_company_approval_gate_2026-06-10.md) · [plan](plans/plan_rp_bug_276_company_approval_gate_2026-06-11.md) |
| `RP-BUG-277` | P3 | — | Emailed https invite link does not auto-open the app — manifest filters are autoVerify=false and no /.well-known/assetlinks.json is published, so only in-app paste + custom-scheme links reach InviteLink.parse (RP-BUG-270 follow-up; needs backend/ops) | functional | feature_gap | 1.30 (35) | — | planned | unreleased | — | [RP-BUG-277](investigations/RP-BUG-277_emailed_invite_link_autoopen_not_wired_2026-06-10.md) · [plan](plans/plan_rp_bug_277_invite_applinks_2026-06-11.md) |
| `RP-BUG-278` | P3 | — | Google OAuth sign-in navigates straight to nav_home, bypassing the SMS and company gates for that session (gates only run in MainActivity at cold start), so an unverified/company-less OAuth user briefly enters the app until relaunch | functional | pre_existing_latent | 1.30 (35) | 6634854 | fixed | unreleased | — | [RP-BUG-278](investigations/RP-BUG-278_oauth_signin_bypasses_sms_company_gates_2026-06-10.md) · [plan](plans/plan_rp_bug_278_oauth_gate_routing_2026-06-11.md) |
| `RP-BUG-279` | P1 | — | Offline equipment write-sync targets non-existent backend routes (PUT/DELETE /api/equipment/{id}) — creates/edits/deletes silently dropped (404→recreate→duplicate-name 422→drop; delete 404→no-op) and never round-trip to the server's equipment_room placement model, so iOS/webapp never see Android's equipment; must adopt the pivot endpoints (attach via POST rooms/{r}/equipment with `equipment_ids:[…]`, update/delete via `equipment-rooms/{pivotId}`, read via GET rooms/{r}/equipment). Implementation merged in `9f6b540` was **reverted** (`dea4ff3`) because it targeted an abandoned backend contract (sent `equipment:[…]`, not `equipment_ids:[…]`). RP-FR-019 built the serialized subsystem but did **NOT** fix this: for flag-OFF companies the legacy write path (`EquipmentPushHandler` → `OfflineSyncApi.updateEquipment`/`deleteEquipment`) still targets the non-existent `PUT`/`DELETE /api/equipment/{id}`, so legacy equipment writes still silently fail. Needs its own fix (attach via POST rooms/{r}/equipment `equipment_ids:[…]`, update/delete via `equipment-rooms/{pivotId}`) — independent of RP-FR-019. **Backend prerequisite: `mongoose:MONGOOSE-BUG-036`**. **FIXED 2026-07-17**: read path now uses `getRoomEquipment` per room (not `getProjectEquipment`); migration 31_32 neutralizes stale serverIds (copies to catalogServerId, nulls serverId); `resolveOrMintCatalog` returns catalogServerId; `upsertEquipmentOffline` preserves catalogServerId/catalogUuid; `AttachRoomEquipmentRequest` now sends quantity | functional | new_code_bug | 1.30 (35) | — | fixed | unreleased | — | [RP-BUG-279](investigations/RP-BUG-279_legacy_equipment_write_sync_dead_routes_2026-07-17.md) · [plan](plans/plan_rp_bug_279_equipment_writesync_pivot_realignment_2026-07-09.md) · prior impl reverted in `dea4ff3` (archive `backup/android-count-equipment-move-transfer-2026-07-13`) · **NOT fixed by RP-FR-019 (descoped)** · backend `mongoose:MONGOOSE-FR-014` §8 · OfflineSyncApi.kt:490,496 |
| `RP-HD-005` | P3 | — | Auth/signup gate decisions in MainActivity are local-logcat only (Log.d), not remote log_entries, so a user's signup routing can't be traced in the wild (iOS logs auth_sms/auth_company/auth_recheck remotely) — observability hardening | functional | pre_existing_latent | 1.30 (35) | ddea51d | fixed | unreleased | — | [RP-HD-005](investigations/RP-HD-005_gate_decisions_not_remote_logged_2026-06-10.md) · [plan](plans/plan_rp_hd_005_remote_log_auth_gates_2026-06-11.md) |
| `RP-HD-006` | P3 | — | CurrentUserResponse does not model email_verified_at (iOS carries User.emailVerifiedAt); not a gate today — preventive parity so a future email-verification gate isn't silently blind | functional | feature_gap | 1.30 (35) | 6634854 | fixed | unreleased | — | [RP-HD-006](investigations/RP-HD-006_email_verified_at_not_modeled_2026-06-10.md) · [plan](plans/plan_rp_hd_006_email_verified_at_2026-06-11.md) |
| `RP-HD-007` | P3 | — | No unit tests for InviteLink.parse and JoinCompanyViewModel (RP-BUG-270 follow-up); the parser is the exact surface iOS regressed on (RP-BUG-327) — test debt | functional | pre_existing_latent | 1.30 (35) | 3dff3e0 | fixed | unreleased | — | [RP-HD-007](investigations/RP-HD-007_invitelink_joincompany_unit_tests_missing_2026-06-10.md) · [plan](plans/plan_rp_hd_007_invitelink_join_tests_2026-06-11.md) |
| `RP-HD-008` | P2 | — | RP-FR-019 serialized-equipment error/swallow paths not remote-logged: pull `refreshRoom` (destructive reconcile) swallows all failures with zero logging; VM `runAction`, generic push-handler RETRY branches, and `fetchEquipmentCatalog` log locally or not at all — sync/pull/UI failures invisible in the wild (RP-CD-019; parity with RP-HD-005) | functional | new_code_bug | feat/RP-FR-019 (35-dev) | — | planned | unreleased | — | [RP-HD-008](investigations/RP-HD-008_equipment_remote_logging_gaps_2026-07-17.md) · [plan](plans/plan_rp_hd_008_equipment_remote_logging_2026-07-17.md) |
| `RP-BUG-334` | P2 | — | RP-FR-019 placement pull (`reconcileAssetPlacements`) closes ALL clean placements incl. the open one on a spurious empty `{data:[]}` for a deployed asset — deployment vanishes from the room until next good pull; `pullCompanyPool` completeness guard was never applied here (RP-CD-016) | functional | new_code_bug | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-BUG-334](investigations/RP-BUG-334_placement_pull_wipes_open_placement_2026-07-17.md) · [plan](plans/plan_rp_bug_334_placement_pull_completeness_guard_2026-07-17.md) |
| `RP-BUG-335` | P3 | — | RP-FR-019 deploy/move/check-out success write uses `preserveDirty=false` (contradicts its own comment) — a concurrent metadata edit (rename/serial) during the sync window is clobbered + the queued update op then pushes clobbered values, so the edit is silently lost (RP-CD-002) | functional | new_code_bug | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-BUG-335](investigations/RP-BUG-335_lifecycle_success_clobbers_concurrent_metadata_edit_2026-07-17.md) · [plan](plans/plan_rp_bug_335_lifecycle_preserve_dirty_2026-07-17.md) |
| `RP-BUG-336` | P3 | — | RP-FR-019 duplicate asset row when a register response is lost: register sends no `uuid` (server mints own), pull reconciles by `serverId` only, so a pull before the register op reconciles inserts a 2nd row (RP-BUG-036/037/048 signature; RP-CD-014/018) | functional | new_code_bug | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-BUG-336](investigations/RP-BUG-336_duplicate_asset_row_lost_register_response_2026-07-17.md) · [plan](plans/plan_rp_bug_336_register_natural_key_adopt_2026-07-17.md) |
| `RP-BUG-337` | P3 | — | RP-FR-019 move sends the original deploy time as `moved_at` (`toMoveRequest` reuses `dateIn`), so the moved asset's new-room placement `date_in` is the old deploy time — room-occupancy/drying-duration reports wrong for moved units | functional | new_code_bug | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-BUG-337](investigations/RP-BUG-337_move_sends_deploy_time_not_move_time_2026-07-17.md) · [plan](plans/plan_rp_bug_337_move_timestamp_2026-07-17.md) |
| `RP-BUG-338` | P3 | — | RP-FR-019 flag rollback (ON→OFF mid-use) drops the user to the RocketDry project screen not the legacy equipment screen (`popBackStack` + forward action's `popUpToInclusive=true`); + move dialog lists the current room as a guaranteed-fail choice | ui_bug | new_code_bug | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-BUG-338](investigations/RP-BUG-338_flag_rollback_wrong_screen_2026-07-17.md) · [plan](plans/plan_rp_bug_338_rollback_nav_2026-07-17.md) |
| `RP-BUG-339` | P3 | — | RP-FR-019 `SerializedRoomEquipmentViewModel.resolve()` has no error boundary — a thrown Room read before the first `Ready` leaves `_uiState` on `Loading` forever (permanent spinner, no retry) | ui_bug | new_code_bug | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-BUG-339](investigations/RP-BUG-339_serialized_resolve_uncaught_throw_spinner_2026-07-17.md) · [plan](plans/plan_rp_bug_339_resolve_error_boundary_2026-07-17.md) |
| `RP-HD-009` | P3 | — | RP-FR-019 `pullCompanyPool` uses `while(true)` advancing by the server-echoed `currentPage` with no iteration cap — a misbehaving server (fixed `currentPage < lastPage`) loops forever; preventive bound + advance-by-requested-page | functional | new_code_bug | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | — | [RP-HD-009](investigations/RP-HD-009_pull_company_pool_unbounded_loop_2026-07-17.md) · [plan](plans/plan_rp_hd_009_pool_pagination_cap_2026-07-17.md) |
| `RP-BUG-340` | P3 | — | RP-BUG-336 natural-key adoption double-adopts one local row when ≥2 identical un-serialized assets share key `(companyId, catalogUuid, name)` — every matching server row adopts the SAME local row → duplicate `assetId` in the upsert list → REPLACE drops a server asset (one identical unit vanishes locally). Consume-on-adopt fix + hoist the per-server DB query | functional | regression | feat/RP-FR-019 (35-dev) | — | fixed | unreleased | RP-BUG-336 | [RP-BUG-340](investigations/RP-BUG-340_natural_key_double_adoption_collision_2026-07-17.md) · [plan](plans/plan_rp_bug_340_natural_key_consume_2026-07-17.md) |

---

## Code Review Standards

### When to Request Review

- Every fix plan must have a code review before merge
- Reviews are linked from the bug tracker and from the plan doc itself
- For multi-bug releases, use a **bundled review** covering all related changes

### Review Doc Template

```md
# Code Review: [RP-BUG-XXX] Short Title

**Bug ID(s):** RP-BUG-XXX
**Plan:** [plan doc link]
**Reviewer:** [name]
**Date:** YYYY-MM-DD
**Build:** XXXX

## Summary

## Findings

### Must Fix

### Should Fix

### Consider

### Verified Safe

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | | |
```

---

## Related Documents

- `docs/architecture/ARCHITECTURE.md` — architectural decisions and invariants
- `docs/architecture/RP-CD_rules.md` — coding rules (`RP-CD-###`) cited by `RP-FR-###` bugs
- `docs/issues/` — cross-bug relationship analysis
- `docs/plans/` — fix implementation plans
- `docs/reviews/` — code review documents
