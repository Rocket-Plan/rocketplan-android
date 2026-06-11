# Fix Plan: [RP-BUG-271] Post-verify routing keys on uncached companyId

**Bug ID(s):** RP-BUG-271
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in 6634854)

---

## Summary

RP-BUG-269 gating never caches `companyId` for an *unverified* user, and `SmsCodeVerifyFragment`'s
post-verify branch read `getStoredCompanyId()` without first refreshing user context. So the
"skip Create/Join chooser, go straight to app" branch was dead — an existing-company user was always
dumped into the chooser after SMS verify (iOS RP-BUG-331/333). Regression of RP-BUG-269.

Fix: re-run `refreshUserContext()` after a successful verify, before branching on the cached company.

## Affected Code

| File | Change |
|------|--------|
| `ui/auth/SmsCodeVerifyFragment.kt` | In the `verified` observer, call `authRepository.refreshUserContext()` before reading `getStoredCompanyId()`. |

## Implementation Notes (as built)
```kotlin
// SmsCodeVerifyFragment — verified observer
viewLifecycleOwner.lifecycleScope.launch {
    authRepository.refreshUserContext()              // RP-BUG-271: pick up freshly-verified companyId
    val hasCompany = authRepository.getStoredCompanyId() != null
    if (hasCompany) { /* navigate straight to projects */ }
    // else -> Create/Join chooser
}
```

## Test Plan

- [ ] Manual QA:
  1. Prereq: existing-company user who is SMS-unverified.
  2. Action: complete SMS verify.
  3. Expected: routed straight to projects, NOT the Create/Join chooser.
- [ ] New-user (no company) still reaches the chooser.

## Rollback Plan

Single added call; revert the `refreshUserContext()` line to restore prior (buggy) behavior.

## Dependencies

- Builds on RP-BUG-269 (`90078ec`) SMS gating. Related: RP-BUG-274 (the 403-retry that makes the
  refresh's `setActiveCompany` robust).

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-271] After SMS verification, existing-company users go straight to the app instead of being
  shown the Create/Join company chooser.
```
