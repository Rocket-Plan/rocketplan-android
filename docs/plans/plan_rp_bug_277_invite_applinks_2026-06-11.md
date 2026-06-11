# Fix Plan: [RP-BUG-277] Emailed invite link auto-open (Android App Links)

**Bug ID(s):** RP-BUG-277
**Author:** Claude
**Date:** 2026-06-11
**State:** draft

---

## Summary

An invited employee taps an emailed `https://…/invite-redirect/{companyUuid}` link on a device with
the app installed; Android opens it in the **browser**, so the in-app auto-join never fires. Only the
in-app paste flow (`JoinCompanyFragment`) and custom-scheme links reach `InviteLink.parse`.

This is **two-part and partly outside the app**: the manifest `https` filters are
`autoVerify="false"`, and no Digital Asset Links file (`/.well-known/assetlinks.json`) is published
on the invite hosts. Android 12+ requires **both** to hand an `https` link to the app automatically.

> This ticket **cannot close on app code alone** — it depends on a backend/ops task. Sequence the ops
> work first, then flip the manifest flag, because turning on `autoVerify` without a live
> `assetlinks.json` silently fails verification.

## Affected Code / Ops

| Item | Owner | Change |
|------|-------|--------|
| `assetlinks.json` on each invite host (prod + staging/QA) | backend/ops | Publish at `/.well-known/assetlinks.json` with package `com.rocketplantech.rocketplan` and the **release signing cert** SHA-256 fingerprint (and the upload/debug cert for QA hosts). Coordinate with the iOS AASA hosts already verified. |
| `app/src/main/AndroidManifest.xml` | app | Set `android:autoVerify="true"` on the `https` invite `intent-filter`(s) — **only after** asset links are live. |
| `MainActivity` deep-link handling | app | Verify `handleInviteDeepLink` / `onNewIntent` accepts the verified `https` `VIEW` intent and routes it through the same `InviteLink.parse` → pending-invite path the custom scheme uses (likely already works once the filter verifies; confirm). |

## Implementation Notes

### Step 0 (ops, blocking): publish assetlinks.json
```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.rocketplantech.rocketplan",
    "sha256_cert_fingerprints": ["<RELEASE_SIGNING_CERT_SHA256>"]
  }
}]
```
Get the fingerprint via `keytool -list -v -keystore <release.keystore>` or the Play Console (App
integrity → App signing key certificate, SHA-256). Include the Play **app-signing** cert if Play
re-signs.

### Step 1 (app): enable autoVerify
```xml
<intent-filter android:autoVerify="true">
  <action android:name="android.intent.action.VIEW"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <category android:name="android.intent.category.BROWSABLE"/>
  <data android:scheme="https" android:host="<invite-host>" android:pathPrefix="/invite-redirect"/>
</intent-filter>
```

### Step 2 (app): confirm routing
Ensure the verified `https` `VIEW` intent reaches `InviteLink.parse` exactly like the custom-scheme
path. Add a startup debug log of App Links verification status.

## Test Plan

- [ ] Verify domain: `adb shell pm get-app-links com.rocketplantech.rocketplan` shows `verified` for
  the invite host after install.
- [ ] Manual QA:
  1. Prereq: `assetlinks.json` live on host; app built with `autoVerify="true"` installed.
  2. Action: tap an emailed `https://…/invite-redirect/{uuid}` link.
  3. Expected: the app opens (not the browser) and the pending-invite auto-join runs.
- [ ] Negative: a host without asset links must NOT hijack unrelated links.

## Rollback Plan

Set `autoVerify="false"` (or revert the manifest filter) to fall back to browser handling; the
custom-scheme + in-app paste paths are unaffected. `assetlinks.json` is inert if the manifest doesn't
opt in.

## Dependencies

- **Blocking:** ops task to publish `assetlinks.json` with the correct cert fingerprint on every
  invite host. App-side change is a one-line manifest flip gated on that.
- Related: `RP-BUG-270` (in-app join, shipped `90078ec`); `docs/architecture/AUTH_SIGNUP_FLOW.md` §6
  hole **H7**.

## Changelog Entry

```markdown
## [1.30] - 2026-06-XX

### Fixed
- [RP-BUG-277] Emailed invite links now open the app directly (Android App Links) and auto-join the
  company, instead of opening in the browser.
```
