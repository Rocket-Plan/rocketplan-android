---
bug_id: RP-BUG-276
aliases: []
title: No company-approval gate — member of an unapproved company goes straight into the app
type: functional
classification: feature_gap
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_276_company_approval_gate_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-276 — Missing company-approval gate (no `.companyApproval` equivalent)

Parity ref: iOS `.companyApproval` / `WelcomeBackContentView`, `Company.isApproved`.
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H6**.

## Symptom
A user who belongs to a company that is **not yet approved** is taken straight to
`nav_projects` on Android. iOS instead shows a "Welcome Back / pending approval" screen
(`.companyApproval`) until `company.isApproved`. The Android user may see an empty/forbidden
app state or hit backend rejections instead of a clear "pending approval" message.

## Root cause
The Android `Company` model has **no `isApproved` field** (grep of `data/model/` finds no
`isApproved`/`is_approved`/approval). `MainActivity.checkAuthenticationStatus` routes to
projects whenever a `companyId` is set, with no approval check and no approval screen in the
nav graph.

## Fix direction
Add `isApproved` (server `is_approved` / approval timestamp — confirm field name with backend)
to the `Company` model, and a routing gate + a "pending approval" screen analogous to iOS
WelcomeBack. Confirm the exact server contract before implementing.

## Observability

### Current Signals
- None — an unapproved-company user is routed identically to an approved one.

### Gaps
- No client awareness of approval state; failures surface only as downstream API rejections.

### Proposed Instrumentation
- Once modeled: local + remote INFO "routing: company approved={bool}".
- Category: `auth_company`. Fields: `company_id`, `is_approved`.

### Success Criteria
- QA: a member of an unapproved company sees the pending-approval screen, not projects.

## Open question
- Confirm with backend whether Android is expected to enforce this gate or whether the API
  already blocks unapproved-company data (which would downgrade this to lower priority).
