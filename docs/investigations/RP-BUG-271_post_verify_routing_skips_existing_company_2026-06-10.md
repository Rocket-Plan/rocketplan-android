---
bug_id: RP-BUG-271
aliases: []
title: Post-verify routing keys on uncached companyId — verified existing-company user dumped into Create/Join chooser
type: functional
classification: new_code_bug
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: 6634854
released_in: null
state: fixed
release_state: unreleased
regression_of: RP-BUG-269
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_271_post_verify_routing_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-271 — Post-verify routing skips existing company (dead "skip chooser" branch)

Parity ref: iOS `RP-BUG-331` (`routePostVerify`) + `RP-BUG-333` (in-session verified flag).
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H1**.

## Symptom
An existing-company user who has not completed SMS verification logs in, is routed to
phone verification, enters the code, and — instead of landing in the app — is dropped on
the **Account Type (Create a Company / Join a Company)** chooser. They appear to have no
company and may create a duplicate or get stuck.

## Root cause
`SmsCodeVerifyFragment` (post-verify, ~line 270) decides whether to skip the chooser via:

```kotlin
val hasCompany = authRepository.getStoredCompanyId() != null
```

`getStoredCompanyId()` reads `secureStorage.getCompanyIdSync()` (`AuthRepository.kt:507`).
But RP-BUG-269 (`90078ec`) moved `secureStorage.saveCompanyId(...)` **inside** the
`if (currentUser.isSmsVerified)` block in `refreshUserContext` — so for an unverified user
the companyId is **never cached**. And `SmsCodeVerifyViewModel.verify()` only calls
`verifySmsCode(...)` then sets `_verified = true`; it never re-runs `refreshUserContext`.

Net: at post-verify time `getStoredCompanyId()` is `null` for exactly the users this branch
targets, so the branch is dead and they always fall through to `accountTypeFragment`.

## Fix direction
After a successful verify, re-run `refreshUserContext()` (the server now reports
`sms_verified_at`, so the company block executes and caches companyId) **before** the
`getStoredCompanyId()` check — or branch on the freshly-returned `CurrentUserResponse`
(`companyId` / `companies`) instead of the cached value. Mirror iOS `routePostVerify`.

## Observability

### Current Signals
- Local console logs: `SmsCodeVerifyFragment` does not log the branch taken.
- Remote logs: `AuthRepository` logs "Company context set" / "Skipped company context" but
  only on a `refreshUserContext` that runs while verified.
- Sentry: none.
- Existing metrics/watchdogs: none.

### Gaps
- The mis-route is silent — no signal distinguishes "new user → chooser" (correct) from
  "existing-company user → chooser" (this bug).

### Proposed Instrumentation
- Local debug log in the post-verify branch: chosen destination + whether companyId/companies
  were present in the post-verify user context.
- Remote (throttled, INFO): one-shot "post-verify routed to {projects|accountType}" with
  `has_company`.
- Category: `auth_post_verify`.
- Key fields: `has_company`, `company_count`, `destination`.

### Success Criteria
- QA: an existing-company unverified user verifies → lands in projects, not the chooser.
- Wild: no `auth_post_verify destination=accountType has_company=true` events.
