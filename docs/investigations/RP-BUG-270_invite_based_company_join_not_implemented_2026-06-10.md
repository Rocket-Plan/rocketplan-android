---
bug_id: RP-BUG-270
aliases: []
title: "Invite-based company joining is not implemented on Android — there is no invite-redirect deep-link handler (the manifest registers only the oauth2 scheme), onNewIntent handles only the OAuth callback, JoinCompanyViewModel is an empty stub, and the \"Join Company\" screen offers only a \"Create instead\" button, so a user invited to a company (via emailed or copied invite link) cannot join it on Android"
type: functional
classification: feature_gap
source: code-trace
found_in: "1.30 (35) (master, code-traced 2026-06-10)"
found_at: "2026-06-10 21:58:40 PDT"
fixed_in: 06f5eb9   # in-app join; emailed-link auto-open still gated on assetlinks.json (see Remaining)
released_in: null
state: fixed   # in-app join path; emailed-link AUTO-OPEN still gated on assetlinks.json deployment (backend/ops) — see "Remaining"
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_270_invite_company_join_2026-06-10.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
related:
  - "iOS RP-BUG-327 — emailed invite link parsed to the wrong UUID. On iOS the feature EXISTS but had a parser defect; on Android the feature is absent entirely, so this is a gap, not the same defect."
  - "iOS RP-BUG-328 — invite auto-join resolution rides on a lifecycle callback. N/A on Android (no auto-join resolver exists)."
  - "mongoose MONGOOSE-BUG-025 — backend email reconciliation; if implemented server-side, invite join could work independent of any client deep-link parsing."
---

# RP-BUG-270 — Invite-based company joining is unimplemented on Android

> Surfaced while cross-validating the iOS signup-flow fix cluster (iOS **RP-BUG-327 / 328**)
> against Android. **Not** a port of the iOS defect: iOS had a *parser bug* in an existing
> feature; Android never built the feature. Filed as a `feature_gap` because the user-visible
> outcome (an invited user cannot join the company) is concrete.

## Symptom

A user is invited to a company — either via the **emailed** invite link
(`…/invite-redirect/{companyUuid}/{invitationUuid}`) or the **copied/shared** invite link
(`…/invite-redirect/{companyUuid}`). On Android:

- Tapping the link does **nothing** in the app (no deep-link handler is registered for it).
- Reaching the in-app "Join Company" screen during signup presents **only** a
  "Create a company instead" button — there is no field to enter a company code/UUID and no
  invite resolution.

The user has no path to join the company they were invited to. The only way forward is to
create a brand-new company.

## Root cause — three independent absences

1. **No invite deep-link intent filter.** `AndroidManifest.xml` registers only
   `MAIN`/`LAUNCHER` and a single `VIEW` intent-filter for the OAuth callback
   (`scheme=rocketplan*`, `host=oauth2`). There is no filter for the `invite-redirect`
   web route, so an invite URL is never delivered to the app.

2. **`onNewIntent` handles only OAuth.** `MainActivity.onNewIntent` (`MainActivity.kt:436-450`)
   forwards `intent?.data` solely to `handleOAuthRedirect(...)`. Even if an invite URL reached
   the activity, nothing would parse `companyUuid` / `invitationUuid` or resolve the company.

3. **The "Join Company" flow is a stub.**
   - `JoinCompanyViewModel` (`ui/auth/JoinCompanyViewModel.kt`) is literally
     `class JoinCompanyViewModel : ViewModel()` — no logic.
   - `JoinCompanyFragment` (`ui/auth/JoinCompanyFragment.kt`) wires only a back button and a
     "Create instead" button (`actionJoinCompanyFragmentToFinalDetailsFragment(isCreating = true)`).
   - There is no company-by-UUID lookup, no `addCompanyEmployee`/`addCompanyUser`-by-invite,
     and `ContextController`-style invite-UUID storage does not exist on Android.

## Evidence

- `AndroidManifest.xml` — only `oauth2` `VIEW` filter; `grep -rni "invite" app/src/main` →
  the sole hit is the `joinCompanyCard` click in `AccountTypeFragment`.
- `MainActivity.kt:436-450` — `onNewIntent` → `handleOAuthRedirect` only.
- `ui/auth/JoinCompanyViewModel.kt` — empty class.
- `ui/auth/JoinCompanyFragment.kt:37-47` — "Create instead" is the only action.
- `grep -rni "invite-redirect|getCompanyByUuid|companyByUuid|inviteCode" app/src/main` →
  zero hits.

