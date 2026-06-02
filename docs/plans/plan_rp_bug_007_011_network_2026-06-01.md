**Bug ID(s):** RP-BUG-007, RP-BUG-011
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [RP-BUG-007 Investigation](../investigations/RP-BUG-007_photo_http_client.md) · [RP-BUG-011 Investigation](../investigations/RP-BUG-011_cert_pinning.md) · [Plan](./plan_rp_bug_007_011_network_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-007 / RP-BUG-011] Centralize OkHttp config and implement certificate pinning

**Bug ID(s):** RP-BUG-007, RP-BUG-011
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

Before implementation, resolve whether photo and thumbnail downloads always terminate on the API host or may target separate S3/CDN hosts. That host decision changes whether certificate pinning can be applied directly to the shared photo client, or whether pinning must be conditional on destination host coverage.

Two network-layer bugs share the same root: there is no single, security-hardened OkHttp configuration that all HTTP traffic flows through.

- **RP-BUG-007** — `PhotoCacheManager` constructs a bare `OkHttpClient()` with no timeouts, no logging, and no certificate pinning. Photo/thumbnail downloads therefore use JVM-default behavior (no connect/read timeout enforcement aligned with the rest of the app) and bypass whatever security policy `RetrofitClient` applies.
- **RP-BUG-011** — `RetrofitClient.certificatePinner` is `null`. It is applied conditionally (`certificatePinner?.let { certificatePinner(it) }`), so production does **not** crash (correcting the memory note about "placeholder pins that would crash production"); pinning is simply absent, leaving the app open to MITM on untrusted networks.

These are genuinely related, not just thematically grouped: the correct RP-BUG-007 fix is to make `PhotoCacheManager` reuse a `RetrofitClient`-owned client, and that reuse only delivers a security benefit once RP-BUG-011 populates the shared `CertificatePinner`. Fixing them together keeps both clients (`okHttpClient` and `plainHttpClient`) pinned from one source of truth and avoids a second pass over the same builder.

## Decision Needed Before Implementation

