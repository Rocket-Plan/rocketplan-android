# Fix Plan: [RP-HD-006] Model email_verified_at on CurrentUserResponse

**Bug ID(s):** RP-HD-006
**Author:** Claude
**Date:** 2026-06-11
**State:** approved (implemented in 6634854)

---

## Summary

`CurrentUserResponse` did not model `email_verified_at` (iOS carries `User.emailVerifiedAt`). Not a
gate today — preventive parity so a future email-verification gate isn't silently blind.

Fix: add the field + a derived `isEmailVerified` accessor.

## Affected Code

| File | Change |
|------|--------|
| `data/model/AuthModels.kt` | Add `@SerializedName("email_verified_at") val emailVerifiedAt: String? = null` to `CurrentUserResponse`; add `val isEmailVerified get() = !emailVerifiedAt.isNullOrBlank()` (mirrors the existing `isSmsVerified`). |

## Implementation Notes (as built)
```kotlin
@SerializedName("email_verified_at")
val emailVerifiedAt: String? = null
...
val isEmailVerified: Boolean get() = !emailVerifiedAt.isNullOrBlank()
```
Additive, nullable, default `null` — no behavior change; nothing reads it yet.

## Test Plan

- [ ] Unit (with RP-HD-007 batch): `CurrentUserResponse` deserializes `email_verified_at` present →
  `isEmailVerified == true`; absent/blank → `false`.

## Rollback Plan

Field is inert; remove if unused.

## Dependencies

- None. Enables a possible future email-verification gate. Sibling of `isSmsVerified` (RP-BUG-269).

## Changelog Entry

```markdown
### Changed
- [RP-HD-006] Model `email_verified_at` on the current-user payload for parity with iOS (preventive;
  no user-facing change yet).
```
