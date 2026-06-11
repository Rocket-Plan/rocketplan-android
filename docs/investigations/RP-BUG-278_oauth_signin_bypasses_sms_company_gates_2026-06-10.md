---
bug_id: RP-BUG-278
aliases: []
title: Google OAuth sign-in navigates straight to home, bypassing the SMS and company gates for that session
type: functional
classification: pre_existing_latent
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: 6634854
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_278_oauth_gate_routing_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-278 — OAuth sign-in bypasses SMS/company gates (first session)

Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H9**.

## Symptom
A user signing in with Google OAuth is taken **directly to home** without passing the SMS
verification or company gates. If they are SMS-unverified or company-less, they briefly see an
empty/forbidden app state (and company-scoped calls may 403) until they relaunch, at which
point `checkAuthenticationStatus` applies the gates and corrects the route.

## Root cause
`EmailCheckFragment` navigates on OAuth success via
`EmailCheckFragmentDirections.actionEmailCheckFragmentToNavHome()` (line ~183) immediately,
without checking `isSmsVerified` or `companyId`. The gate sequence lives only in
`MainActivity.checkAuthenticationStatus`, which runs at cold start — not after an in-session
OAuth completion.

## Fix direction
After OAuth success, run the same gate check (refresh user context → SMS gate → company/invite
gate) before navigating, or route OAuth completion back through a shared gate function rather
than hard-coding `nav_home`.

## Observability

### Current Signals
- Local: OAuth success logs exist; no gate-decision log on this path.

### Gaps
- No signal that an OAuth user entered home unverified / company-less.

### Proposed Instrumentation
- Reuse the shared gate logging (see RP-HD-005) on the OAuth completion path.
- Category: `auth_oauth`. Fields: `sms_verified`, `has_company`.

### Success Criteria
- QA: an unverified OAuth account is routed to phone verification in the same session, not home.
