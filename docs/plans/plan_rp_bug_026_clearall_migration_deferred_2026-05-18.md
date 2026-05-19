**Bug ID:** RP-BUG-026
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-026_clearall_migration_deferred.md) · [Review](../reviews/code_review_full_2026-05-18.md)

# Fix Plan: [RP-BUG-026] Reset auth-token migration state on logout

**Bug ID(s):** RP-BUG-026
**Author:** jeremie.blais@jot.digital
**Date:** 2026-05-18
**State:** approved

---

## Summary

`SecureStorage` keeps a one-shot `migrationDeferred` created at construction time. `clearAll()` clears persisted state but leaves that in-memory migration task untouched. Although `compareAndSet(null, legacyToken)` reduces overwrite risk, logout should still fully invalidate old auth migration work so a stale coroutine cannot mutate post-logout state or confuse later reads. Fix by making migration lifecycle resettable and explicitly cancelling/invalidating it during `clearAll()`.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/storage/SecureStorage.kt` | Refactor migration task management so logout can cancel and replace stale migration work. |
| `app/src/test/java/com/example/rocketplan_android/data/storage/SecureStorageTest.kt` | Add logout/relogin race tests covering stale migration completion after `clearAll()`. |

## Implementation Notes

### Step 1: Replace immutable migration state with resettable state

Move from:

- single `private val migrationDeferred`

to:

- a mutable reference guarded by a small synchronization primitive (`Mutex`, synchronized block, or atomic reference)
- a helper that creates the current migration task

This allows `clearAll()` to invalidate old work and define what future reads should await.

### Step 2: Make `clearAll()` cancel or invalidate in-flight migration

On logout:

- cancel the current migration task if still active, or mark it stale via a generation token
- clear encrypted prefs + DataStore
- clear `authTokenState`
- replace migration state with a completed empty task, or a fresh no-op migration for the new logged-out session

The key invariant is: work started before logout must not publish token state after logout.

### Step 3: Keep the "fresh save beats migration" guard

Retain the existing `compareAndSet(null, legacyToken)` behavior or equivalent guard. Even with cancellation, this remains the right protection for save-vs-migrate races.

## Observability

- Add local debug logs for migration lifecycle transitions: started, cancelled during clearAll, completed, skipped because stale generation.
- Keep remote logging off by default for this path unless QA uncovers a real-world recurrence pattern.

## Test Plan

- [ ] Unit test: start legacy migration, call `clearAll()`, then complete the old migration; `authTokenState` remains null.
- [ ] Unit test: logout followed by immediate re-login keeps the new token even if old migration work finishes late.
- [ ] Unit test: `getAuthTokenSync()` after `clearAll()` does not await or revive stale token state.
- [ ] Unit test: no-legacy-token path still behaves as a fast no-op.

## Rollback Plan

Revert `SecureStorage.kt` migration lifecycle changes. No persistent format changes are required.

## Dependencies

- Requires: none
- Blocking: none

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-026] Logout now invalidates stale auth-token migration work so old session state cannot leak into a new login.
```

## Post-implementation Review Notes

- **Review:** [code_review_rp_bug_024_027_2026-05-18.md](../reviews/code_review_rp_bug_024_027_2026-05-18.md)
- **Status:** implemented and validated

### Follow-up Required

- No bug-specific blockers remain.
