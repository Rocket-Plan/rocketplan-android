---
bug_id: RP-BUG-269
aliases: []
title: "Company activation (refreshUserContext → setActiveCompany, POST /api/active-company) is not gated behind SMS verification — the client has no awareness of sms_verified_at at all, so an SMS-unverified user who already belongs to a company fires the active-company call on every login/signup/startup, the backend rejects it with 403 \"Your sms code is not verified.\", and the global unauthorizedInterceptor treats that 403 as a session rejection and force-signs-out the user"
type: functional
classification: pre_existing_latent
source: code-trace
found_in: "1.30 (35) (master, code-traced 2026-06-10)"
found_at: "2026-06-10 21:58:40 PDT"
fixed_in: 06f5eb9
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_269_sms_gate_company_activation_2026-06-10.md
related_review: docs/reviews/code_review_rp_bug_269_270_2026-06-10.md
related_test: null
last_updated: 2026-06-10
related:
  - "iOS RP-BUG-330 — the same root-cause defect on iOS (startup company activation not gated behind the SMS-verification gate). This is the Android counterpart."
  - "iOS RP-BUG-331 — the post-verify routing counterpart; on Android the login path has no SMS gate at all (see §Related Android gap)."
---

# RP-BUG-269 — Company activation fires before any SMS-verification gate

> Cross-validated from the iOS signup-flow fix cluster (iOS **RP-BUG-330**). Code-trace on
> `master` (1.30 build 35), 2026-06-10. Same backend (mongoose) as iOS, so the 403 mechanism
> proven on iOS device console applies identically here.

## Symptom

A user whose account has **not** completed SMS verification (`sms_verified_at` NULL on the
backend) but who **already belongs to a company** signs in on Android. Instead of landing
cleanly in the app (or on a "verify your number" screen), the app fires a company-context
call the backend rejects with `403 "Your sms code is not verified."`, and the global
`unauthorizedInterceptor` interprets that 403 as a session rejection and **force-signs-out**
the user — potentially a sign-in → immediate sign-out loop.

This is reachable by any user who registered on web/iOS (or via an invite) without completing
SMS verification and then logs into Android.

## Root cause

`AuthRepository.refreshUserContext()` runs on **every** `signIn`, `signUp`,
`signInWithGoogle`, and cached-identity startup (`isLoggedIn`). When the user has any company,
it computes `selectedCompanyId` and **unconditionally** notifies the backend:

```kotlin
// AuthRepository.kt:419-429
// Notify the backend which company is active for this session
runCatching {
    val activeCompanyResponse = authService.setActiveCompany(SetActiveCompanyRequest(selectedCompanyId))
    ...
}
```

There is **no check on the user's SMS-verification status** before this call. In fact the
client has **zero awareness of verification state**: `sms_verified` / `verifiedAt` appears
**nowhere** in the Android codebase, and `CurrentUserResponse` (`AuthModels.kt:87-110`) does
not even deserialize an `sms_verified_at` field. The client therefore *cannot* gate on it
today.

Two compounding faults follow from the 403:

1. **`refreshUserContext`'s own `runCatching` swallows the failure** (logs only), so company
   context is silently left unset for the session — the user appears "logged in" but with no
   working company context.
2. **The OkHttp `unauthorizedInterceptor` fires first, at the network layer**, before the
   response returns to `runCatching`:

   ```kotlin
   // RetrofitClient.kt:137-159
   if ((code == 401 || code == 403) && authToken.get() != null) {
       val path = chain.request().url.encodedPath
       if (!path.contains("/auth/login") && !path.contains("/auth/google")) {
           // small JSON error → treat as a real auth rejection
           if (contentLength < 1000 && contentType.contains("json")) {
               onUnauthorized?.invoke()   // → MainActivity forced sign-out (wired at MainActivity:137)
           }
       }
   }
   ```

   `POST /api/active-company` is not `/auth/login` or `/auth/google`, and the
   "sms code is not verified" body is small JSON, so the interceptor invokes `onUnauthorized`
   → forced sign-out.

## Evidence

- **Code trace** (this repo, `master`): `AuthRepository.kt:419-429` (ungated `setActiveCompany`),
  `RetrofitClient.kt:137-159` (403 → forced sign-out), `MainActivity.kt:137` (`onUnauthorized`
  wired), `AuthModels.kt:87-110` (no `sms_verified_at` field).
