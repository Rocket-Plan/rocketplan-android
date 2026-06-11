# Fix Plan: RP-BUG-270 — Implement invite-based company joining on Android

**Bug:** [RP-BUG-270](../investigations/RP-BUG-270_invite_based_company_join_not_implemented_2026-06-10.md)
**Date:** 2026-06-10 · **Author:** Claude · **Priority:** P2
**iOS reference:** RP-BUG-327 (parser), RP-BUG-328 (resolver lifecycle) · **Backend:** MONGOOSE-BUG-025

## Goal

Let a user who is invited to a company actually **join it** on Android — both via the
in-app "Join Company" screen and via tapping an invite link. Today none of this exists
(no deep-link filter, `onNewIntent` handles only OAuth, `JoinCompanyViewModel` is empty,
"Join Company" only offers "Create instead").

This is **net-new feature work**, so the plan is staged: a usable in-app path first (Phase 1),
then deep-link support (Phase 2), with a backend-reconciliation escape hatch (Phase 3) that
can make Phase 2 largely unnecessary.

## Phase 0 — pin down the backend contract (before any UI)

1. From mongoose, document the exact endpoints and shapes:
   - resolve a company by invite UUID (iOS uses `getCompanyByUUID`) — path, response, error codes.
   - link the user to the company by invite (iOS `addCompanyEmployee` / Android already has
     `companyApi.addCompanyUser(companyId, userId)` — confirm it accepts an invite context, or
     whether a dedicated "accept invitation" endpoint exists).
   - the two invite URL shapes and which segment is the **company** UUID:
     - copy/share: `…/invite-redirect/{companyUuid}` (one segment)
     - email: `…/invite-redirect/{companyUuid}/{invitationUuid}` (two segments)
   - **The company UUID is the FIRST segment after `invite-redirect/`, not the last.** iOS
     RP-BUG-327 shipped the last-segment bug; do not repeat it.
2. Decide whether MONGOOSE-BUG-025 (server-side email reconciliation on signup) is shipping —
   if so, much of Phase 2 becomes optional (see Phase 3).

## Phase 1 — in-app "Join Company" (smallest usable slice)

1. Implement `JoinCompanyViewModel` (currently empty):
   - input: an invite link or company UUID (paste/enter), validated.
   - `resolveCompanyByUuid(uuid)` → `addCompanyUser(companyId, userId)` →
     `setActiveCompany(companyId)` → `refreshUserContext()`.
   - expose `isLoading` / `errorMessage` / `joinComplete` LiveData (mirror `FinalDetailsViewModel`).
2. Update `JoinCompanyFragment` to add an input field + "Join" button (keep "Create instead").
   On `joinComplete`, navigate to `nav_home` (popUpTo `emailCheckFragment`, like the other
   terminal auth actions).
3. Reuse the existing `AuthRepository.addCompanyUser` / `setActiveCompany`; add
   `resolveCompanyByUuid` to `AuthRepository` + `CompanyApi`.

## Phase 2 — invite deep-link support

1. Add a `VIEW` intent-filter in `AndroidManifest.xml` for the `invite-redirect` web route,
   for **all** build flavors (match the host(s) used by copy/share and email links). Handle
   both one- and two-segment paths via `pathPattern`/`pathPrefix`.
2. Add an invite parser (e.g. `InviteLink.parse(uri): { companyUuid, invitationUuid? }`) that
   takes the **first** path segment after `invite-redirect/` as the company UUID. Unit-test it
   against both URL shapes (this is the iOS RP-BUG-327 regression guard).
3. In `MainActivity.onNewIntent` (and `onCreate` for cold-start), branch: OAuth callback →
   `handleOAuthRedirect`; invite link → store the parsed company UUID and route into the
   Phase-1 join flow (or auto-join if the user is already authenticated).
   - Store the pending invite UUID durably (e.g. `SecureStorage`), **not** tied to a fragment
     lifecycle callback — iOS RP-BUG-328 was caused by binding resolution to `.onAppear`.
     Resolve it from a deterministic point (auth-state resolution), not a view callback.

## Phase 3 — prefer backend email reconciliation (escape hatch)

If MONGOOSE-BUG-025 ships, a user signing up with an email that has a pending invite is
auto-joined server-side. In that case:
- Phase 1 still has value (explicit join by code/link).
- Phase 2's pre-install case is fully covered by the backend, so the deep-link work can be
  deprioritized to "nice to have" for the already-installed case.
Re-scope Phase 2 once the backend decision is known.

## Tests

- Unit (`InviteLink.parse`): one-segment and two-segment URLs both yield the correct
  **company** UUID (guards the iOS RP-BUG-327 trap).
- Unit (`JoinCompanyViewModel`): resolve → addUser → setActiveCompany → refresh happy path;
  resolve-failure surfaces an error and does not navigate.
- Manual (device): tap an emailed invite link with the app installed → lands in join flow /
  auto-joins; "Join Company" with a pasted link works; "Create instead" still works.

## Lifecycle

`open → planned` (on landing this doc). Likely shipped in slices: Phase 1 first
(`→ fixed` once the in-app join works), Phase 2 as a follow-up. Note in the tracker if it
ships partially so `release_state` reflects what actually works.

**Update 2026-06-10:** Phases 0–2 implemented + reviewed (3 rounds, see
`docs/reviews/code_review_rp_bug_269_270_2026-06-10.md`); state → `fixed` (in-app join works
end-to-end) on the `master` working tree (commit SHA pending). **Two items remain:**
(1) the emailed-link **auto-open** needs `assetlinks.json` published on the three hosts +
`autoVerify="true"` (backend/ops — currently `autoVerify="false"`, so https links don't
auto-open the app); (2) the Phase-0/Phase-2 **tests** (`InviteLink.parse` over both URL shapes;
`JoinCompanyViewModel` happy/failure) are not yet written. Phase 3 (backend reconciliation,
MONGOOSE-BUG-025) remains the preferred long-term path.

## Notes / risk

- Coordinate with the backend (MONGOOSE-BUG-025) before investing in Phase 2 — server-side
  reconciliation may make the client deep-link path redundant for the common case.
- This is the largest of the two cross-validated findings; treat estimates as scoping, not
  commitments, until Phase 0 confirms the endpoints.