- If photos always use the API host, `PhotoCacheManager` can safely reuse the existing shared client with API-host pinning.
- If photos may use separate S3/CDN hosts, do **not** blindly switch photo downloads onto an API-pinned client. In that case, share timeout/logging configuration, but apply certificate pinning only for destinations explicitly covered by configured pins.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/api/RetrofitClient.kt` | Populate `certificatePinner` from build-time host/pin config; apply it to both `okHttpClient` and any host-covered shared client used for photos; emit a log on pin failure. |
| `app/src/main/java/com/example/rocketplan_android/config/AppConfig.kt` | Expose `apiHost` and the per-environment public-key pins (sourced from `BuildConfig`) so pins are not hard-coded across environments. |
| `app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt` | Default the injected `httpClient` to a shared client instead of a bare `OkHttpClient()`, applying certificate pinning only when the destination host is covered by configured pins. |
| `app/src/main/java/com/example/rocketplan_android/RocketPlanApplication.kt` | No change required (construction already omits the `httpClient` arg, so the new default applies); verify only. |
| `app/build.gradle.kts` | Add `CERT_PINS` / host BuildConfig fields per flavor+buildType (pins blank for dev to avoid lockout). |

## Implementation Notes

### Step 1 (RP-BUG-007): Share the existing plain client with PhotoCacheManager

`PhotoCacheManager` already adds its own `Authorization` header manually and downloads may target external/S3 URLs, so it must **not** inherit `RetrofitClient.authInterceptor` (which forces a Bearer + `X-Company-Id` onto every request). The correct shared client is `plainHttpClient`, which carries the logging interceptor and timeouts but no auth interceptor.

```kotlin
// PhotoCacheManager.kt — before
class PhotoCacheManager(
    context: Context,
    private val localDataService: LocalDataService,
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // TODO: Share OkHttpClient config (timeouts, cert pinning) with RetrofitClient
    //  to ensure consistent network behaviour across the app.
    private val httpClient: OkHttpClient = OkHttpClient()
) {
```

```kotlin
// PhotoCacheManager.kt — after
class PhotoCacheManager(
    context: Context,
    private val localDataService: LocalDataService,
    private val remoteLogger: RemoteLogger? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Reuse the app-wide unauthenticated client so photo/thumbnail downloads
    // inherit shared timeouts, logging, and certificate pinning. The class adds
    // its own Authorization header per-request, so the auth interceptor is
    // intentionally NOT inherited (downloads can target external/S3 URLs).
    private val httpClient: OkHttpClient = RetrofitClient.plainHttpClient
) {
```

The constructor parameter stays injectable so unit tests can pass a MockWebServer-backed client. `RocketPlanApplication.kt:149` constructs it without the `httpClient` argument, so the new default takes effect with no call-site change.

### Step 2 (RP-BUG-011): Source pins from build config

Standardize on a single format: `BuildConfig.CERT_PINS` is a comma-separated list of base64 SHA-256 pins. Do not describe the same value as both comma-separated and newline-delimited.

Add per-environment public-key pins via `BuildConfig` so they differ across dev/staging/prod and can rotate without touching source.

```kotlin
// AppConfig.kt — after (additions)
/** Hostname extracted from apiBaseUrl, used for certificate pinning. */
val apiHost: String = apiBaseUrl.toHttpUrlOrNull()?.host ?: ""

/**
 * SHA-256 public-key pins for [apiHost], supplied as a comma-separated BuildConfig field.
 * Blank for dev builds to avoid lockout against rotating QA certs.
 */
val certificatePins: List<String> =
    BuildConfig.CERT_PINS.split(',').map { it.trim() }.filter { it.isNotBlank() }
```

`BuildConfig.CERT_PINS` is declared in `app/build.gradle.kts` (empty for dev/staging flavors, populated for prod). Generate pins with the command already documented in the `RetrofitClient` TODO (`openssl s_client … | openssl dgst -sha256 -binary | openssl enc -base64`). Pin both the leaf and at least one backup (intermediate CA) to survive rotation.

### Step 3 (RP-BUG-011): Build and apply the pinner

```kotlin
// RetrofitClient.kt — before
// TODO: Add real certificate pins before production release.
// ...
private val certificatePinner: CertificatePinner? = null
```

```kotlin
// RetrofitClient.kt — after
private val certificatePinner: CertificatePinner? =
    AppConfig.certificatePins
        .takeIf { it.isNotEmpty() && AppConfig.apiHost.isNotBlank() }
        ?.let { pins ->
            CertificatePinner.Builder()
                .apply { pins.forEach { pin -> add(AppConfig.apiHost, "sha256/$pin") } }
                .build()
        }
```

`certificatePinner` stays nullable and is still applied conditionally, so dev/staging (empty pins) keep working unchanged and only prod enforces pinning. Apply it to **both** clients so photo downloads (RP-BUG-007) are also pinned:

```kotlin
// RetrofitClient.kt — plainHttpClient, after
val plainHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .apply { certificatePinner?.let { certificatePinner(it) } }
        .connectTimeout(AppConfig.apiTimeout, TimeUnit.SECONDS)
        .readTimeout(AppConfig.apiTimeout, TimeUnit.SECONDS)
        .build()
}
```

The existing `okHttpClient` builder already has `.apply { certificatePinner?.let { certificatePinner(it) } }`, so it needs no change once `certificatePinner` is non-null.

### Step 4 (RP-BUG-011): Observe pin failures

Add a concrete interception point at request execution time (for example an OkHttp interceptor or event-listener-based hook) that emits a scrubbed warning when an `SSLPeerUnverifiedException` occurs. Do not describe this as a vague `RetrofitClient` catch unless the exact execution hook is defined.

- Log only safe fields: host, request category/path class, and exception type.
- Do not log auth headers, full signed URLs, or query parameters.
- Keep it console-only initially; mirror to `remoteLogger` only if QA validates low noise.

## Observability

### Current Signals
- No explicit signal today when photo downloads bypass the shared client policy.
- No explicit signal today when certificate pinning is absent or fails.

### Proposed Signals
- Console warning on `SSLPeerUnverifiedException` at the actual request execution point.
- Remote logging only if QA validates that the signal is low-noise and scrubbed.

### Safety Rules
- Do not log auth headers.
- Do not log full signed URLs or query params.
- Log host and exception class only.

## Test Plan

- [ ] Unit test: `PhotoCacheManager` accepts an injected `OkHttpClient` (MockWebServer) and downloads succeed/cache; confirms the default-arg change did not break injection.
- [ ] Unit test: with non-empty `certificatePins`, `RetrofitClient` builds a non-null `CertificatePinner`; with empty pins it stays null (dev path).
- [ ] Manual QA (pinning happy path): prod-config build against `api-public.rocketplantech.com`, confirm login + project sync + photo download all succeed.
- [ ] Manual QA (pinning negative path): point a debug build at a proxy (Charles/mitmproxy) with a fake cert and non-empty pins; confirm requests fail with `SSLPeerUnverifiedException` instead of succeeding.
- [ ] Manual QA (RP-BUG-007): capture a photo, force re-cache, confirm download uses app timeouts and appears in HTTP logs (dev build).
- [ ] Regression: dev/staging builds (empty pins) behave exactly as before.

## Rollback Plan

- RP-BUG-011: set `BuildConfig.CERT_PINS` to empty for prod (or revert the `certificatePinner` assignment to `null`). No app update strictly required if pins are server/remote-configurable; otherwise ship a build with empty pins. No persisted-data impact.
- RP-BUG-007: revert the `httpClient` default back to `OkHttpClient()`. No schema or data impact.

## Dependencies

- Requires: the production leaf + backup public-key SHA-256 pins for `api-public.rocketplantech.com` (and any CDN/S3 host that serves photos, if pinning is desired there too — note S3 hosts rotate certs frequently, so prefer pinning only the API host and leaving photo CDN unpinned if it is on a separate domain).
- Blocking: none. RP-BUG-007's security benefit depends on RP-BUG-011 landing, which is why they ship together.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-011] Implemented TLS certificate pinning for production API traffic to prevent MITM on untrusted networks.
- [RP-BUG-007] Photo/thumbnail downloads now reuse the shared OkHttp client (consistent timeouts, logging, and certificate pinning) instead of an unconfigured client.
```

## Open Questions

- Are photos served from the API host (`api-public.rocketplantech.com`) or a separate S3/CDN domain? If separate, decide whether to pin that host (and accept rotation risk) or leave it unpinned. This determines whether `plainHttpClient` pinning is meaningful for photo downloads.
