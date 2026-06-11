---
bug_id: RP-HD-005
aliases: []
title: Auth gate decisions are local-logcat only — cannot trace a user's signup routing from remote log_entries
type: functional
classification: pre_existing_latent
source: review
found_in: "1.30 (35)"
found_at: "2026-06-10 23:04:27 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_hd_005_remote_log_auth_gates_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-HD-005 — Remote-log the auth/signup gate decisions

Parity ref: iOS remote categories `auth_sms` / `auth_company` / `auth_recheck`.
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H8**. Preventive — no live failure.

## Rationale
The Android signup routing decisions in `MainActivity.checkAuthenticationStatus` (SMS gate,
pending-invite resolution, company gate, navigate-to-projects) are emitted with local
`Log.d(TAG="MainActivity")` only. They do **not** reach the remote `log_entries` store, so a
user's auth routing cannot be traced in the wild — unlike iOS, which logs `auth_sms` /
`auth_company` / `auth_recheck` remotely. This blocks diagnosis of RP-BUG-271/274/278-class
mis-routes in production.

## Proposal
Add low-volume, one-shot remote INFO logs (mirroring iOS categories) at each gate decision:
SMS gate hit, invite auto-join attempt/result, company gate hit, routed-to-projects. Reuse the
existing `RemoteLogger` already used by `AuthRepository` for "Company context set".

## Observability

### Current Signals
- Local logcat only (see grep in `MainActivity.kt:391–443`).
- Remote: only `AuthRepository` "Company context set" / "Skipped company context".

### Gaps
- No remote trail for the gate sequence; can't answer "why did this user land on X?".

### Proposed Instrumentation
- Categories: `auth_sms`, `auth_company`, `auth_invite_join` (align names with iOS where possible).
- Fields: `sms_verified`, `has_company`, `company_count`, `destination`, `invite_outcome`.
- Sampling/throttling: one-shot per launch decision; INFO level; not per-frame.
- Build/env gating: enabled in all builds (low volume, actionable).

### Success Criteria
- A single `log_entries` query reconstructs a user's signup routing (parity with the iOS SQL in
  the iOS AUTH_SIGNUP_FLOW.md §4).

## Update 2026-06-11 — partial: gating mechanism only

Commit `3dff3e0` added a `RemoteLogGate` interface (`RemoteLogGateAlwaysOn` default,
`RemoteLogger.updateGate()`, early-return in `log()`) — the **build/env gating** building block from
the proposal. It does **not** emit the auth gate-decision logs, so the ticket is not satisfied: the
`auth_sms` / `auth_company` / `auth_invite_join` logs in `MainActivity.checkAuthenticationStatus`
(+ the OAuth path) are still outstanding. State stays `planned`; see
`docs/plans/plan_rp_hd_005_remote_log_auth_gates_2026-06-11.md`.
