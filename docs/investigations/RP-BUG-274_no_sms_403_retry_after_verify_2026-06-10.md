---
bug_id: RP-BUG-274
aliases: []
title: No SMS-403 retry / in-session verified flag — just-verified user's company call silently fails on stale backend gate
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
related_plan: docs/plans/plan_rp_bug_274_sms_403_retry_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-274 — No retry / in-session trust for the stale post-verify SMS-403

Parity ref: iOS `RP-BUG-333` (retry + `markSmsVerifiedInSession()`); backend root `MONGOOSE-BUG-028`.
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H4**.

## Symptom
Immediately after SMS verification, company-context calls (`POST /api/active-company`) can
still `403 "sms code is not verified"` because the backend `sms.verified` gate reads a cached
auth user (~60s TTL) that isn't busted on verify (MONGOOSE-BUG-028). On Android the company
context is then **not set**, so the just-verified user lands without an active company until a
later `refreshUserContext` happens to run after the cache expires.

## Root cause
`RetrofitClient.unauthorizedInterceptor` (RP-BUG-269) only **exempts** the SMS-403 from forced
sign-out — it does **not retry**, and Android has **no in-session "verified" flag**. The
`setActiveCompany` call sits inside `runCatching { }` in `refreshUserContext`, so the 403 is
swallowed with a warning and the company context block's local writes never run for that pass.

iOS added a brief retry for a verified-in-session user plus `markSmsVerifiedInSession()` so all
gates trust the in-session verify and never bounce on the stale 403.

## Fix direction
Add an in-session "SMS verified" flag set on verify success (cleared on sign-out) that the gates
trust; and a short bounded retry of the company-context call on an SMS-403 for a
verified-in-session user. Note this is a safety net — the durable fix is MONGOOSE-BUG-028.

## Observability

### Current Signals
- Remote logs: `AuthRepository` "Failed to set active company on server: 403" (warning).
- Sentry: none.

### Gaps
- The transient 403 is logged as a generic failure; not distinguished from a real auth failure,
  and no signal that the user proceeded without active company context.

### Proposed Instrumentation
- Remote WARN (throttled): "active-company 403 (sms gate) post-verify — retrying" with attempt #.
- Category: `auth_sms_403`. Fields: `attempt`, `verified_in_session`.

### Success Criteria
- QA: verify on a fresh account → active company set within the verify session (no relaunch needed).
- Wild: no users landing company-less right after a successful verify.
