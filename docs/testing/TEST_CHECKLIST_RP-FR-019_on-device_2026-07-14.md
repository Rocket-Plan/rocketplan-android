# RP-FR-019 On-Device Test Checklist

**Feature:** Serialized Equipment — register, move, and continuous backend-flip invalidation
**APK:** `app/build/outputs/apk/devStandard/debug/app-dev-standard-debug.apk`
**Branch:** `feat/RP-FR-019-serialized-equipment` (PR #8)
**Reviewer:** (fill in before running)

---

## Prerequisites

- [ ] Device connected: `adb devices` shows a device/tablet
- [ ] APK installed: `adb install -r app/build/outputs/apk/devStandard/debug/app-dev-standard-debug.apk`
- [ ] Test company has the **`serializedEquipment`** feature flag enabled on the backend (company-scoped; fails closed). Confirm at `GET /api/auth/user/feature-flags` → `data.values.serializedEquipment == true` for the active/owner company.
- [ ] (Optional) `adb logcat` running with filter: `adb logcat --pid=$(adb shell pidof -s com.rocketplantech.rocketplan) | grep -iE "equipment|serialized|catalog|feature flag"`
  - Real log tags in this feature: **`API`** (sync push handlers — register/deploy/move/check-out), **`EquipmentAssetSyncService`** (write rejections/invalid ops), **`AuthRepository`** (flag fetch). The ViewModel/mode-observer emit no logs (mode flows through DataStore).

---

## Test 1 — Register Equipment from Company Catalog

**Purpose:** Verify the catalog picker appears and `registerAndDeploy(name, catalog_uuid)` is wired.

1. Open the Serialized Equipment screen (mode must be **ON** for the company). Reach it via RocketDry → a room's equipment (the host routes ON companies to the serialized screen).
2. Tap **Register**.
3. The catalog chooser should appear (company equipment catalog loaded from `GET /companies/{c}/equipment`). If it's empty you'll see a "No equipment catalog available" toast — that means the catalog fetch returned nothing (check connectivity / that the company has catalog items).
4. Select a catalog item. **Note:** there is no separate name-entry step — the catalog item's name is used, and the unit is registered **and deployed to the current room** in one action (`registerAndDeploy`).
5. **Assert:** A new row appears under "Deployed in this room".
6. **Assert:** No crash. (offline is fine — it queues; POST `/companies/{c}/equipment-assets` then `.../placements` fire when online.)

**Expected result:** Deployed row visible with the catalog item's name (+ serial/tag if set later).

---

## Test 2 — Move Equipment Between Rooms

**Purpose:** Verify Move action surfaces a room chooser and same-room rejection works.

1. From Test 1 (or any deployed row), tap **Move**.
2. **Assert:** Room chooser appears (project rooms via `observeRooms`).
3. Select a **different** room from current.
4. **Assert:** Row moves to new room without error.
5. Tap **Move** again on the same row.
6. Select the **same** room it's already in.
7. **Assert:** Service rejects the move (toast or error message, row unchanged).

**Expected result:** Move works cross-room; same-room is rejected.

---

## Test 3 — Continuous Backend-Flip Invalidation (Mode OFF)

**Purpose:** Verify the 60s poll picks up an emergency OFF rollback without user action.

**Setup:** You will need backend access or another client session to toggle the company's mode OFF while this device is on the serialized screen.

1. Open the Serialized Equipment screen with mode ON.
2. **While the screen is foregrounded**, have backend change the company's `serializedEquipment` flag to **OFF**.
3. Wait **up to 60 seconds** (next poll cycle; the screen polls `refreshFeatureFlags` every 60s and on resume).
4. **Assert:** the serialized screen **leaves** — on OFF the serialized VM emits `LegacyMode` and the fragment pops back; the host then mounts the **legacy** count equipment UI (or you land back on RocketDry). Serialized register/move are no longer reachable.
5. Verify via logcat: on a failed/invalid flag fetch you'll see `AuthRepository: Feature flags not cached ... → UNKNOWN`. On success the flag is written silently (no per-poll success log) — confirm via the UI transition, not a log line.

**Expected result:** within ~60s (no user interaction) the serialized screen is dismissed and the legacy/gated state is shown.

---

## Test 4 — Continuous Backend-Flip Invalidation (Mode back to ON)

**Purpose:** Verify rollback back to ON is also picked up.

1. From Test 3 state (mode OFF, now on the **legacy equipment room** screen — it also polls every 60s + on resume).
2. Have backend change the `serializedEquipment` flag back to **ON**.
3. Wait **up to 60 seconds**.
4. **Assert:** the host detects ON and **routes to the serialized screen** (register/move available again).
5. **Assert:** the equipment list is intact (no data loss across the round-trip).

**Note:** the auto round-trip relies on being on the equipment room / serialized screens (both poll). On the RocketDry summary the equipment tab reacts to a cached-mode change but isn't force-refreshed there — re-enter the equipment screen if needed.

**Expected result:** Full ON→OFF→ON round-trip handled without data loss.

---

## Test 5 — Mode ON Hides/Blocks the LEGACY count system (cutover boundary)

**Purpose:** Verify that when a company is serialized (ON), the legacy count-equipment
surfaces are hidden/routed AND legacy writes are hard-rejected (not just navigation).

1. Ensure company mode is **ON**.
2. **Assert (display):** RocketDry's **Equipment tab is hidden** (falls back to Moisture); opening a room's equipment **routes to the serialized screen**; the legacy Total-Equipment screen, if reached, **pops back** with an "Equipment mode changed" toast.
3. **Assert (write boundary):** any legacy mutation that still fires (e.g. a tap during the OFF→ON transition, or a queued legacy action) is rejected before the network — logcat shows `EquipmentRoomViewModel: Rejecting legacy equipment write — mode=ON` (or `TotalEquipmentVM: Rejecting legacy total-equipment write — mode=ON`) and a toast.
4. **Assert (serialized side):** if the flag flips to OFF/UNKNOWN while a serialized action is attempted, it's rejected with an "Equipment mode changed — please refresh" toast (the ViewModel `requireOn()` guard).

**Expected result:** no legacy `upsertEquipmentOffline`/`deleteEquipmentOffline` reaches the network for an ON company; serialized actions are gated to ON.

---

## Log Reference

Real log tags/patterns to grep (verified against the code):
```
API                        — serialized sync push handlers (register/deploy/move/check-out; 409/422/skip)
EquipmentAssetSyncService  — rejected/invalid serialized writes (bad deploy, retire-while-deployed)
EquipmentRoomViewModel     — "Rejecting legacy equipment write — mode=..."
TotalEquipmentVM           — "Rejecting legacy total-equipment write — mode=..."
AuthRepository             — "Feature flags not cached ... → UNKNOWN"   (flag fetch failure)
```
Note: the mode observer + ViewModels emit no dedicated "mode changed" log — mode flows through DataStore
(`SerializedEquipmentModeProvider.observeMode`); confirm transitions via the UI, not a log line.

---

## Sign-Off

| Test | Tester | Date | Result | Notes |
|------|--------|------|--------|-------|
| 1    |        |      |        |       |
| 2    |        |      |        |       |
| 3    |        |      |        |       |
| 4    |        |      |        |       |
| 5    |        |      |        |       |

**Overall:** ☐ Pass  ☐ Fail  ☐ Blocked (no test company flag)

