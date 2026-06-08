---
bug_id: RP-BUG-047
aliases: []
title: Room moisture-log pull sends include=photo,moisture_log — backend rejects the invalid "moisture_log" relation with HTTP 400 for every room, so moisture logs never download
type: functional
classification: pre_existing_latent
source: internal
found_in: "1.0.00"
found_at: "2026-06-07 22:55:41 PDT"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
last_updated: 2026-06-07
---

# RP-BUG-047: moisture-log pull rejected (HTTP 400) — invalid `moisture_log` include

> Found 2026-06-07 in on-device logcat (tablet `30407ef`) while capturing the RP-BUG-046 push 422.
> Root cause pinned by **iOS parity** (the user asked what iOS does here): iOS sends a different
> `include` value and never hits the 400.

## Symptom

Moisture-log down-sync fails for **every room** — readings created on another device/web never appear on
this device. Logcat (one per room, all rooms):
```
--> GET /api/rooms/6800/damage-materials/logs?include=photo%2Cmoisture_log
<-- 400 …/api/rooms/6800/damage-materials/logs?include=photo%2Cmoisture_log (424ms, 26165-byte body)
E/API: [syncRoomMoistureLogs] Failed for roomId=6800 (projectId=5233)
```
Observed for rooms 6800, 6801, 6802, 6803, … — i.e. all of them.

## Root cause (confirmed via iOS parity)

`ProjectMetadataSyncService.syncRoomMoistureLogs` (`:252`) called:
```kotlin
api.getRoomMoistureLogs(roomId, include = "photo,moisture_log")
```
→ `GET /api/rooms/{roomId}/damage-materials/logs?include=photo,moisture_log`.

The backend rejects the **`moisture_log`** include — it's not a valid relation on this resource (the
returned items *are* the moisture logs, keyed by material id) — and returns HTTP 400, failing the whole
request.

**iOS does it right** (`RocketPlan/Services/DamageService.swift:getRoomMoistureLogs` and the queue sync in
`OfflineSync+Queue.swift`):
```swift
let path = "/api/rooms/\(roomId)/damage-materials/logs"
let parameters = ["include": "photo"]   // ← only "photo"
```
Both iOS call sites send `include=photo`. Android's extra `,moisture_log` is the sole difference.

## Fix (implemented 2026-06-07)

`ProjectMetadataSyncService.kt:252` → `include = "photo"` (drop `moisture_log`), matching iOS. One-line
change; the response shape (`{"data": {materialId: [logs]}}`) is unchanged — iOS parses the same dict with
just `photo`.

## Relationship to other moisture bugs
- **RP-BUG-046** (push 422, offline-created reading dropped) is a *separate* path (the create POST), still
  pending its captured 422 body. This fix only addresses the *pull*.
- Note iOS RP-BUG-103: the per-log mutate/delete route must be the singular `damage-material-room-log`
  (Android already uses the singular dashed form — verified, not affected here).

## Observability

### Current Signals
- Local console logs: `[syncRoomMoistureLogs] Failed for roomId=… (projectId=…)` + okhttp `<-- 400 …`.
- Remote logs: now that RP-BUG-045 is fixed, a remote warning could be added; currently console-only.
- Sentry: none.

### Gaps
- The handler logged the failure but not the 400 body, so the invalid-include cause wasn't obvious until
  compared against iOS. (The fix removes the failure; a body-capture like RP-BUG-046's could be added if
  this regresses.)

### Success Criteria
- QA: opening a project's rooms yields `200` on `GET …/damage-materials/logs?include=photo` and moisture
  readings populate locally; no `[syncRoomMoistureLogs] Failed` / `400` lines in logcat.
- Wild: absence of 400s on that endpoint; moisture readings sync down across devices.
