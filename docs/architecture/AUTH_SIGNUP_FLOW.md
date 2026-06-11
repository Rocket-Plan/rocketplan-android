# Auth / Sign-up Flow (Android)

**Scope:** the full client-side journey from app launch / sign-in through SMS
verification, company resolution, and into the app — plus the invited-employee
auto-join branch. Traced from source on 2026-06-10 (1.30 (35), `master`, after
RP-BUG-269/270 `90078ec`).

**Authoritative code:**
- `MainActivity.kt` — the relaunch/auth gate sequence (`checkAuthenticationStatus`,
  `handleInviteDeepLink`, `handleOAuthCallback`). Android has **no single `viewState`
  enum** like iOS `AppViewModel`; routing is imperative across `MainActivity` + each
  auth fragment's own navigation.
- `app/src/main/res/navigation/mobile_navigation.xml` — the auth screen graph (start
  destination `emailCheckFragment`).
- `ui/auth/**` — the auth screens (`EmailCheckFragment`, `SignUpFragment`,
  `PhoneVerificationFragment`, `SmsCodeVerifyFragment`, `AccountTypeFragment`,
  `JoinCompanyFragment`, `FinalDetailsFragment`).
- `data/model/AuthModels.kt` — `CurrentUserResponse` (`smsVerifiedAt`, `isSmsVerified`,
  `companyId`, `companies`).
- `data/repository/AuthRepository.kt` — `refreshUserContext`, `resolveCompanyByUuid`,
  pending-invite storage, SMS-verified cache.
- `util/InviteLink.kt` — invite deep-link parser.
- `data/api/RetrofitClient.kt` — `unauthorizedInterceptor` (the 403-SMS-gate exemption).

> This doc is descriptive. If it disagrees with the code, the code wins — update this
> doc. It is the Android counterpart to the iOS `docs/architecture/AUTH_SIGNUP_FLOW.md`
> and supersedes the older `docs/ONBOARDING_FLOW.md` (which predates RP-BUG-269/270 and
> does not describe the SMS gate or the invite auto-join).

---

## 1. The route "state machine"

Unlike iOS (a single `AppViewModel.viewState` enum rendered by `AppContentView`),
Android splits routing in two:

1. **Relaunch / cold start** — `MainActivity.checkAuthenticationStatus()` runs a fixed
   gate sequence and navigates to the right destination (popping `emailCheckFragment`
   inclusive so there's no back-stack to the gate).
2. **Forward signup** — each fragment navigates to the next on user action via the nav
   graph actions.

### Gate order in `checkAuthenticationStatus()` (`MainActivity.kt:344`)

```
checkAuthenticationStatus()
  if !isLoggedIn                       → return (stay on emailCheckFragment = sign-in/up)
  refreshUserContext()                 → currentUser (or null offline)
  isSmsVerified = currentUser?.isSmsVerified ?: cachedSmsVerified   (offline fallback)
  companyId = getStoredCompanyId();  userId = getStoredUserId()
  needsOnboarding = (companyId == null && userId > 0)
  ── gate sequence ──
  if !isSmsVerified                    → phoneVerificationFragment   (SMS GATE)   return
  if pendingInviteUuid != null         → resolve → addUser → setActiveCompany → nav_projects  return
                                          (on resolve-failure: clear invite, fall through)
  if needsOnboarding                   → phoneVerificationFragment   (COMPANY GATE) return
  else                                 → nav_projects + ensureInitialSync()
```

| Condition | Destination | iOS analogue |
|---|---|---|
| not logged in | `emailCheckFragment` (sign in / sign up) | `.authentication` |
| `!isSmsVerified` | `phoneVerificationFragment` | `.authenticationVerifyMobile` |
| pending invite resolves | `nav_projects` (auto-join) | `AccountType.checkForInvitiation` |
| verified, no company | `phoneVerificationFragment` (then onboarding) | `.noCompany` |
| verified + company | `nav_projects` | `.home` |
| — (no equivalent) | — | `.companyApproval` (**Android has no approval gate**) |
| — (no equivalent) | — | `.mustUpdate` (no client version gate here) |

### Forward signup graph (`mobile_navigation.xml`, start = `emailCheckFragment`)

```
emailCheckFragment
├── (registered)     → loginFragment
├── (not registered) → signUpFragment ──► phoneVerificationFragment
│                                              └─► smsCodeVerifyFragment
│                                                     ├─(has company)→ nav_projects
│                                                     └─(no company) → accountTypeFragment
│                                                                        ├─(create)→ finalDetailsFragment → nav_projects
│                                                                        └─(join)  → joinCompanyFragment ──► nav_projects
│                                                                                              └─(create instead)→ finalDetailsFragment
└── (OAuth success) → nav_home/nav_projects   ⚠ bypasses SMS + company gates this session (see §5)
```

---

## 2. SMS verification

`isSmsVerified = !smsVerifiedAt.isNullOrBlank()` on `CurrentUserResponse`
(`AuthModels.kt`), where `smsVerifiedAt` is the server's `sms_verified_at`.

- A user whose `sms_verified_at` is **NULL on the backend has never completed SMS
  verification**. On their next launch the SMS gate (`MainActivity.kt`) routes them to
  `phoneVerificationFragment` — by design.
- The **backend independently enforces** the same check: company-context endpoints
  (`POST /api/active-company`, etc.) return **`HTTP 403 "Your sms code is not verified."`**
  until `sms_verified_at` is set (iOS / mongoose counterpart `MONGOOSE-BUG-028`).

### SMS-gate hardening implemented (RP-BUG-269, `90078ec`)

- **Company context gated** — `AuthRepository.refreshUserContext` skips the entire
  company-context block (`setActiveCompany` POST + local `companyId` +
  `RetrofitClient.setCompanyId`) when `!isSmsVerified`, so an unverified user fires no
  company-scoped calls. Caches `isSmsVerified` in `SecureStorage`.
- **403 exemption** — `RetrofitClient.unauthorizedInterceptor` peeks the body
  (non-consuming `peekBody(1024)`) and, for any `403` whose body contains both `sms` and
  `verified`, returns the response instead of forcing sign-out.
- **Offline fallback** — `MainActivity` falls back to the cached verified flag when
  `refreshUserContext` fails (offline / transient).

### ⚠ Differences from iOS (see §6 for the full list)

Android did **not** port iOS's RP-BUG-331/333 post-verify work. There is **no
in-session "verified" flag, no post-verify `refreshUserContext`, and no SMS-403 retry**.
The post-verify routing in `SmsCodeVerifyFragment` keys on `getStoredCompanyId()`, which
RP-BUG-269 gating leaves `null` for the very users it targets — so the "skip the
Create/Join chooser" branch is effectively dead (see §6, hole H1).

---

## 3. Sign-up & invited-employee branch

### Invite deep link

`MainActivity.handleInviteDeepLink()` (called from `onCreate` and `onNewIntent`) parses
the intent URI with `InviteLink.parse()` and stores the company UUID durably via
`AuthRepository.savePendingInviteCompanyUuid()` (`SecureStorage`, encrypted prefs —
**RP-BUG-328** lifecycle guard: not tied to a fragment).

`InviteLink.parse()` (`util/InviteLink.kt`) handles two shapes, anchoring on the
`invite-redirect` marker and taking the segment **immediately after** it as the company
UUID (**RP-BUG-327** guard against taking the wrong/last segment):

| Channel | URL shape | Parsed |
|---|---|---|
| Custom scheme | `rocketplan://invite-redirect/{companyUuid}[/{invitationUuid}]` | `host == invite-redirect` → `pathSegments[0]` |
| Web (https) | `https://<host>/…/invite-redirect/{companyUuid}[/{invitationUuid}]` | segment after `indexOf("invite-redirect")` |

### Auto-join

Auto-join happens **only** in `MainActivity.checkAuthenticationStatus` (at launch / auth
resolution): resolve via `resolveCompanyByUuid` (`POST /api/companies/check {uuid}` →
`CompanyEnvelope{data}`), then `addCompanyUser` → `setActiveCompany` → clear pending →
`nav_projects` + `ensureInitialSync`. On resolve failure it clears the pending invite and
falls through.

### In-app join (`JoinCompanyFragment` / `JoinCompanyViewModel`)

The "Join a Company" screen accepts a pasted invite link **or** a bare UUID;
`extractUuid` runs the input through `InviteLink.parse` then falls back to bare UUID.
Flow: `resolveCompanyByUuid` → `addCompanyUser` → `setActiveCompany` → `refreshUserContext`
→ `nav_projects`.

### Final Details (`FinalDetailsFragment` / `FinalDetailsViewModel`)

Collects first/last name (+ company name when `isCreating`). On submit:
`PUT /api/users/{id}` → (if creating) `POST /api/companies` → `addCompanyUser` →
`setActiveCompany` → `refreshUserContext` → verify `getPrimaryCompanyId()` → `nav_projects`.
**No optional-phone field** (cf. iOS RP-BUG-334).

---

## 4. Logs to watch

Android gate decisions are **local `Log.d(TAG=…"MainActivity")`** only — they do **not**
go to the remote `log_entries` store (unlike iOS, which logs `auth_sms`/`auth_company`
categories remotely). The only remote log on this path is `AuthRepository`'s
"Company context set" / "Skipped company context: user not SMS-verified".

```bash
# Trace a device's auth routing (local logcat only)
adb -s <serial> logcat -d -t 500 --pid=$(adb -s <serial> shell pidof -s com.rocketplantech.rocketplan) \
  | grep -iE "SMS-verified|pending invite|onboarding|navigating to projects|invite deep link"
```

| Source | Message | Means |
|---|---|---|
| `MainActivity` | `…not SMS-verified, redirecting to phone verification` | SMS gate |
| `MainActivity` | `Processing pending invite for company UUID …` | invite auto-join firing |
| `MainActivity` | `Successfully joined company via invite …` | auto-join succeeded |
| `MainActivity` | `…no company, redirecting to onboarding` | company gate |
| `AuthRepository` (remote) | `Skipped company context: user not SMS-verified` | RP-BUG-269 gate hit |

---

## 5. OAuth note

Google OAuth success in `EmailCheckFragment` navigates **directly to home**
(`actionEmailCheckFragmentToNavHome()`), bypassing the SMS and company gates for that
session. A relaunch re-applies the gates via `checkAuthenticationStatus`, so an
unverified/no-company OAuth user is corrected on the next cold start — but not within the
first session.

---

## 6. Parity gaps / holes vs iOS

Ordered by severity. iOS references are to its `AUTH_SIGNUP_FLOW.md`. Each hole is now
filed in `docs/BUG_TRACKER.md` (ticket column).

| # | Hole | iOS has | Ticket | Severity |
|---|------|---------|--------|----------|
| **H1** | **Post-verify routing dead.** `SmsCodeVerifyFragment` branches on `getStoredCompanyId()`, but RP-BUG-269 gating never caches `companyId` for an unverified user, and `verify()` never re-runs `refreshUserContext`. So an existing-company user who verifies is sent to the Create/Join chooser, not into the app. | RP-BUG-331 `routePostVerify` + RP-BUG-333 in-session flag | **RP-BUG-271** (P1) | High |
| **H2** | **Invite auto-join only at launch.** `AccountTypeFragment` does not resolve a pending invite. An invited user going through fresh signup (verify → AccountType) won't auto-join until they relaunch; meanwhile they see "Create / Join". | RP-BUG-328 `AccountType.checkForInvitiation()` (driven from `.task`+`.onAppear`, one-shot) | **RP-BUG-272** (P2) | High |
| **H3** | **No logout/exit on signup screens.** None of `phoneVerification / smsCodeVerify / accountType / joinCompany / finalDetails` offer sign-out. Because `checkAuthenticationStatus` pops `emailCheckFragment` inclusive, a force-routed authenticated-but-incomplete user has **no back stack** → stranded, can't sign in as someone else. | RP-BUG-329 `AuthSignHeader.onLogout` on every signup screen + WelcomeBack real logout | **RP-BUG-273** (P2) | High |
| **H4** | **No SMS-403 retry / stale-verified safety net.** RetrofitClient only exempts the 403 from sign-out; it does not retry. A just-verified user hitting the backend's ~60s stale `sms.verified` 403 silently fails the company call (it's inside `runCatching`); context isn't set until a later refresh. | RP-BUG-333 retry + `markSmsVerifiedInSession()`; root fix `MONGOOSE-BUG-028` | **RP-BUG-274** (P3) | Med |
| **H5** | **Auto-join swallows failures.** `MainActivity` ignores the `addCompanyUser`/`setActiveCompany` `Result`s and clears the pending invite on resolve-success regardless — a failed join is dropped, no retry. | (resolver returns to `loadData` re-route) | **RP-BUG-275** (P3) | Med |
| **H6** | **No company-approval gate.** Android `Company` has no `isApproved`; a member of an unapproved company goes straight to `nav_projects`. | `.companyApproval` / `WelcomeBackContentView`, `Company.isApproved` | **RP-BUG-276** (P3) | Med |
| **H7** | **Emailed-link auto-open not wired.** `AndroidManifest` filters are `autoVerify="false"` and no `/.well-known/assetlinks.json` is published, so an emailed `https://…/invite-redirect/…` link won't hand off to the app (only in-app paste + custom-scheme work). | Universal Links via AASA | **RP-BUG-277** (P3, needs ops) | Med |
| **H8** | **Gate decisions not remote-logged.** Routing is `Log.d` (local logcat) only; can't trace a user's auth routing from `log_entries` like iOS. | `auth_sms`/`auth_company`/`auth_recheck` remote categories | **RP-HD-005** (P3) | Low |
| **H9** | **OAuth bypasses gates first session.** Google OAuth → home without SMS/company check; corrected only on relaunch (§5). | gates apply uniformly via `loadData` | **RP-BUG-278** (P3) | Low |
| **H10** | **No `email_verified_at` model.** `CurrentUserResponse` has no `emailVerifiedAt`. Not currently a gate on either platform, noted for parity. | `User.emailVerifiedAt` exists | **RP-HD-006** (P3) | Low |
| **H11** | **No `InviteLink.parse` / `JoinCompanyViewModel` unit tests.** Documented follow-up from `90078ec`. | — | **RP-HD-007** (P3) | Low |

### Backend dependencies (shared with iOS)
- `MONGOOSE-BUG-025` — invite **email reconciliation** (the durable fix for the
  link-tapped-before-install case; neither client can solve it alone).
- `MONGOOSE-BUG-028` — `sms.verified` serves a stale 403 to a just-verified user
  (~60s cached auth user, not busted on verify) — the root cause behind H1/H4.

---

## 7. Related tickets

- **RP-BUG-269** — company activation not gated behind SMS verification (fixed `90078ec`;
  iOS `RP-BUG-330`).
- **RP-BUG-270** — invite-based company joining not implemented on Android (fixed
  `90078ec`; iOS `RP-BUG-327/328` area).
- iOS parity sources: `RP-BUG-327` (invite parser segment), `RP-BUG-328` (invite auto-join
  lifecycle), `RP-BUG-329` (logout on signup screens), `RP-BUG-330` (SMS gate),
  `RP-BUG-331` (post-verify routing), `RP-BUG-333` (post-verify stale-verified bounce),
  `RP-BUG-334` (Final Details optional-phone).
