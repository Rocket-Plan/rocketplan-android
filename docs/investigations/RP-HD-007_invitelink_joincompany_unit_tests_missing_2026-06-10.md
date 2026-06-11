---
bug_id: RP-HD-007
aliases: []
title: No unit tests for InviteLink.parse and JoinCompanyViewModel (RP-BUG-270 follow-up)
type: functional
classification: pre_existing_latent
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: null
released_in: null
state: open
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-HD-007 — Unit-test the invite parser and join flow

Documented follow-up of `RP-BUG-270` (`90078ec`).
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H11**. Preventive — test debt.

## Rationale
`util/InviteLink.kt` and `ui/auth/JoinCompanyViewModel.kt` (added in `90078ec`) ship without
unit coverage. The parser is the exact surface where iOS regressed (RP-BUG-327: wrong segment
taken from a two-segment email link) — Android should pin its behavior before the next change.

## Proposal
Add JVM unit tests:
- `InviteLink.parse`: custom-scheme one-segment, custom-scheme two-segment, https web
  one-segment, https web two-segment, link with no `invite-redirect` marker (→ null), blank
  segments, trailing slash. Assert the company UUID is the segment **after** `invite-redirect`.
- `JoinCompanyViewModel.extractUuid`: pasted full link, bare UUID, garbage input.
- `JoinCompanyViewModel` join flow: resolve → addUser → setActiveCompany → refresh happy path
  and each failure short-circuit (with fakes/mocks).

## Observability

### Current Signals
- N/A (test coverage gap).

### Gaps
- A future edit to the parser/join flow could regress RP-BUG-327/270 undetected.

### Proposed Instrumentation
- CI runs `testDevStandardDebugUnitTest`; these tests gate the parser/join behavior.

### Success Criteria
- Tests exist, pass, and fail if the "segment after invite-redirect" rule is broken.
