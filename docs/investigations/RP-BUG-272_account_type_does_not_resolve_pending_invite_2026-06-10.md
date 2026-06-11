---
bug_id: RP-BUG-272
aliases: []
title: AccountType screen does not resolve a pending invite — invited user sees Create/Join during fresh signup
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
related_plan: null
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-272 — Invite auto-join only fires at launch, not on AccountType

Parity ref: iOS `RP-BUG-328` (`AccountTypeViewModel.checkForInvitiation()`).
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H2**.

## Symptom
A user taps a company-invite link, then completes a fresh signup (register → phone verify →
SMS code). After verification they land on the **Account Type** screen and are asked to
"Create a Company" or "Join a Company" — even though a pending invite UUID is already stored.
The invite does not auto-resolve until they relaunch the app.

## Root cause
On Android, invite auto-join lives **only** in `MainActivity.checkAuthenticationStatus`
(launch / auth resolution). `AccountTypeViewModel` is an empty stub — it has no
`checkForInvitiation`-equivalent. The forward signup path (verify → AccountType) never
consults `AuthRepository.getPendingInviteCompanyUuid()`, so a pending invite is ignored
until the next cold start funnels back through `MainActivity`.

iOS drives `AccountType.checkForInvitiation()` from both `.task` and `.onAppear`, one-shot
via `didCheckInvitation`, so an invited user reaching the `.noCompany` root auto-joins
immediately.

## Fix direction
In `AccountTypeFragment`/`ViewModel`, on first appearance check
`getPendingInviteCompanyUuid()`; if present, resolve → addUser → setActiveCompany → refresh →
navigate to projects (reuse the `JoinCompanyViewModel`/`MainActivity` path). Guard one-shot.

## Observability

### Current Signals
- Local console logs: `MainActivity` logs the launch-time auto-join; AccountType logs nothing.
- Remote logs: none on this path.
- Sentry: none.

### Gaps
- No signal that an invited user reached AccountType with a pending invite unresolved.

### Proposed Instrumentation
- Local debug log when AccountType detects a pending invite and the resolution outcome.
- Remote (throttled, INFO) one-shot: "AccountType resolving invite" / "invite resolved".
- Category: `auth_account_type`. Fields: `company_uuid_present`, `outcome`.

### Success Criteria
- QA: tap invite → fresh signup → after SMS verify, user auto-joins without seeing the chooser.
- Wild: invited users no longer create duplicate companies from the chooser.
