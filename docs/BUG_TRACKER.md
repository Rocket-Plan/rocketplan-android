# RocketPlan Bug Tracker

> **Single source of truth for all user-facing bugs, crashes, hangs, and functional defects.**
> Every investigation doc, plan, and review must link back here.
> Investigation docs carry YAML front matter that mirrors the fields below.

---

## Shipping Status

| Version | Build | Status |
|---------|-------|--------|
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

**ID Conventions** — prefix is chosen by **failure concreteness**, not by where the issue was discovered. (Discovery is captured in `Source`; evidence strength is captured in `Evidence` — see below.)

- `ROCKET-PLAN-ANDROID-*` — Sentry short ID. Use when there's a crash event regardless of how concrete the failure is. Sentry IDs always win the prefix lottery.
- `RP-BUG-###` — **Concrete defect.** A plausible user-visible failure mode exists with a symptom that can be described or a reproducer that can be written, even if not yet observed in Sentry. The investigation doc has a "Symptom" section that names what the user sees.
- `RP-FR-###` — **Architectural / rule violation.** A code-shape concern surfaced by review that violates a documented rule (`RP-CD-###`) but has no demonstrated user-visible failure path today. User impact is contingent on other code changing or on a separate latent bug firing first. Tracked because the code drifts toward risk, not because it's broken now.
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
| `RP-BUG-043` | P2 | — | pendingPhotoSyncs leaks when the CONTENT_ONLY follow-up is coalesced/dropped by enqueue (key ignores mode) — room-card spinner hangs forever and the project's photos never download | hang | pre_existing_latent | On-device DB trace 2026-06-07 | 1.0.00 | fixed | unreleased | — | [RP-BUG-043](investigations/RP-BUG-043_pending_photo_syncs_leak_stuck_spinner.md) · [plan](plans/plan_rp_bug_043_pending_photo_syncs_leak_2026-06-07.md) · [review](reviews/code_review_rp_bug_043_2026-06-07.md) · [test](../app/src/test/java/com/example/rocketplan_android/data/sync/SyncQueueManagerPhotoSyncFlagTest.kt) |
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
| `RP-HD-001` | P2 | — | Audit every codebase touchpoint against every RP-CD rule | hardening | pre_existing_latent | 1.0.00 | — | closed | n/a | — | [RP-HD-001](investigations/RP-HD-001_rp_cd_rule_audit.md) |
| `RP-HD-002` | P2 | — | Guard SecureStorage migration body against non-cancellation exceptions | hardening | new_code_bug | 1.0.00 | — | fixed | unreleased | — | [RP-HD-002](investigations/RP-HD-002_secure_storage_migration_exception_guard.md) · [plan](plans/plan_rp_hd_002_003_secure_storage_2026-05-18.md) · [review](reviews/code_review_RP-HD-002_003_2026-05-18.md) |
| `RP-HD-003` | P3 | — | Document ordering of legacy-token clear vs encrypted save in SecureStorage | hardening | new_code_bug | 1.0.00 | — | fixed | unreleased | — | [RP-HD-003](investigations/RP-HD-003_secure_storage_clear_save_ordering.md) · [plan](plans/plan_rp_hd_002_003_secure_storage_2026-05-18.md) · [review](reviews/code_review_RP-HD-002_003_2026-05-18.md) |
| `RP-HD-004` | P2 | — | Add unit-test coverage for the 2026-06 sync-fix batch (RP-BUG-029..035, RP-FR-003/004, RP-BUG-031) | hardening | pre_existing_latent | 1.29 (32) | 1.29 (32) | fixed | unreleased | — | [RP-HD-004](investigations/RP-HD-004_test_coverage_sync_fix_batch.md) |

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
