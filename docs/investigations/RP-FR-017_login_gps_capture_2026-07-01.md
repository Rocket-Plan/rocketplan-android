# RP-FR-017 — Capture user GPS location at login (debugging)

**Date:** 2026-07-01
**Type:** feature (debugging/observability)
**Backend:** `mongoose:MONGOOSE-FR-012` (implemented — `log_entries` location columns + `/api/logs/ios` ingestion accepts batch-level `latitude`/`longitude`/`location_accuracy`/`location_captured_at`)
**iOS parity:** `ios.rocketplantech.com:RP-FR-022`

> **This is a complete, self-contained work order.** Do the tasks in order. Obey the
> Agent Guardrails — they are hard constraints, not suggestions. All file:line refs were
> verified 2026-07-01; if the code has drifted, re-locate the symbol by name before editing.

---

## Goal

When a log batch is sent (which happens around login and throughout the session), attach the
device's **last-known** GPS coordinates to the batch POSTed to `/api/logs/ios`, so the backend
`log_entries` rows record where the user was. Debugging only — supports the auth/login flows.
Not a product feature; nothing is shown in the UI.

## Agent Guardrails (hard DO-NOTs)

1. **DO NOT request location permission** anywhere in the logging path. No
   `ActivityResultContracts.RequestPermission`, no permission dialog. Capture **only if already granted**.
2. **DO NOT block or meaningfully delay batch submission** on a location fix. Use *last-known*
   location only; if it isn't immediately available, send the batch with no coordinates.
3. **DO NOT** add new permissions to `AndroidManifest.xml` (both are already declared, lines 12-13).
4. **DO NOT** touch any other `RemoteLogBatch` field, the gzip header, or retry logic.
5. **DO NOT** add background location, geofencing, or continuous location updates.
6. **Scope:** edit only `data/model/LogModels.kt` and `logging/RemoteLogger.kt` (+ optionally a tiny
   private helper in the logging package). No changes to `MapFragment` or UI.

## Files & exact references (verified 2026-07-01)

- **DTO:** `app/src/main/java/com/example/rocketplan_android/data/model/LogModels.kt` — `RemoteLogBatch` data class, **lines 16-38** (Gson `@SerializedName`, `import com.google.gson.annotations.SerializedName` at line 3).
- **Batch builder:** `app/src/main/java/com/example/rocketplan_android/logging/RemoteLogger.kt` — `submitBatch()` **lines 242-312**; batch assembled **lines 260-270**; `userId`/`companyId` fetched **lines 253-258**; class has `context` (used for `deviceId` at lines 67-69) and `sessionId` (line 66).
- **API:** `app/src/main/java/com/example/rocketplan_android/data/api/LoggingService.kt` lines 10-14 — `submitLogBatch(@Body batch: RemoteLogBatch)`. **No change needed.**
- **Location pattern to reuse (do not import MapFragment):** `LocationServices.getFusedLocationProviderClient(context)` + `ContextCompat.checkSelfPermission(...)` — same pattern as `MapFragment.hasLocationPermission()` (lines 339-349) and `fetchLastKnownLocation()` (lines 329-337).
- **Manifest:** `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` already declared (lines 12-13).

## Task 1 — extend the DTO

In `LogModels.kt`, add four **nullable** fields to `RemoteLogBatch` (after `platform`, before `logs`):
```kotlin
@SerializedName("latitude")
val latitude: Double? = null,
@SerializedName("longitude")
val longitude: Double? = null,
@SerializedName("location_accuracy")
val locationAccuracy: Float? = null,
@SerializedName("location_captured_at")
val locationCapturedAt: String? = null,
```
Defaults = null so existing call sites keep compiling and batches without location omit them.

## Task 2 — capture last-known location in `submitBatch()`

Just before the batch is assembled (after the `userId`/`companyId` block, ~line 258), add a
permission-gated, non-blocking last-location read. Use the app `context` the logger already holds:

```kotlin
// Debugging (RP-FR-017): attach last-known location ONLY if permission is already granted.
// Never request permission here; never block batch send on a fix.
val lastLoc: Location? = try {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) {
        // .lastLocation is cached — no active fix, no prompt. Bound the wait so logging never stalls.
        withTimeoutOrNull(500) {
            LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
        }
    } else null
} catch (_: Exception) { null }
```
Notes for the implementing agent:
- `Task.await()` requires `kotlinx-coroutines-play-services` — if it isn't already a dependency,
  instead wrap `lastLocation` in `suspendCancellableCoroutine` with `addOnSuccessListener`/`addOnFailureListener`. Do **not** add a heavy new dependency just for this; prefer the coroutine wrapper.
- `submitBatch()` is already a `suspend` fun, so `withTimeoutOrNull`/`await` are valid here.

## Task 3 — pass it into the batch

In the `RemoteLogBatch(...)` constructor call (lines 260-270), add:
```kotlin
latitude = lastLoc?.latitude,
longitude = lastLoc?.longitude,
locationAccuracy = lastLoc?.accuracy,
locationCapturedAt = lastLoc?.let { java.time.Instant.ofEpochMilli(it.time).toString() },
```
Keep it all-or-nothing implicitly: if `lastLoc` is null every field is null and the backend stores NULL.

## Wire format (batch level, POST `/api/logs/ios`)
```json
{ "batch_id": "…", "user_id": "957", "session_id": "…",
  "latitude": 49.2827, "longitude": -123.1207,
  "location_accuracy": 12.5, "location_captured_at": "2026-07-01T20:23:04Z",
  "logs": [ … ] }
```

## Test / acceptance
- Permission granted + a cached location present → batch carries coordinates; a QA `log_entries`
  row for the user shows non-null `latitude`/`longitude`.
- Permission **not** granted → **no permission dialog**, batch omits the keys, backend stores NULL, login unaffected.
- No cached location / timeout → no keys sent, no error, no added latency to log submission.
- Existing `RemoteLogBatch` call sites still compile (null defaults).
