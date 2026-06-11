# Fix Plan: [RP-BUG-278] OAuth sign-in routes through SMS/company gates

**Bug ID(s):** RP-BUG-278
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in 6634854)

---

## Summary

Google OAuth sign-in navigated straight to `nav_home`, bypassing the SMS and company gates for that
session (the gates only ran in `MainActivity` at cold start). An unverified / company-less OAuth user
briefly entered the app until relaunch.

Fix: route OAuth success through the same gate check the cold-start path uses, in both entry points.

## Affected Code

| File | Change |
|------|--------|
| `MainActivity.kt` (OAuth handler, ~L571–600) | After saving the token + loading the account, call `checkAuthenticationStatus(navController)` instead of hard-coding `navigate(nav_projects)`. Removed the eager `ensureInitialSync()`/`checkCrmAccess()` from before the gate; reset `isProcessingOAuth` on the early-return error path. |
| `ui/auth/EmailCheckFragment.kt` | In the Google `signInSuccess` observer, branch on `getCachedSmsVerified()` / `getStoredCompanyId()`: unverified → `phoneVerificationFragment`; no company → `accountTypeFragment`; else → projects (pop to emailCheck). |

## Implementation Notes (as built)

`MainActivity` OAuth path now ends with `checkAuthenticationStatus(navController)` so OAuth and
email/password share one gate. `EmailCheckFragment` performs the equivalent gate inline for its own
Google sign-in entry point.

> Note: the two entry points duplicate the gate logic. A follow-up could extract a single shared
> routing helper; not required for correctness.

## Test Plan

- [ ] Manual QA, OAuth user variants:
  1. Unverified → lands on phone verification (not home).
  2. Verified, no company → account-type chooser.
  3. Verified + company → projects.
- [ ] No transient flash of `nav_home` before the gate redirect.

## Rollback Plan

Restore the hard-coded `navigate(nav_projects)` in both entry points.

## Dependencies

- Reuses `checkAuthenticationStatus` (RP-BUG-269 gating). Related: RP-HD-005 (would remote-log these
  OAuth gate decisions).

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-278] Google sign-in now applies the SMS and company gates, so unverified or company-less
  users are routed correctly instead of briefly entering the app.
```
