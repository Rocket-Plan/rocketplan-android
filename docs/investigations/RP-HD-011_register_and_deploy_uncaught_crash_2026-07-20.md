---
bug_id: RP-HD-011
aliases: []
title: SerializedRoomEquipmentViewModel.registerAndDeploy runs staging writes bare in viewModelScope — an uncaught throw crashes the app, and no single-flight guard allows double-registration
type: functional
classification: new_code_bug
source: review
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-20 16:46:54 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
last_updated: 2026-07-20
---

# RP-HD-011 — Guard registerAndDeploy against uncaught crash + double-tap

**Found during the RP-FR-019 pre-ship review (2026-07-20).** One IMPORTANT finding; fixed on-branch
in the same pass. No blocker-level issues found in the serialized subsystem.

## Rationale
`ui/rocketdry/SerializedRoomEquipmentViewModel.kt` — `registerAndDeploy(...)` called
`registerEquipmentAssetOffline(...)` then `deployEquipmentAssetOffline(...)` **bare** inside
`viewModelScope.launch(Dispatchers.IO)`, unlike every sibling action:

- **Uncaught crash.** `viewModelScope` installs no `CoroutineExceptionHandler`, so a throw from the DB
  staging write (register or deploy) escapes uncaught and crashes the app. Every other action routes
  through `runAction` → `runCatching`, and the pool VM's `register()` is likewise wrapped — this was
  the one unguarded entry point.
- **No single-flight guard.** `runAction` gates duplicate taps via the per-asset `inFlight` set, but
  `registerAndDeploy` has no assetId yet, so a fast double-tap could stage **two** registrations of
  the same unit.

Both are new-code, this-branch defects (the screen is RP-FR-027/RP-FR-019); no live failure observed
because the path had not been driven on-device (see RP-HD-010).

## Fix
Mirror `SerializedEquipmentPoolViewModel.register()`:
- Wrap the register→deploy body in `runCatching`; on failure, rethrow `CancellationException`, emit a
  remote WARN (`equip_ui`), and surface a retry event to the user.
- Add a dedicated `AtomicBoolean registerInFlight` single-flight guard (compareAndSet on entry,
  reset in `finally`), since there is no assetId to key the existing `inFlight` set on.

Compile-checked (`compileDevStandardDebugKotlin`, exit 0). On-device verification folds into the
RP-HD-010 offline E2E pass.

## Observability
Reuses the `app.remoteLogger` `equip_ui` WARN channel used by `runAction` and the pool VM — closes the
same gap RP-HD-008 tracks for the rest of the serialized UI.
