# Fix Plan: [RP-HD-005] Remote-log the auth/signup gate decisions

**Bug ID(s):** RP-HD-005
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in ddea51d)

---

## Progress

- **2026-06-11 (`3dff3e0`):** the build/env gating building block landed — `RemoteLogGate`
  interface + `RemoteLogGateAlwaysOn` default + `RemoteLogger.updateGate()` and an early-return in
  `log()`. This is **only the volume-control mechanism** (Step "build/env gating" below).
- **2026-06-11 (`ddea51d`) — done:** the `auth_sms` / `auth_company` / `auth_invite_join` remote
  logs now fire at each decision point in `MainActivity.checkAuthenticationStatus` (unverified
  redirect, fully-authenticated→projects, pending-invite start/success/3×failure, needs-onboarding).
  Payloads key on `userId` (+ `companyId`/`companyUuid`); **email was dropped** to honor the
  no-raw-user-text guidance. Success criterion met. RP-HD-005 → `fixed`.
- **Not covered (out of scope here):** the OAuth entry path (`EmailCheckFragment` Google sign-in)
  does its own routing and is not yet remote-logged; tracked under RP-BUG-278's surface if needed.

---

## Summary

The signup routing decisions in `MainActivity.checkAuthenticationStatus` (SMS gate, pending-invite
resolution, company gate, navigate-to-projects) are emitted with local `Log.d(TAG="MainActivity")`
only. They never reach the remote `log_entries` store, so a user's auth routing cannot be traced in
the wild — unlike iOS, which logs `auth_sms` / `auth_company` / `auth_recheck` remotely. This blocks
production diagnosis of the RP-BUG-271/274/278-class mis-routes just fixed in `6634854`.

Preventive hardening — no live failure. Add low-volume, one-shot remote INFO logs at each gate.

## Affected Code

| File | Change |
|------|--------|
| `MainActivity.kt` (`checkAuthenticationStatus` ~L391–443; OAuth handler ~L571–600) | At each gate decision, add a `remoteLogger.log(...)` alongside the existing `Log.d`. `remoteLogger` is already a field (`MainActivity.kt:89`, init L120) and is already used at L218/948/963. |
| (optional) `data/repository/AuthRepository.kt` | Already remote-logs "Company context set"; align category naming with the new MainActivity categories. |

## Implementation Notes

### Categories & fields (mirror iOS where possible)
- `auth_sms` — SMS gate hit. Fields: `sms_verified`, `destination`.
- `auth_company` — company gate / routed-to-projects. Fields: `has_company`, `company_count`,
  `destination`.
- `auth_invite_join` — invite auto-join attempt/result. Fields: `company_id`, `invite_outcome`
  (`success` | `add_user_failed` | `set_active_failed` | `resolve_failed`).

### Pattern (one-shot, INFO, all builds — low volume)
```kotlin
remoteLogger.log(
    LogLevel.INFO, "auth_company", "routing decision",
    mapOf(
        "sms_verified" to currentUser.isSmsVerified,
        "has_company" to (companyId != null),
        "company_count" to (currentUser.companies?.size ?: 0),
        "destination" to destinationName,   // "projects" | "accountType" | "phoneVerification" | "companyApproval"
    )
)
```
- One log per launch decision (not per frame). INFO level.
- **No raw user text / tokens / full URLs** (per the tracker's remote-logging guidance). IDs and
  booleans only.
- Reuse the existing `RemoteLogger`; no new infra.

## Test Plan

- [ ] Manual QA: drive each path (unverified → phone; verified+no company → accountType;
  verified+company → projects; invite present → auto-join) and confirm a single `log_entries` query
  reconstructs the routing (parity with iOS AUTH_SIGNUP_FLOW.md §4 SQL).
- [ ] Verify volume: exactly one routing log per cold-start decision; no per-frame spam.

## Rollback Plan

Pure additive logging. Remove the `remoteLogger.log` calls to revert; no behavior change.

## Dependencies

- None (RemoteLogger + `/api/logs/ios` already live, RP-BUG-045). Related: `RP-HD-006` (already
  models the verification fields these logs reference); `docs/architecture/AUTH_SIGNUP_FLOW.md` §6
  hole **H8**.

## Changelog Entry

```markdown
## [1.30] - 2026-06-XX

### Changed
- [RP-HD-005] Auth/signup gate decisions are now logged remotely (auth_sms / auth_company /
  auth_invite_join) so production routing can be traced.
```
