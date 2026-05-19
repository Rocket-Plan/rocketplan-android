**Bug ID:** RP-BUG-025
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-025_current_company_id_npe.md) · [Review](../reviews/code_review_full_2026-05-18.md)

# Fix Plan: [RP-BUG-025] Remove throwing company-context footgun

**Bug ID(s):** RP-BUG-025
**Author:** jeremie.blais@jot.digital
**Date:** 2026-05-18
**State:** approved

---

## Summary

`LocalDataService.currentCompanyId` throws when company context has not been initialized. The codebase already has a safer nullable accessor (`currentCompanyIdOrNull`), and current sync code is using that safer path. The fix is to remove the throwing access pattern as a public footgun and make "missing company context" an explicit, recoverable branch for any future callers.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/local/LocalDataService.kt` | Deprecate or remove the throwing accessor; expose a single nullable/read-result API for company context. |
| `app/src/main/java/com/example/rocketplan_android/data/repository/OfflineSyncRepository.kt` | Keep the existing null-guard path and tighten messaging around skipped sync when company context is absent. |
| `app/src/test/java/com/example/rocketplan_android/data/repository/OfflineSyncRepositoryTest.kt` | Add regression coverage for "no company context" behavior. |

## Implementation Notes

### Step 1: Make the API impossible to misuse

Replace the dual-accessor pattern with one of these shapes:

- preferred: keep only `currentCompanyIdOrNull`
- acceptable: deprecate `currentCompanyId` with `DeprecationLevel.ERROR` and migrate any future call sites away from it

The goal is to prevent new code from depending on an exception for control flow.

### Step 2: Preserve graceful sync behavior

`OfflineSyncRepository.syncProjectEssentials()` already returns `SyncResult.incomplete(...NO_COMPANY_CONTEXT...)` when company context is missing. Keep that contract and use it as the model for other sync entry points that may need company scoping later.

### Step 3: Clarify lifecycle expectations

Document in `LocalDataService` that:

- company context is session state
- it is set after login / company selection
- it must be treated as nullable during startup, logout, or relogin transitions

## Observability

- Keep the existing warn-level local log in `syncProjectEssentials()` when sync is skipped for missing company context.
- If any new logging is added, keep it console-only unless QA shows this path is occurring unexpectedly in production.

## Test Plan

- [ ] Unit test: sync entry points that require company context return a recoverable result when the value is unset.
- [ ] Unit test: no code path depends on `IllegalStateException` from `LocalDataService` for normal startup/logout sequencing.
- [ ] Static verification: search the app module for direct uses of the throwing accessor and confirm none remain.

## Rollback Plan

Reintroduce the throwing accessor and revert the call-site updates. No schema or persisted-data changes are involved.

## Dependencies

- Requires: none
- Blocking: none

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-025] Removed a crash-prone company-context accessor so pre-login sync paths fail gracefully instead of throwing.
```

## Post-implementation Review Notes

- **Review:** [code_review_rp_bug_024_027_2026-05-18.md](../reviews/code_review_rp_bug_024_027_2026-05-18.md)
- **Status:** already effectively fixed and validated

### Follow-up Required

- No bug-specific blockers remain.