## Difference from iOS

iOS `RP-BUG-327` was a **parser defect** (`URL.parseEmployeeSignupLink` took the *last* path
segment, so the two-segment emailed link stored the invitation UUID instead of the company
UUID) in a feature that otherwise works. iOS `RP-BUG-328` was the resolver being bound to an
unreliable `.onAppear`. On Android there is **no parser and no resolver to fix** — the entire
invite-join capability is missing.

## Fix (implemented 2026-06-10, reviewed 3 rounds — see related_review)

> Status: in-app join implemented + build-passing; committed to `master` as `06f5eb9`.
> **The emailed-link AUTO-OPEN is not yet functional** — it depends on hosting
> `assetlinks.json` (see Remaining). Cross-checked against the mongoose backend + iOS reference.

1. **Resolve endpoint** — `CompanyApi.kt` + `AuthRepository.resolveCompanyByUuid`: uses the real
   backend route `POST /api/companies/check` with body `{ "uuid": … }` (model
   `CheckCompanyByUuidRequest`, response `CompanyEnvelope{data}`), matching iOS
   `CompanyService.getCompanyByUUID`. *(review item B1 — the first draft used a non-existent
   `GET api/companies/uuid/{uuid}` route)*
2. **Parser handles both URL shapes** — `InviteLink.kt`: `parse` accepts the custom-scheme shape
   (`host == invite-redirect` → company UUID is `pathSegments[0]`) **and** the web shape
   (`https://<host>/invite-redirect/{companyUuid}[/{invitationUuid}]` → company UUID is the
   segment *after* `invite-redirect`). Taking the segment **after** `invite-redirect` (not the
   last) is the explicit guard against the iOS RP-BUG-327 trap. *(review item B2)*
3. **In-app "Join Company"** — `JoinCompanyViewModel` (full impl) + `JoinCompanyFragment` +
   `fragment_join_company.xml`: input field → `extractUuid` (runs input through `InviteLink.parse`,
   falls back to a bare UUID) → `resolveCompanyByUuid` → `addCompanyUser` → `setActiveCompany` →
   `refreshUserContext`, with loading/error states; on success navigates to `nav_projects`.
   *(review items S3 + the original fix step 3)*
4. **Durable pending-invite + deep-link handler** — `SecureStorage`
   (`savePendingInviteCompanyUuid`/get/clear, encrypted prefs) + `MainActivity.handleInviteDeepLink`
   (parses the link, stores the company UUID durably). On the next auth-state resolution, an
   authenticated user is **auto-joined** and routed straight to `nav_projects`. Storing the UUID
   durably (not on a fragment lifecycle callback) is the guard against the iOS RP-BUG-328 trap.
5. **Manifest intent-filters** — `AndroidManifest.xml`: custom-scheme filters
   (`rocketplan`, `rocketplan-dev`, `rocketplan-staging`, `rocketplan-local`, host
   `invite-redirect`) **and** `https` App Links for the iOS-verified Universal-Link hosts:
   `aasa.rocketplantech.com` (prod), `web-staging-mongoose-n5tr2spgf.rocketplantech.com` (stage),
   `web-qa-mongoose-br2wu78v1.rocketplantech.com` (dev/qa).

## Remaining — emailed-link auto-open gated on `assetlinks.json` (backend/ops)

The `https` App Links filter is currently `android:autoVerify="false"` and **no Digital Asset
Links file is published**. For Android 12+ to hand an emailed `https://…/invite-redirect/…`
link to the app automatically, each host must serve `/.well-known/assetlinks.json` (app package
+ signing-cert SHA-256) **and** the filter must set `android:autoVerify="true"`. Until then:
- ✅ In-app join (paste link or UUID) works.
- ✅ Custom-scheme links work.
- ❌ Tapping an emailed `https` invite link opens the browser, not the app.

**Tests** (`related_test`): a unit test for `InviteLink.parse` over both URL shapes (the
RP-BUG-327 regression guard) and the `JoinCompanyViewModel` happy/failure paths are **not yet
written**. Also prefer backend email reconciliation (mongoose MONGOOSE-BUG-025) so invite
membership does not depend on client-side deep-link delivery at all.
