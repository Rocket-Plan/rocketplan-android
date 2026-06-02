**Bug ID(s):** RP-BUG-002, RP-BUG-018
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation RP-BUG-002](../investigations/RP-BUG-002_printstacktrace_leak.md) · [Investigation RP-BUG-018](../investigations/RP-BUG-018_session_logging.md) · [Plan](./plan_rp_bug_002_018_logging_pii_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-002 / RP-BUG-018] Remove raw stderr/stdout logging of auth data in LoginViewModel

**Bug ID(s):** RP-BUG-002 (P0), RP-BUG-018 (P2)
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

Two related logging defects live in the auth UI layer and share a single root cause: sensitive auth data is emitted through raw JVM stream APIs (`Throwable.printStackTrace()` → `System.err`, and `println(...)` → `System.out`) instead of the app's `android.util.Log` convention.

- **RP-BUG-002 (P0):** `LoginViewModel.signIn()` calls `error?.printStackTrace()` on a failed sign-in. Auth exception stack traces (which can contain request/response context, tokens, or credentials) are written to `System.err`. `System.err` is not subject to `Log.isLoggable()` filtering and is commonly captured wholesale by crash/diagnostic collectors, so it can leak in production-flavored builds where `ENABLE_LOGGING` is on.
- **RP-BUG-018 (P2):** The same method logs a success path via `println("Sign in successful")` / `println("User ID: ${session?.user?.id}")`. While only the user id is printed today, the surrounding `AuthSession` object carries a Sanctum bearer `token` (confirmed below), and `println` bypasses logcat tag-based filtering. This is the same anti-pattern as RP-BUG-002, in the same method, and the fix is identical in shape.

The same `printStackTrace()` anti-pattern also exists in `ForgotPasswordViewModel.kt:106` (plus a `println` of the user email at line 98). It is in scope here because it is the exact same root cause in a sibling auth ViewModel; fixing only `LoginViewModel` would leave an identical P0-class leak one screen away.

**Are the two bugs genuinely related?** Yes. Same file, same method (`LoginViewModel.signIn()`), same root cause (raw `System.err`/`System.out` writes instead of `Log`), and a single shared fix (route through a tagged `Log` call and never log the session/token). They are not merely thematically grouped.

### Confirmed data model risk

`AuthSession` (`app/src/main/java/com/example/rocketplan_android/data/model/AuthModels.kt:52`) is:

```kotlin
data class AuthSession(
    val token: String,            // Sanctum bearer token — sensitive
    val user: CurrentUserResponse
)
```

So any future `println(session)` / `Log.d(TAG, "$session")` would dump a live bearer token. The fix must log only non-sensitive scalar fields (e.g. `user.id`), never the `AuthSession` or `token`.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/ui/login/LoginViewModel.kt` | Replace `println(...)` success logging (lines 167-170) with a tagged `Log.d` that logs only the user id. Replace `error?.printStackTrace()` (line 178) with a scrubbed `Log.e` message that logs exception class only by default and keeps raw throwables out of production-eligible auth logs. Add a `TAG` companion constant. |
| `app/src/main/java/com/example/rocketplan_android/ui/forgotpassword/ForgotPasswordViewModel.kt` | Same treatment: replace `println(...)` (line 98) and `error?.printStackTrace()` (line 106) with tagged `Log` calls; do not log the raw email and do not attach unsanitized auth errors by default. Add a `TAG` companion constant. |

No source changes are made by this plan; it documents the intended edits only.

## Implementation Notes

The codebase already uses `android.util.Log` with a `private const val TAG` companion convention (e.g. `MainActivity.kt:59`, `MainActivity.kt:503` `Log.e(TAG, "...", error)`). We follow that exact pattern. Logging stays gated behind `AppConfig.isLoggingEnabled` so production-prod builds (`ENABLE_LOGGING=false`) emit nothing, while dev/staging keep useful, filtered diagnostics.

### Step 1: Add imports and a TAG to `LoginViewModel`

```kotlin
import android.util.Log
// ...
class LoginViewModel(application: Application) : AndroidViewModel(application) {
    // ...
    companion object {
        private const val TAG = "LoginViewModel"
    }
}
```

### Step 2: Fix the success path (RP-BUG-018)

Before (lines 164-171):

```kotlin
if (result.isSuccess) {
    val session = result.getOrNull()
    _authSession.value = session
    if (AppConfig.isLoggingEnabled) {
        println("Sign in successful")
        println("User ID: ${session?.user?.id}")
    }
    _signInSuccess.value = true
}
```

After:

```kotlin
if (result.isSuccess) {
    val session = result.getOrNull()
    _authSession.value = session
    if (AppConfig.isLoggingEnabled) {
        // Log only the non-sensitive user id. Never log `session` or
        // `session.token` (Sanctum bearer token).
        Log.d(TAG, "Sign in successful (userId=${session?.user?.id})")
    }
    _signInSuccess.value = true
}
```

### Step 3: Fix the failure path (RP-BUG-002)

### Step 3a: Define failure-log redaction policy

Do not attach the raw `Throwable` to production-eligible auth logs unless we separately verify that the auth stack never includes tokens, credentials, emails, or raw response bodies in exception messages/causes. The default fix should log only safe metadata such as the exception class name, while reserving stack traces for stricter debug-only tooling if they are still needed later.

Before (lines 172-180):

```kotlin
} else {
    // Error message is already user-friendly from ApiError
    val error = result.exceptionOrNull()
    _errorMessage.value = error?.message ?: "Sign in failed. Please try again."

    if (AppConfig.isLoggingEnabled) {
        error?.printStackTrace()
    }
}
```

After:

```kotlin
} else {
    // Error message is already user-friendly from ApiError
    val error = result.exceptionOrNull()
    _errorMessage.value = error?.message ?: "Sign in failed. Please try again."

    if (AppConfig.isLoggingEnabled) {
        // Route through logcat (tag-filterable, gated by ENABLE_LOGGING)
        // instead of System.err. Log the exception type/message, not credentials.
        Log.e(TAG, "Sign in failed: ${error?.javaClass?.simpleName}")
    }
}
```

This keeps the failure log inside the filterable, build-gated logging channel rather than `System.err` while avoiding unsanitized throwable output in production-eligible auth logs. If deeper stack traces are needed later, add a stricter debug-only path rather than broadening the default auth log.

### Step 4: Apply the same fix to `ForgotPasswordViewModel`

Add `import android.util.Log` and a `private const val TAG = "ForgotPasswordVM"` companion, then:

```kotlin
// Success path (was: println("Password reset requested for: $emailValue"))
if (AppConfig.isLoggingEnabled) {
    Log.d(TAG, "Password reset requested")   // do not log the email
}