- **`grep -rni "sms_verified|verifiedAt" app/src/main` → zero hits** — the client has no
  verification-state awareness whatsoever.
- **Backend 403 mechanism is device-proven on iOS** (iOS RP-BUG-330, user 945, `sms_verified_at`
  NULL): `POST /api/active-company` and `GET /users/{id}/companies` both return
  `403 "Your sms code is not verified."`. Same mongoose backend serves Android.

## Difference from iOS

| | iOS (RP-BUG-330) | Android (this bug) |
|---|---|---|
| Ungated call | `companyService.setActiveCompany` in `hydrateCompanies` | `authService.setActiveCompany` in `refreshUserContext` |
| 403 outcome | activation-failure recovery can flip `viewState = .error` (stranded on error screen) | `unauthorizedInterceptor` → **forced sign-out** |
| Verification awareness | reads `user.smsVerifiedAt` (gate exists, just not sequenced before the POST) | **no `sms_verified_at` field at all** — gate cannot exist without first adding it |

Same root cause (company activation not gated behind SMS verification); the Android
manifestation is arguably worse because the 403 triggers sign-out rather than an error screen.

## Suggested fix direction (not yet applied)

1. Add `sms_verified_at` (or equivalent) to `CurrentUserResponse` and read it in
   `refreshUserContext()`.
2. Skip the `setActiveCompany` POST (and any other company-scoped startup calls) when the user
   is SMS-unverified; route the user to phone/SMS verification instead.
3. Defense-in-depth: have `unauthorizedInterceptor` exclude the
   "sms code is not verified" 403 from the forced-sign-out path (it is a verification-gate
   response, not a session/credential rejection).

## Related Android gap (now addressed by this fix)

The Android **login** path (`EmailCheck` → `Login` → `nav_home`) previously had **no
SMS-verification gate at all** — an unverified existing user was never routed to a verify
screen (only the **sign-up** path reached phone/SMS verification). That was the Android
counterpart of the iOS RP-BUG-331 routing concern. The fix below adds the missing gate in
`MainActivity.checkAuthenticationStatus`.

## Fix (implemented 2026-06-10, reviewed 3 rounds — see related_review)

> Status: implemented + build-passing; committed to `master` as `06f5eb9`.
> Cross-checked against the mongoose backend.

1. **Client SMS-verification awareness** — `AuthModels.kt`: added
   `@SerializedName("sms_verified_at") smsVerifiedAt` + `isSmsVerified` to `CurrentUserResponse`.
2. **Gate company activation** — `AuthRepository.refreshUserContext()`: the entire company-context
   block (`setActiveCompany` POST **and** `saveCompanyId` / `setCurrentCompanyId` /
   `RetrofitClient.setCompanyId`) is now inside `if (currentUser.isSmsVerified)`. Unverified
   users get **no** company context and **no** active-company call; the skip is remote-logged
   (`reason = "sms_unverified"`). *(covers the original fix step 2 + review item S1)*
3. **Interceptor no longer signs out on the verification 403** — `RetrofitClient.kt`: any `403`
   whose body contains `sms` + `verified` (path-agnostic, via non-consuming `peekBody(1024)`) is
   exempt from the forced-sign-out path. *(fix step 3 + review item S2)*
4. **Route unverified users to verification** — `MainActivity.checkAuthenticationStatus`: after
   `refreshUserContext`, unverified users are navigated to `phoneVerificationFragment`. The
   verified flag is **cached in `SecureStorage`** (`saveSmsVerified`/`getSmsVerifiedSync`) and
   used as a fallback when `refreshUserContext` fails offline, so a verified user is **not**
   misrouted on an offline launch. *(review item B3)*
5. **Post-verify routing by membership** — `SmsCodeVerifyFragment`: after SMS-verify success, a
   user who already has a stored company goes straight to `nav_projects`; only company-less users
   continue to the Create/Join chooser. *(addresses the iOS RP-BUG-331 trap)*

### Remaining / follow-up
- **Tests** (`related_test`): unit coverage for the gate (verified vs unverified → `setActiveCompany`
  called or not), the interceptor exemption, and the offline cached-fallback is **not yet written**.
- Minor: the offline cached flag defaults to `false` on the very first offline launch after
  updating (before any online refresh) → a verified user is routed to verification once;
  self-heals after one online launch.
