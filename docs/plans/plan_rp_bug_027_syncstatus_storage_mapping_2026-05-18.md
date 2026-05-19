**Bug ID:** RP-BUG-027
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-027_syncstatus_string_parse.md) · [Review](../reviews/code_review_full_2026-05-18.md)

# Fix Plan: [RP-BUG-027] Make SyncStatus storage parsing explicit

**Bug ID(s):** RP-BUG-027
**Author:** jeremie.blais@jot.digital
**Date:** 2026-05-18
**State:** approved

---

## Summary

`OfflineTypeConverters.toSyncStatus()` currently uses `runCatching { SyncStatus.valueOf(it) }.getOrNull()`. That silently collapses unknown database values to `null`, which makes enum drift harder to detect and can fail unpredictably at the Room boundary. Fix by centralizing SyncStatus parsing into an explicit policy: either fail fast with a clear message or map to a deliberate fallback state, but never silently swallow an unknown stored value.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/local/SyncEnums.kt` | Add a single parser/adapter entry point for storage values. |
| `app/src/main/java/com/example/rocketplan_android/data/local/OfflineTypeConverters.kt` | Replace inline `valueOf(...).getOrNull()` parsing with the centralized policy. |
| `app/src/test/java/com/example/rocketplan_android/data/local/OfflineTypeConvertersTest.kt` | Add coverage for valid, invalid, and future/unknown storage values. |

## Implementation Notes

### Step 1: Centralize parsing

Add a companion/parser on `SyncStatus`, for example:

- `fromStorageValue(value: String): SyncStatus`

This becomes the only supported path for converting database strings into enum values.

### Step 2: Choose an explicit unknown-value policy

Preferred policy for this codebase:

- throw a descriptive `IllegalArgumentException` for unknown values in debug/test
- log clearly and map to a deliberate fallback only if production resilience requires it

The important change is that unknown values become visible and intentional instead of disappearing behind `null`.

### Step 3: Audit downstream assumptions

If a fallback enum such as `FAILED` or a new `UNKNOWN` state is chosen, audit `when (syncStatus)` branches in UI and sync code so the new state cannot be dropped on the floor. If fail-fast is chosen, make sure the exception message identifies the bad stored value.

## Observability

- If fallback behavior is used, add a throttled warning with the unknown raw value and the converter path.
- If fail-fast behavior is used, ensure the thrown message is explicit enough to diagnose the bad DB state from logs/crash reports.

## Test Plan

- [ ] Unit test: known stored values map to the expected `SyncStatus`.
- [ ] Unit test: unknown stored value no longer returns `null` silently.
- [ ] Unit test: chosen fallback or exception behavior is stable and documented.
- [ ] Static verification: remove ad hoc `SyncStatus.valueOf(...).getOrNull()` parsing from Room converter paths.

## Rollback Plan

Revert the centralized parser and restore the current converter behavior. No schema migration is required unless a new persisted enum value is introduced.

## Dependencies

- Requires: decision on whether production should fail-fast or use a fallback enum for unknown stored values
- Blocking: none

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-027] SyncStatus database parsing now uses an explicit, test-covered policy instead of silently swallowing unknown enum values.
```

## Post-implementation Review Notes

- **Review:** [code_review_rp_bug_024_027_2026-05-18.md](../reviews/code_review_rp_bug_024_027_2026-05-18.md)
- **Status:** already effectively fixed and validated

### Follow-up Required

- No bug-specific blockers remain.
- Optional follow-up: document fail-fast parsing as an intentional production invariant.
