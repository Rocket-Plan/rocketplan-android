# Code Review — RP-BUG-269 & RP-BUG-270 (Android signup-flow fixes)

**Reviewer:** Claude
**Repo / branch:** `Rocketplan_android` · `master` (uncommitted working tree)
**Scope:** SMS-verification gating (RP-BUG-269) + invite-based company joining (RP-BUG-270)
**Status:** Final (3 rounds)
**Started:** 2026-06-10 22:20:48 PDT

## Uncommitted files at review start (round 1)
```
 M app/src/main/AndroidManifest.xml
 M app/src/main/java/.../MainActivity.kt
 M app/src/main/java/.../data/api/CompanyApi.kt
 M app/src/main/java/.../data/api/RetrofitClient.kt
 M app/src/main/java/.../data/model/AuthModels.kt
 M app/src/main/java/.../data/repository/AuthRepository.kt
 M app/src/main/java/.../data/storage/SecureStorage.kt
 M app/src/main/java/.../ui/auth/JoinCompanyFragment.kt
 M app/src/main/java/.../ui/auth/JoinCompanyViewModel.kt
 M app/src/main/java/.../ui/auth/SmsCodeVerifyFragment.kt
 M app/src/main/res/layout/fragment_join_company.xml
 M app/src/main/res/values/strings.xml
?? app/src/main/java/.../util/InviteLink.kt   (new)
```

## Findings & resolution

Cross-checked against the mongoose backend (`CompanyCheckController`, `routes/api.php`,
`config/webapp.php`) and the iOS reference (`URL+RP.swift`, `SceneDelegate.swift`,
`CompanyService.swift`, the `*.entitlements` associated-domains).

| ID | Issue | Final |
|----|-------|-------|
| **B1** | RP-BUG-270 called `GET api/companies/uuid/{uuid}` — route does not exist | ✅ Fixed → `POST api/companies/check` body `{uuid}`, `CompanyEnvelope{data}` (matches `CompanyCheckController`) |
| **B2** | Deep link only matched custom schemes; real invite links are `https://` web URLs; parser rejected the web shape | ✅ Fixed (app side): `InviteLink.parse` handles both host- and path-based shapes; App Links hosts corrected to the iOS-verified Universal-Link hosts (`aasa`, `web-staging-mongoose-…`, `web-qa-mongoose-…`). ⚠️ **Email-link auto-open still requires `assetlinks.json` + `autoVerify="true"`** — see Remaining. |
| **B3** | `checkAuthenticationStatus` treated `refreshUserContext` failure (offline) as unverified → routed verified users to phone verification | ✅ Fixed → caches `sms_verified` in `SecureStorage`; falls back to cached value on failure |
| **S1** | Unverified users still got local company context (`setCompanyId`) → other company-scoped calls could 403 → sign-out | ✅ Fixed → entire company-context block gated behind `isSmsVerified` |
| **S2** | Interceptor exemption only covered `/api/active-company` | ✅ Fixed → any 403 with `sms…verified` body exempt from forced sign-out (`peekBody`, non-consuming) |
| **S3** | In-app join passed raw text as UUID; pasted links failed | ✅ Fixed → `extractUuid` runs input through `InviteLink.parse`, falls back to bare UUID |
| **S4** | (found round 2) Stale `needsOnboarding` dumped a freshly auto-joined invitee into onboarding | ✅ Fixed → on auto-join success, navigate to `nav_projects` + `return@withContext` |

## Verified-correct details
- `AuthModels`: `sms_verified_at` + `isSmsVerified`; request `CheckCompanyByUuidRequest{uuid}` serializes to the backend's expected `{ "uuid": … }`.
- `AuthRepository.refreshUserContext`: caches verified flag on success; company context fully gated.
- `RetrofitClient`: `peekBody(1024)` does not consume the body for downstream handlers.
- Nav wiring: `phoneVerificationFragment` args (`userId` required, `email` defaulted) satisfied; `nav_projects` is a valid global destination.

## Remaining (not app code) — RP-BUG-270 deep-link auto-open
`autoVerify="false"` and no Digital Asset Links file is published. For Android 12+ to hand an
emailed `https://…/invite-redirect/…` link to the app automatically, the web hosts must serve
`/.well-known/assetlinks.json` (package + signing-cert SHA-256) **and** the filter must set
`android:autoVerify="true"`. Until then: in-app join (paste link/UUID) and custom-scheme links
work; the seamless email-link auto-open does not.

## Minor (non-blocking)
- `MainActivity` invite auto-join ignores `addCompanyUser`/`setActiveCompany` `Result`s and
  clears the pending invite on resolve-success regardless — a failed join is silently dropped.
  Consider retaining the pending UUID on failure to retry next launch.
- First offline launch immediately after updating to this build (before any online refresh)
  defaults the cached verified flag to `false` → a verified user is routed to verification once;
  self-heals after one online launch.

## Verdict
All seven code findings resolved; RP-BUG-269 fully fixed. RP-BUG-270 in-app join works
end-to-end; the emailed-link auto-open is gated on `assetlinks.json` deployment (backend/ops).
