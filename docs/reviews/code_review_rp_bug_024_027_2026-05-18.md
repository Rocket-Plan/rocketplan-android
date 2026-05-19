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

Result: **not approved yet**. RP-BUG-025 and RP-BUG-027 look directionally correct, but there are two blocking issues: one functional regression in `SecureStorage`, and one test-suite/verification gap in the new `PusherServiceTest`.

## Findings

### Must Fix

1. **RP-BUG-026 regression: legacy token migration never starts anymore**
   - File: `app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt:109-149`
   - `migrationDeferred` now starts as `null`, `initMigrationDeferred()` is never called, and `getAuthTokenSync()` falls back to `scope.async { null }`.
   - Effect: cold-start callers no longer await or execute legacy token migration at all, so users with only the old DataStore token can observe `null` permanently instead of getting migrated auth state.
   - This is worse than the original bug because it breaks the migration path entirely.

2. **RP-BUG-024 tests do not validate the implementation because the new test file does not compile as written**
   - File: `app/src/test/java/com/example/rocketplan_android/realtime/PusherServiceTest.kt`
   - The test directly calls `service.shouldThrottleRemoteLog(...)`, but that method is `private` in `PusherService` (`app/src/main/java/com/example/rocketplan_android/realtime/PusherService.kt:524`).
   - The test also uses `ConcurrentHashMap<String, Long>` without importing it, and the eviction assertions build the wrong key format for `code = null` / `exceptionMessage = null` (production stores `none|none`, not `code_i|exception_i`).
   - Until fixed, the intended RP-BUG-024 regression coverage is not actually present.

### Should Fix

- `SecureStorage.getAuthTokenSync()` writes `migrationDeferred = it` outside the mutex in the fallback branch. Even after the missing initialization is fixed, keep creation/publication of the deferred under the same lock so two callers cannot race and install different one-shot tasks.

### Consider

- For RP-BUG-025, after deprecating `currentCompanyId`, consider eventually removing it entirely once downstream code is migrated. `DeprecationLevel.ERROR` is a good short-term guard.
- For RP-BUG-027, decide explicitly whether fail-fast-on-unknown is desired in production or only in debug/test. The current implementation throws everywhere, which may be acceptable, but it should be a deliberate product decision.

### Verified Safe

- `LocalDataService.currentCompanyIdOrNull` remains the accessor used by the reviewed sync path, and the new `OfflineSyncRepositoryTest` coverage for `NO_COMPANY_CONTEXT` matches the intended graceful behavior.
- `OfflineTypeConverters.toSyncStatus()` now routes through a single parser, which is a cleaner and more auditable storage conversion path than inline `valueOf(...).getOrNull()`.
- `PusherService.trimThrottleCache()` does cap the map by evicting oldest entries after expired-entry cleanup; the implementation direction matches the plan.

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | OpenAI Codex | 2026-05-18 |
