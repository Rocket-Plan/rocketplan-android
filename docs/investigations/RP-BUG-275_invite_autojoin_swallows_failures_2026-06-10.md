---
bug_id: RP-BUG-275
aliases: []
title: Launch-time invite auto-join ignores addCompanyUser/setActiveCompany results and clears pending invite regardless
type: functional
classification: new_code_bug
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: 6634854
released_in: null
state: fixed
release_state: unreleased
regression_of: RP-BUG-270
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_275_invite_autojoin_failures_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-275 — Invite auto-join silently drops a failed join

Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H5**.
Noted as "minor" in `code_review_rp_bug_269_270_2026-06-10.md`; promoted to a tracked bug.

## Symptom
A user with a pending invite launches the app; the company resolves but `addCompanyUser` or
`setActiveCompany` fails (network blip, server error). The pending invite is **cleared anyway**
and the user is navigated to projects without actually being a member of the company — with no
error shown and no retry on next launch. The invite is permanently lost.

## Root cause
In `MainActivity.checkAuthenticationStatus` (RP-BUG-270 block, ~line 405):

```kotlin
if (joinResult.isSuccess) {
    val company = joinResult.getOrNull()
    if (company != null) {
        authRepository.addCompanyUser(company.id, userId)   // Result ignored
        authRepository.setActiveCompany(company.id)          // Result ignored
        authRepository.clearPendingInviteCompanyUuid()       // cleared regardless
        navController.navigate(R.id.nav_projects, ...)
    }
}
```

The `Result`s of `addCompanyUser` / `setActiveCompany` are never inspected, and the pending
UUID is cleared on resolve-success even if the join itself failed.

## Fix direction
Only clear the pending invite after `addCompanyUser` **and** `setActiveCompany` both succeed.
On failure, retain the pending UUID (retry next launch) and surface a non-fatal error; do not
navigate to projects as if joined.

## Observability

### Current Signals
- Local: "Successfully joined company via invite" logs unconditionally on the success branch
  even though the inner calls may have failed.
- Remote/Sentry: none.

### Gaps
- A failed join is indistinguishable from a successful one in logs.

### Proposed Instrumentation
- Check + log each Result; remote WARN on join failure with retained-pending flag.
- Category: `auth_invite_join`. Fields: `resolve_ok`, `add_user_ok`, `set_active_ok`, `retained`.

### Success Criteria
- QA: force `addCompanyUser` to fail → invite retained, retried next launch, user not in projects.
