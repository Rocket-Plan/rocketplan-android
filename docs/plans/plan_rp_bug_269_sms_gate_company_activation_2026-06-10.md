# Fix Plan: RP-BUG-269 — Gate company activation behind SMS verification

**Bug:** [RP-BUG-269](../investigations/RP-BUG-269_company_activation_not_gated_behind_sms_verification_2026-06-10.md)
**Date:** 2026-06-10 · **Author:** Claude · **Priority:** P1
**iOS counterpart:** RP-BUG-330

## Goal

Stop the app from firing `setActiveCompany` (`POST /api/active-company`) for an SMS-unverified
user, and stop the resulting `403 "Your sms code is not verified."` from force-signing-out the
user. Root cause: the client has **no awareness of `sms_verified_at`** and activates the
company unconditionally in `AuthRepository.refreshUserContext()`.

Three layers, smallest-blast-radius first. Layers 1+2 are the fix; layer 3 is defense-in-depth.

## Phase 0 — confirm the backend contract (before coding)

1. Confirm `GET /api/auth/user` returns an SMS-verification field and its exact JSON key/shape
   (`sms_verified_at` timestamp vs a boolean). Check mongoose `UserResource` / the iOS
   `User.smsVerifiedAt` decode to mirror the key. **Do not guess the key** — the whole fix
   hinges on reading it correctly.
2. Confirm which endpoints 403 for unverified users (iOS RP-BUG-330 proved `POST /api/active-company`
   and `GET /users/{id}/companies`). Note any others reached during `refreshUserContext`/startup.

## Phase 1 — make the client SMS-verification-aware

1. Add the field to `CurrentUserResponse` (`AuthModels.kt`), e.g.:
   ```kotlin
   @SerializedName("sms_verified_at")
   val smsVerifiedAt: String? = null
   ```
   plus a derived `val isSmsVerified: Boolean get() = !smsVerifiedAt.isNullOrBlank()`
   (adjust if the backend returns a boolean instead).
2. Add the same field to `User` if any startup path deserializes into it (audit call sites).

## Phase 2 — gate the activation call

In `AuthRepository.refreshUserContext()` (`AuthRepository.kt:404-455`), wrap the
company-context block so the **server notification is skipped when unverified**:

1. If `!currentUser.isSmsVerified`: **do not** call `authService.setActiveCompany(...)`.
   - Still allowed: cache company name/id locally and set `RetrofitClient.setCompanyId` only if
     that does not trigger other gated calls. Safer default — **leave company context unset**
     for unverified users and let the verify flow re-run `refreshUserContext` after success.
   - Emit a remote log (`source = "refreshUserContext"`, `reason = "sms_unverified"`) so the
     skip is observable.
2. Apply the same guard to `signIn` / `signUp` / `signInWithGoogle` indirectly (they all route
   through `refreshUserContext`, so gating there is sufficient — verify no other path calls
   `setActiveCompany` directly during startup; `FinalDetailsViewModel.finish()` is post-verify
   and intentionally calls it — leave that one alone).

## Phase 3 — defense-in-depth: don't sign out on the verification 403

In `RetrofitClient.unauthorizedInterceptor` (`RetrofitClient.kt:137-159`), exclude the
verification-gate 403 from the forced-sign-out path:

1. For a 403 on `/api/active-company` (and any other verification-gated path from Phase 0),
   inspect the small JSON body for the "sms code is not verified" message (or a stable error
   code if the backend provides one) and **do not** invoke `onUnauthorized`.
2. Keep genuine 401/credential rejections signing out. A verification 403 is a gate, not a
   session-invalid signal.

   > Note: reading the error body in an interceptor consumes it — peek via
   > `response.peekBody(1024)` so the downstream `runCatching` still sees the body.

## Phase 4 — route unverified users to verification (folds in the RP-BUG-331-area gap)

The login path (`EmailCheck → Login → nav_home`) currently has no SMS gate. After Phase 1
makes verification state available:

1. In the post-login routing (`LoginFragment`/`MainActivity` startup decision), if the user is
   authenticated but `!isSmsVerified`, navigate to `phoneVerificationFragment` instead of
   `nav_home`.
2. After SMS-verify success, re-run `refreshUserContext()` (now it will activate the company)
   and proceed into the app — do **not** force every existing-company user through the
   Create/Join `AccountType` chooser (that is the Android analogue of the iOS RP-BUG-331 trap;
   only brand-new users with no company should see Create/Join).

## Tests

- Unit (`AuthRepository`): unverified user with a company → `setActiveCompany` is **not** called;
  verified user → it **is** called. Use a fake `AuthService`.
- Unit (`AuthRepository`): `refreshUserContext` returns success for an unverified user without
  throwing / without clearing the session.
- Unit (interceptor): a 403 with an "sms not verified" JSON body on `/api/active-company` does
  **not** invoke `onUnauthorized`; a 401 still does.
- Manual (device): log in as an unverified user who has a company → no sign-out loop; user lands
  on the verify screen; after verifying, company context loads and the app opens.

## Lifecycle

`open → planned` (on landing this doc). `→ fixed` when Phases 1–3 + tests land (Phase 4 may be
split into a follow-up if routing review wants it separate, but it is the user-visible half).

**Update 2026-06-10:** Phases 1–4 implemented + reviewed (3 rounds, see
`docs/reviews/code_review_rp_bug_269_270_2026-06-10.md`); state → `fixed` on the `master`
working tree (commit SHA pending). **Tests (the explicit Phase-1–3 test list above) are not yet
written** — the only remaining work for this bug.

## Notes / risk

- Phase 2's "leave context unset for unverified" is the conservative choice; verify it doesn't
  break any screen that assumes a company id before verification (there shouldn't be one — an
  unverified user should never reach project screens).
- Keep the `runCatching` around `setActiveCompany` even after gating — it is still correct
  defense for transient failures of the verified path.