// Failure path (was: error?.printStackTrace())
if (AppConfig.isLoggingEnabled) {
    Log.e(TAG, "Password reset failed: ${error?.javaClass?.simpleName}")
}
```

### Step 5: Guardrail (optional, recommended)

Add a lint/CI grep check (or Detekt `ForbiddenMethodCall` rule) for `printStackTrace(` and bare `println(` under `app/src/main/java` so the anti-pattern cannot be reintroduced. Out of scope for the bug fix itself but called out as the durable prevention.

## Observability

### Current signals
- RP-BUG-002: stack trace dumped to `System.err`; not in logcat tags, not in Sentry, no remote log.
- RP-BUG-018: success/userId dumped to `System.out`; not tag-filterable, no remote log.

### After this fix
- Failures surface as scrubbed `Log.e(TAG=LoginViewModel/ForgotPasswordVM, ...)` messages that include safe exception metadata only — filterable in `adb logcat` by tag and gated by `AppConfig.isLoggingEnabled` (`BuildConfig.ENABLE_LOGGING`), so production-prod builds emit nothing.
- Success path logs only `userId`, never the `AuthSession` or `token`.

### Gaps not closed here
- No remote/Sentry capture of auth failures is added (the RP-BUG-002 investigation lists this as a "proposed" future enhancement). If desired later, send a scrubbed `Sentry.captureException(error)` with the token redacted — tracked separately, not blocking this fix.

### Redaction rule
- Never log raw `AuthSession`
- Never log `token`
- Never log raw email address
- Never attach unsanitized auth response bodies, exceptions, or stack traces by default

## Test Plan

- [ ] Static verification: `grep -rn "printStackTrace(" app/src/main/java --include="*.kt"` returns no matches; `grep -rn "println(" app/src/main/java --include="*.kt"` returns no matches in the auth ViewModels.
- [ ] Unit/compile: `./gradlew compileDevStandardDebugKotlin` succeeds.
- [ ] Manual QA (dev build, `ENABLE_LOGGING=true`):
  1. Prereq: install `devStandardDebug`, attach `adb logcat`.
  2. Action: attempt sign-in with bad credentials.
  3. Expected: failure logged under tag `LoginViewModel` via `Log.e`; nothing on `System.err`; no token/credentials in the log line.
  4. Action: sign in successfully.
  5. Expected: a single `Log.d` line `Sign in successful (userId=...)`; no `AuthSession`/token printed; no `System.out` `println`.
  6. Repeat for the forgot-password screen (no raw email logged).
- [ ] Manual QA (prod-like build, `ENABLE_LOGGING=false`): no auth log lines emitted at all.

## Rollback Plan

Pure logging change with no schema, persisted-data, or API impact. To revert, restore the original `println`/`printStackTrace()` lines in the two ViewModels. Low risk; behavior of the auth flow itself is unchanged.

## Dependencies

- Requires: none.
- Blocking: none. RP-BUG-002 and RP-BUG-018 are fixed together in this plan.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-002] Replaced `printStackTrace()` in the login/forgot-password flows with tag-filterable, build-gated `Log.e` so auth stack traces no longer leak to System.err in production.
- [RP-BUG-018] Stopped printing the session object/user id via `println`; sign-in success now logs only a non-sensitive user id through logcat and never logs the auth token.
```
