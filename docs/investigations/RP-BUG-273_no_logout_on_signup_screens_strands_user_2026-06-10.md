---
bug_id: RP-BUG-273
aliases: []
title: No logout/exit on authenticated signup screens — force-routed user is stranded with no back stack
type: functional
classification: pre_existing_worsened
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: 6634854
released_in: null
state: fixed
release_state: unreleased
regression_of: RP-BUG-269
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_273_logout_on_signup_screens_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-273 — Stranded on signup screens (no logout / no back stack)

Parity ref: iOS `RP-BUG-329` (`AuthSignHeader.onLogout` on every signup screen + WelcomeBack real logout).
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H3**.

## Symptom
An authenticated-but-incomplete user (e.g. logged in but SMS-unverified, or verified with no
company) is force-routed by `MainActivity` to `phoneVerificationFragment`. They cannot get
out: there is no "Log Out" / "Sign in as someone else" affordance on any signup screen, and
the back button has nowhere to go. The user is stuck on the verification/onboarding screen.

## Root cause
1. `checkAuthenticationStatus` navigates with `setPopUpTo(R.id.emailCheckFragment, true)`
   (inclusive) — the sign-in/up entry is popped, so the signup screens have **no back stack**.
2. None of `phoneVerificationFragment`, `smsCodeVerifyFragment`, `accountTypeFragment`,
   `joinCompanyFragment`, `finalDetailsFragment` expose a sign-out action; back buttons only
   call `navigateUp()`. Logout exists only inside the authenticated app (e.g. ProjectsFragment).

The strand was always latent (signup screens never had logout) but RP-BUG-269's forced
routing made it reachable for an existing authenticated user, not just a brand-new signup.

## Fix direction
Add a logout/exit affordance to every authenticated-but-incomplete signup screen (toolbar
"Log Out" → `authRepository.logout()` + navigate to `emailCheckFragment`), mirroring iOS
`AuthSignHeader.onLogout`.

## Observability

### Current Signals
- None — a stranded user produces no log/Sentry event; it manifests as a support complaint.

### Gaps
- No telemetry for "user sat on a signup screen with no forward progress and no exit".

### Proposed Instrumentation
- Local debug log when a signup screen is shown as a forced-route root (no back stack).
- Optional remote INFO: count of logout-from-signup taps once the affordance exists.
- Category: `auth_signup_exit`.

### Success Criteria
- QA: from each signup screen, "Log Out" returns to email check and clears the session.
- Wild: drop in "stuck on verification, can't switch accounts" reports.
