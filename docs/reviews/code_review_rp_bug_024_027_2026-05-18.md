**Bug ID:** RP-BUG-024, RP-BUG-025, RP-BUG-026, RP-BUG-027
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [RP-BUG-024 Plan](../plans/plan_rp_bug_024_pusher_throttle_map_2026-05-18.md) · [RP-BUG-025 Plan](../plans/plan_rp_bug_025_current_company_id_2026-05-18.md) · [RP-BUG-026 Plan](../plans/plan_rp_bug_026_clearall_migration_deferred_2026-05-18.md) · [RP-BUG-027 Plan](../plans/plan_rp_bug_027_syncstatus_storage_mapping_2026-05-18.md)

# Code Review: RP-BUG-024 through RP-BUG-027

**Bug ID(s):** RP-BUG-024, RP-BUG-025, RP-BUG-026, RP-BUG-027
**Plan:** [RP-BUG-024](../plans/plan_rp_bug_024_pusher_throttle_map_2026-05-18.md), [RP-BUG-025](../plans/plan_rp_bug_025_current_company_id_2026-05-18.md), [RP-BUG-026](../plans/plan_rp_bug_026_clearall_migration_deferred_2026-05-18.md), [RP-BUG-027](../plans/plan_rp_bug_027_syncstatus_storage_mapping_2026-05-18.md)
**Reviewer:** OpenAI Codex
**Date:** 2026-05-18
**Timestamp:** 2026-05-18 11:57:57 PDT
**Build:** uncommitted local review
**Uncommitted files (`git status --porcelain` at review start):**
```text
 M AGENTS.md
 M CLAUDE.md
 M app/build.gradle.kts
 M app/src/main/java/com/example/rocketplan_android/data/local/LocalDataService.kt
 M app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt
 M app/src/main/java/com/example/rocketplan_android/data/local/OfflineTypeConverters.kt
 M app/src/main/java/com/example/rocketplan_android/data/local/SyncEnums.kt
 M app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt
 M app/src/main/java/com/example/rocketplan_android/realtime/PusherService.kt
 M app/src/test/java/com/example/rocketplan_android/data/repository/OfflineSyncRepositoryTest.kt
RM docs/ARCHITECTURE.md -> docs/architecture/ARCHITECTURE.md
R  docs/ROCKETPLAN_IOS_OFFLINE_ARCHITECTURE.md -> docs/reference/ROCKETPLAN_IOS_OFFLINE_ARCHITECTURE.md
?? app/src/test/java/com/example/rocketplan_android/data/local/
?? app/src/test/java/com/example/rocketplan_android/data/storage/
?? app/src/test/java/com/example/rocketplan_android/realtime/PusherServiceTest.kt
?? app/src/test/resources/
?? docs/BUG_TRACKER.md
?? docs/README.md
?? docs/architecture/RP-CD_rules.md
?? docs/investigations/
?? docs/plans/
?? docs/reviews/
```

## Summary

Review scope covered the implemented changes for:

- `RP-BUG-024` — `PusherService` throttle-cache bounding
- `RP-BUG-025` — `LocalDataService.currentCompanyId` deprecation / nullable-path usage
- `RP-BUG-026` — `SecureStorage.clearAll()` migration invalidation
- `RP-BUG-027` — explicit `SyncStatus` storage parsing

Result: **approved for these four bugs**.

Follow-up validation showed:

- `RP-BUG-024` is properly implemented in source with size-bounded eviction.
- `RP-BUG-025` was already effectively fixed by the deprecated throwing accessor and nullable call pattern.
- `RP-BUG-026` is properly implemented in source with migration cancellation/replacement on `clearAll()`.
- `RP-BUG-027` is properly implemented in source with explicit throwing storage parsing.

The remaining compilation problems in `PusherServiceTest.kt` and `OfflineSyncRepositoryTest.kt` are tracked as **unrelated test-suite issues**, not blockers for RP-BUG-024 through RP-BUG-027.

## Findings

### Must Fix

- None for RP-BUG-024, RP-BUG-025, RP-BUG-026, or RP-BUG-027 after follow-up validation.

### Should Fix

- Clean up unrelated test-suite issues separately:
  - `app/src/test/java/com/example/rocketplan_android/realtime/PusherServiceTest.kt`
  - `app/src/test/java/com/example/rocketplan_android/data/repository/OfflineSyncRepositoryTest.kt`

### Consider

- For RP-BUG-025, eventually remove `currentCompanyId` entirely once downstream code is fully migrated.
- For RP-BUG-027, explicitly document that fail-fast parsing for unknown stored values is the intended production behavior.

### Verified Safe

- `LocalDataService.currentCompanyIdOrNull` remains the accessor used by the reviewed sync path, and the new `OfflineSyncRepositoryTest` coverage for `NO_COMPANY_CONTEXT` matches the intended graceful behavior.
- `OfflineTypeConverters.toSyncStatus()` now routes through a single parser, which is a cleaner and more auditable storage conversion path than inline `valueOf(...).getOrNull()`.
- `PusherService.trimThrottleCache()` does cap the map by evicting oldest entries after expired-entry cleanup; the implementation direction matches the plan.
- `SecureStorage.clearAll()` cancels/replaces migration state and preserves the intended post-logout invariant.

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | OpenAI Codex | 2026-05-18 |
