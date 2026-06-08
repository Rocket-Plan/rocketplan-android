---
bug_id: RP-BUG-045
aliases: []
title: Remote log batches containing a WARN-level entry are rejected by the backend (HTTP 400, level "WARN" invalid — backend wants "WARNING") and the whole batch is dropped
type: functional
classification: pre_existing_latent
source: internal
found_in: "1.0.00"
found_at: "2026-06-07 21:34:02 PDT"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: app/src/test/java/com/example/rocketplan_android/logging/LogLevelWireTest.kt
last_updated: 2026-06-07
---

# RP-BUG-045: Android remote logs with `WARN` level are rejected (400) — whole batch dropped

> Found 2026-06-07 in on-device logcat while verifying the RP-BUG-043/044 fixes on the tablet
> (`30407ef`, build `1.29 (32)-dev`). Confirmed end-to-end against the backend validation rule.

## Symptom

Android's remote-logging pipeline (`RemoteLogger` → `POST /api/logs/ios`) silently loses logs. Any batch
that contains a **warning-level** entry is rejected wholesale by the backend and dropped — observed in
logcat:

```
RemoteLogger: Remote log batch rejected (HTTP 400), dropping:
  {"success":false,"error":"Validation failed",
   "details":{"logs.0.level":["The selected logs.0.level is invalid."],
              "logs.1.level":["The selected logs.1.level is invalid."]}}
```

Not user-visible in the UI, but it is a concrete, observed defect: a whole severity class of diagnostic
telemetry never reaches the backend `LogEntry` store, undercutting production diagnosability.

## Root cause (confirmed end-to-end)

- Android `LogLevel` enum (`RemoteLogger.kt:28`) is `DEBUG, INFO, WARN, ERROR`, and the wire value is
  `level = level.name` (`RemoteLogger.kt:78`) → the literal string **`"WARN"`** for warnings.
- Backend `IosLoggingController.php:87` validates:
  ```php
  'logs.*.level' => 'required|string|in:DEBUG,INFO,WARNING,ERROR,CRITICAL',
  ```
  Accepted: `DEBUG, INFO, WARNING, ERROR, CRITICAL`.

So `DEBUG` / `INFO` / `ERROR` pass, but **`WARN` ≠ `WARNING`** → invalid. Laravel fails the **entire
request** on any invalid element, so a single warning entry causes the whole batch (every log in it,
including valid `INFO`/`ERROR` ones) to be rejected with HTTP 400 and dropped by `RemoteLogger`.

### Blast radius
- Warnings are the most common operational remote-log level, so a large fraction of batches are poisoned.
- The **RP-BUG-044** one-shot partial-failure remote log uses `LogLevel.WARN` — it would be dropped by
  this same bug, so that observability does not actually land until this is fixed.
- Android also has no `CRITICAL` level (backend accepts it); not a bug, just unused.

## Suggested fix

Map the wire value so warnings serialize as `"WARNING"`. Minimal options:
1. Give `LogLevel` an explicit wire name and send that instead of `.name`:
   ```kotlin
   enum class LogLevel(val wire: String) {
       DEBUG("DEBUG"), INFO("INFO"), WARN("WARNING"), ERROR("ERROR")
   }
   // RemoteLogger: level = level.wire
   ```
2. Or rename the enum constant `WARN → WARNING` (touches all call sites).

Option 1 is the smallest, lowest-risk change (one enum + one line). Add a unit test asserting
`LogLevel.WARN` serializes to `"WARNING"` and that the other three are unchanged, plus a guard test that
the set of wire values is a subset of the backend's accepted set (`DEBUG,INFO,WARNING,ERROR,CRITICAL`).

## Fix (implemented 2026-06-07)

`LogLevel` now carries an explicit wire value and `RemoteLogger` sends that instead of the enum name
(`RemoteLogger.kt`):
```kotlin
enum class LogLevel(val wireValue: String) { DEBUG("DEBUG"), INFO("INFO"), WARN("WARNING"), ERROR("ERROR") }
// log(): level = level.wireValue
```
`WARN` now serializes to `"WARNING"`, which the backend accepts, so warning-level batches are no longer
rejected. **Test:** `LogLevelWireTest` — `WARN → "WARNING"`, others unchanged, and a contract guard that
every wire value is a subset of the backend's accepted set (`DEBUG,INFO,WARNING,ERROR,CRITICAL`). Full
suite green.

## Observability

### Current Signals
- Local console logs: `RemoteLogger: Remote log batch rejected (HTTP 400), dropping: …` (the backend
  validation body names the offending field/level).
- Remote logs: N/A — this *is* the remote-log path failing.
- Sentry: none.
- Existing metrics/watchdogs: none.

### Gaps
- The drop is only visible in logcat; there is no counter/metric for "remote log batches rejected", so in
  the field this is invisible. Valid INFO/ERROR entries riding in a poisoned batch are lost with no trace.

### Proposed Instrumentation
- Local debug log already exists (the rejection line). Consider logging the count of dropped entries.
- Remote logs to add: none here (would recurse); rely on the fix + unit test.
- A defensive backend-vs-client level contract test (client wire values ⊆ backend accepted set) prevents
  regression if either side adds a level.

### Success Criteria
- QA: trigger a `LogLevel.WARN` remote log; `POST /api/logs/ios` returns 200 and a `LogEntry` row with
  `level = WARNING` is created; no `Remote log batch rejected` line in logcat.
- Wild: absence of `Remote log batch rejected (HTTP 400)` lines; warning-level entries appear in the
  backend `LogEntry` store.
