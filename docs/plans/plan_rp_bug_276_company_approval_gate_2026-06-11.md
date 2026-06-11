# Fix Plan: [RP-BUG-276] Company-approval gate (`.companyApproval` parity)

**Bug ID(s):** RP-BUG-276
**Author:** Claude
**Date:** 2026-06-11
**State:** draft

---

## Summary

A user who belongs to a **not-yet-approved** company is routed straight to `nav_projects` on
Android. iOS instead parks them on a "Welcome Back / pending approval" screen (`.companyApproval`)
until `company.isApproved`. Android's `Company` model has no approval field and
`MainActivity.checkAuthenticationStatus` routes to projects whenever a `companyId` is set.

Fix: model the approval flag, add a routing gate, and add a pending-approval screen.

> **Prerequisite decision (blocking — see Open Question in the investigation).** Confirm with
> backend whether Android is *expected* to enforce this gate, or whether `api-public` already
> blocks unapproved-company data (which would downgrade this to a UX-nicety, not a correctness
> gate). Do not implement the full screen+gate until this is answered — if the API already blocks,
> scope shrinks to a friendlier error state. The steps below assume client enforcement is wanted.

## Affected Code

| File | Change |
|------|--------|
| `data/model/AuthModels.kt` | Add `isApproved: Boolean?` to `Company` (`@SerializedName("is_approved")` — confirm exact field/contract with backend). `RP-HD-006` already added the `email_verified_at` precedent here. |
| `data/repository/AuthRepository.kt` | Expose a cached `getStoredCompanyApproved()` (persist alongside `companyId` in `SecureStorage`) or read from the cached `CurrentUserResponse`. |
| `MainActivity.kt` (`checkAuthenticationStatus`, ~L391–443) | After the SMS gate and company gate, branch: if `companyId != null` **and** approval is known-false → navigate to the pending-approval screen instead of `nav_projects`. |
| `res/navigation/*nav graph*.xml` | Add a `companyApprovalFragment` destination reachable from the auth flow. |
| `ui/auth/CompanyApprovalFragment.kt` + layout (new) | "Pending approval / Welcome Back" screen: company name, explanatory copy, a refresh/recheck action (re-runs `refreshUserContext`), and a logout button (reuse the RP-BUG-273 logout pattern). |

## Implementation Notes

### Step 1: Model the field
```kotlin
// AuthModels.kt — Company
@SerializedName("is_approved")
val isApproved: Boolean? = null   // null = unknown/legacy; treat null as approved to avoid lockout
```
Decide the null policy explicitly: **treat `null` as approved** so existing/legacy payloads don't
strand current users. Only a hard `false` triggers the gate.

### Step 2: Routing gate in MainActivity
```kotlin
// inside checkAuthenticationStatus, after company gate, before navigate(nav_projects)
val approved = currentUser.primaryCompany()?.isApproved ?: true
if (companyId != null && approved == false) {
    navController.navigate(R.id.companyApprovalFragment, null, popToEmailCheckInclusive)
    return@withContext
}
```

### Step 3: Pending-approval screen
- Mirror iOS `WelcomeBackContentView` copy.
- "Check again" → `authRepository.refreshUserContext()` then re-run the gate; if now approved, route
  to projects.
- Logout button (same NavOptions pop-to-`emailCheckFragment` as RP-BUG-273).

## Test Plan

- [ ] Unit: `Company` deserializes `is_approved` true/false/absent; null→treated approved.
- [ ] Manual QA:
  1. Prereq: account in an **unapproved** company (backend-seeded).
  2. Action: launch app / sign in.
  3. Expected: pending-approval screen, not projects. "Check again" after backend approval routes
     to projects. Logout returns to email check.

## Rollback Plan

Gate is additive and guarded by `approved == false` (null/true preserve current behavior). Revert
the MainActivity branch + nav destination to disable; the model field is inert if unused.

## Dependencies

- **Requires:** backend confirmation of the approval contract (field name + whether the gate is the
  client's responsibility). This is the blocking open question.
- Related: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H6**.

## Changelog Entry

```markdown
## [1.30] - 2026-06-XX

### Fixed
- [RP-BUG-276] Members of an unapproved company now see a pending-approval screen instead of being
  dropped into an empty/forbidden app state.
```
