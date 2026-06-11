---
bug_id: RP-BUG-277
aliases: []
title: Emailed https invite link does not auto-open the app — autoVerify=false and no assetlinks.json
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
related_plan: docs/plans/plan_rp_bug_277_invite_applinks_2026-06-11.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
---

# RP-BUG-277 — Emailed invite link auto-open not wired (App Links)

Parity ref: iOS Universal Links via AASA. Documented follow-up of `RP-BUG-270`.
Flow doc: `docs/architecture/AUTH_SIGNUP_FLOW.md` §6 hole **H7**.

## Symptom
An invited employee taps an emailed `https://…/invite-redirect/{companyUuid}` link on a device
with the app installed. Android opens it in the **browser**, not the app, so the in-app
auto-join never triggers. Only the in-app paste flow (`JoinCompanyFragment`) and custom-scheme
links currently reach `InviteLink.parse`.

## Root cause
Two-part, both outside app logic:
1. `AndroidManifest` https intent filters are declared `android:autoVerify="false"`.
2. No Digital Asset Links file (`/.well-known/assetlinks.json`, package + signing-cert SHA-256)
   is published on the invite hosts.

For Android 12+ to hand an `https` link to the app automatically, both the manifest must set
`autoVerify="true"` and the hosts must serve a valid `assetlinks.json`.

## Fix direction
- App: set `autoVerify="true"` on the https invite filters (once asset links are live).
- Backend/ops: publish `assetlinks.json` with the release signing cert SHA-256 on each invite
  host (prod + staging/QA). Coordinate with the iOS AASA hosts already verified.

## Observability

### Current Signals
- Local: `MainActivity.handleInviteDeepLink` logs received invite URIs (custom-scheme only today).

### Gaps
- No signal when an emailed link is opened in the browser instead of the app (it never reaches us).

### Proposed Instrumentation
- Once wired: log App Links verification status at startup (debug) and count invite-link opens.

### Success Criteria
- QA: tapping an emailed https invite on an installed device opens the app and auto-joins.

## Dependency
- Backend/ops task to publish `assetlinks.json`; this ticket cannot close on app code alone.
