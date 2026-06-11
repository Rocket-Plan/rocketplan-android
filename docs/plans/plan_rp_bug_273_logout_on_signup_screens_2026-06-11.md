# Fix Plan: [RP-BUG-273] Logout/exit on authenticated signup screens

**Bug ID(s):** RP-BUG-273
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in 6634854)

---

## Summary

`checkAuthenticationStatus` pops `emailCheckFragment` inclusive, so a user force-routed onto an
authenticated signup screen (phone / SMS / accountType / joinCompany / finalDetails) had no back
stack and no logout — stranded, unable to sign in as someone else (iOS RP-BUG-329). Worsened by the
RP-BUG-269 gating that force-routes more users here.

Fix: add a logout button to all five screens that logs out and returns to email check.

## Affected Code

| File | Change |
|------|--------|
| `ui/auth/{PhoneVerification,SmsCodeVerify,AccountType,JoinCompany,FinalDetails}Fragment.kt` | Wire a `logoutButton` click → `authRepository.logout()` → navigate to `emailCheckFragment` (pop inclusive). |
| `res/layout/fragment_{phone_verification,sms_code_verify,account_type,join_company,final_details}.xml` | Add the `logoutButton` view. |

## Implementation Notes (as built)
```kotlin
binding.logoutButton.setOnClickListener {
    lifecycleScope.launch {
        authRepository.logout()
        val navOptions = NavOptions.Builder().setPopUpTo(R.id.emailCheckFragment, true).build()
        findNavController().navigate(R.id.emailCheckFragment, null, navOptions)
    }
}
```
Each fragment lazily builds `AuthRepository(SecureStorage.getInstance(requireContext()))`.

## Test Plan

- [ ] Manual QA: on each of the five screens, tapping logout signs out and returns to email check; can
  then sign in as a different user.

## Rollback Plan

Remove the buttons / click handlers; no behavior change elsewhere.

## Dependencies

- None. Complements RP-BUG-271/269 routing.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-273] Added a logout option to the signup screens so users force-routed there are no longer
  stranded and can sign in as a different account.
```
