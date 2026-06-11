# Fix Plan: [RP-BUG-274] Bounded SMS-403 retry on setActiveCompany

**Bug ID(s):** RP-BUG-274
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in 6634854)

---

## Summary

`RetrofitClient` only *exempts* the stale post-verify 403 from sign-out — it does not retry. So a
just-verified user's `setActiveCompany` could 403 inside `runCatching` (backend MONGOOSE-BUG-028 race)
and land company-less until a later refresh (iOS RP-BUG-333).

Fix: an in-session "just verified" flag that enables a bounded retry of `setActiveCompany` on 403.

## Affected Code

| File | Change |
|------|--------|
| `data/repository/AuthRepository.kt` | Add `@Volatile sessionSmsVerifiedJustConfirmed` + `MAX_SET_ACTIVE_COMPANY_RETRIES`. Set the flag on successful `verifyCode`. In the SMS-verified branch of the company-context path, retry `setActiveCompany` up to N times with backoff when the response is 403; clear the flag after. |

## Implementation Notes (as built)
```kotlin
val maxRetries = if (sessionSmsVerifiedJustConfirmed) MAX_SET_ACTIVE_COMPANY_RETRIES else 1
repeat(maxRetries) { attempt ->
    if (setActiveCompanySuccess) return@repeat
    val resp = authService.setActiveCompany(SetActiveCompanyRequest(selectedCompanyId))
    if (resp.isSuccessful) { setActiveCompanySuccess = true }
    else if (resp.code() == 403 && attempt < maxRetries - 1) { delay(500L * (attempt + 1)) }
}
```
Only the just-verified session retries (bounded, with linear backoff); steady-state behavior is
unchanged (single attempt). Gated under the existing `isSmsVerified` check (RP-BUG-269).

## Test Plan

- [ ] Manual QA: verify SMS for a user whose backend briefly 403s on first `setActiveCompany`; confirm
  the retry succeeds and the user lands in their company (not company-less).
- [ ] Steady-state login still makes a single `setActiveCompany` call.

## Rollback Plan

Revert to the single `runCatching` call + flag removal; reintroduces the rare company-less window but
no new failure.

## Dependencies

- Pairs with the RetrofitClient 403 sign-out exemption (already present) and RP-BUG-271
  (`refreshUserContext` after verify, which triggers this path). Backend: MONGOOSE-BUG-028.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-274] Just-verified users no longer briefly land company-less when the server lags on the
  set-active-company call; the request is retried within the session.
```
