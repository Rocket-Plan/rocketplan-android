# Fix Plan: [RP-BUG-275] Launch-time invite auto-join honors failures

**Bug ID(s):** RP-BUG-275
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in 6634854)

---

## Summary

The RP-BUG-270 launch-time auto-join in `MainActivity` ignored the `addCompanyUser` /
`setActiveCompany` Results and cleared the pending invite on resolve-success regardless. A failed
join was silently dropped (no retry) and the user was navigated to projects without membership.
Regression of RP-BUG-270.

Fix: check both Results; only clear the invite and navigate on full success, otherwise retain the
pending invite for a later retry and stay put.

## Affected Code

| File | Change |
|------|--------|
| `MainActivity.kt` (pending-invite block, ~L401–443) | Capture `addCompanyUser`/`setActiveCompany` Results; clear invite + navigate to projects only when both succeed; on either failure, log a warning and retain the invite (no navigation). |

## Implementation Notes (as built)
```kotlin
val addUserResult = authRepository.addCompanyUser(company.id, userId)
if (addUserResult.isFailure) {
    Log.w(TAG, "Failed to add user to company: ...")   // retain invite, don't navigate
} else {
    val setCompanyResult = authRepository.setActiveCompany(company.id)
    if (setCompanyResult.isSuccess) {
        authRepository.clearPendingInviteCompanyUuid()
        navController.navigate(R.id.nav_projects, null, popToEmailCheck)
        lifecycleScope.launch { syncQueueManager.ensureInitialSync() }
        return@withContext
    } else {
        Log.w(TAG, "Failed to set active company: ...")  // retain invite, don't navigate
    }
}
```

## Test Plan

- [ ] Manual QA: simulate `addCompanyUser`/`setActiveCompany` failure on a pending invite; confirm the
  invite is retained, the user is NOT shown projects as if joined, and a subsequent launch retries.
- [ ] Happy path still joins + navigates + clears the invite once.

## Rollback Plan

Revert the result-checking block to the prior fire-and-forget version.

## Dependencies

- Mirrors RP-BUG-272's failure-closed semantics on the AccountType path. Builds on RP-BUG-270
  (`90078ec`).

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-275] A failed invite auto-join at launch no longer silently drops the invite or sends the
  user into the app without membership; the invite is kept and retried.
```
