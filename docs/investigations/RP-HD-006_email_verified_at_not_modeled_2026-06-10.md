---
bug_id: RP-HD-006
aliases: []
title: CurrentUserResponse does not model email_verified_at (iOS parity)
type: functional
classification: feature_gap
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: 6634854
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_hd_006_email_verified_at_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-HD-006 — Model `email_verified_at` for parity (preventive)

Parity ref: iOS `User.emailVerifiedAt`.
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H10**. Preventive — not a gate today.

## Rationale
`CurrentUserResponse` (`AuthModels.kt`) models `sms_verified_at` (added RP-BUG-269) but not
`email_verified_at`, which iOS carries on its `User` model. Neither platform currently gates on
email verification, so this is **not a live defect** — it is tracked so that if email
verification becomes a gate (or a screen needs to display verification state), the Android model
is not silently missing the field.

## Proposal
Add `@SerializedName("email_verified_at") val emailVerifiedAt: String? = null` plus an
`isEmailVerified` computed property to `CurrentUserResponse`, matching the `isSmsVerified`
pattern. No routing change unless/until product introduces an email gate.

## Observability

### Current Signals
- N/A — field not consumed today.

### Gaps
- If a future email gate is added without this field, the client would be blind to it.

### Proposed Instrumentation
- None needed until the field gates routing; then log under `auth_email` parity category.

### Success Criteria
- Field decodes from `/api/auth/user`; available for any future email-verification gate/UI.
