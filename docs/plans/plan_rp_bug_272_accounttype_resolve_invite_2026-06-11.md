# Fix Plan: [RP-BUG-272] AccountType screen resolves a pending invite

**Bug ID(s):** RP-BUG-272
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in 6634854)

---

## Summary

Invite auto-join lived only in `MainActivity` at launch. An invited user going through *fresh signup*
reached the AccountType (Create/Join) screen with no invite resolution, so they saw the chooser
instead of auto-joining (iOS RP-BUG-328, parity with `checkForInvitiation`).

Fix: add `checkForInvitation()` to `AccountTypeViewModel`, run it on screen entry, and auto-join +
route to projects on success — failing **closed** (clear invite / fall back to chooser) on error.

## Affected Code

| File | Change |
|------|--------|
| `ui/auth/AccountTypeViewModel.kt` | `ViewModel` → `AndroidViewModel`; add `checkForInvitation()`, `inviteResolved`/`isLoading`/`errorMessage` LiveData, and `onInviteResolvedHandled()`. |
| `ui/auth/AccountTypeFragment.kt` | Observe the new state; on `inviteResolved == true` run `ensureInitialSync()` and navigate to `nav_projects` (pop to emailCheck). Loading/error UI wiring. |
| `data/repository/AuthRepository.kt` | Reuse `getPendingInviteCompanyUuid`, `getStoredUserId`, `resolveCompanyByUuid`, `addCompanyUser`, `setActiveCompany`, `clearPendingInviteCompanyUuid`. |

## Implementation Notes (as built)

`checkForInvitation()` is idempotent (`hasCheckedInvite` guard). Flow:
`pendingInviteUuid? → resolveCompanyByUuid → addCompanyUser → setActiveCompany → clear invite →
inviteResolved=true`. Each failure short-circuits: resolve/null-company → clear invite + fall back to
chooser; addUser fail → surface `errorMessage`, leave invite (RP-BUG-275 semantics); setActive fail →
"joined but failed to set active, please restart".

## Test Plan

- [ ] Manual QA:
  1. Prereq: fresh signup for an invited user (pending invite stored).
  2. Action: reach AccountType screen.
  3. Expected: auto-joins and lands in projects; no chooser.
- [ ] No pending invite → chooser shown as before.
- [ ] Covered by RP-HD-007 unit tests (planned).

## Rollback Plan

Revert `AccountTypeViewModel`/`Fragment` to the stub; the MainActivity launch-time path still handles
already-authenticated invitees.

## Dependencies

- Shares the join primitives with RP-BUG-270 / RP-BUG-275. Related: RP-HD-007 (tests).

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-272] Invited users going through fresh signup now auto-join their company at the account-type
  step instead of seeing the Create/Join chooser.
```
