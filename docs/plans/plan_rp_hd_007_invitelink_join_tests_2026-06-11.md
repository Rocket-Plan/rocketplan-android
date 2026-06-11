# Fix Plan: [RP-HD-007] Unit-test the invite parser and join flow

**Bug ID(s):** RP-HD-007
**Author:** Claude
**Date:** 2026-06-11
**State:** draft

---

## Summary

`util/InviteLink.kt` and `ui/auth/JoinCompanyViewModel.kt` (added in `90078ec`, RP-BUG-270) ship
without unit coverage. The parser is the exact surface where iOS regressed (RP-BUG-327: wrong segment
taken from a two-segment email link). Pin the behavior with JVM unit tests before the next change —
especially now that RP-BUG-272 added a second invite-resolution path (`AccountTypeViewModel`).

Preventive test debt — no live failure.

## Affected Code

| File | Change |
|------|--------|
| `app/src/test/java/.../util/InviteLinkTest.kt` (new) | Parser cases. |
| `app/src/test/java/.../ui/auth/JoinCompanyViewModelTest.kt` (new) | UUID extraction + join flow happy path and each failure short-circuit, using fakes/mocks. |
| (optional) `AccountTypeViewModelTest.kt` (new) | `checkForInvitation` failure-closed behavior added in RP-BUG-272 (`6634854`). |

## Implementation Notes

### InviteLink.parse cases
Assert the company UUID is the segment **after** `invite-redirect`:
- custom-scheme, one segment after marker
- custom-scheme, **two** segments (the RP-BUG-327 trap — must take the right one)
- `https` web link, one segment
- `https` web link, two segments
- link with **no** `invite-redirect` marker → `null`
- blank/empty segments → `null`
- trailing slash tolerated

### JoinCompanyViewModel
- `extractUuid`: pasted full link, bare UUID, garbage input.
- Join flow: `resolveCompanyByUuid` → `addCompanyUser` → `setActiveCompany` → refresh happy path;
  and each failure short-circuit (resolve fail, addUser fail, setActive fail) asserts the right
  terminal state and that the pending invite is NOT cleared on a non-terminal failure (ties to the
  RP-BUG-275 semantics).

### Tooling
Pure JVM tests (`testDevStandardDebugUnitTest`). Mock `AuthRepository` with mockk or a hand fake;
no Android instrumentation needed.

## Test Plan

- [ ] `./gradlew testDevStandardDebugUnitTest` green.
- [ ] A deliberately broken parser (take segment *before* the marker) makes the two-segment test fail
  — confirms the test actually guards RP-BUG-327.

## Rollback Plan

Test-only; no production code touched. Remove the test files to revert.

## Dependencies

- None. Related: `RP-BUG-270` (`90078ec`), `RP-BUG-272`/`RP-BUG-275` (`6634854`);
  `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H11**.

## Changelog Entry

```markdown
## [1.30] - 2026-06-XX

### Changed
- [RP-HD-007] Added unit tests for InviteLink.parse and the company-join flow to lock in
  invite-link parsing (regression guard for the iOS RP-BUG-327 class).
```
