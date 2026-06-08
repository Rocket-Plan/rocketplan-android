---
bug_id: RP-FR-008
aliases: []
title: No "block Phase 2 (metadata) sync while editing a form" gate like iOS Phase2GatingService — Android relies on preserveDirty only
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_fr_008_phase2_edit_gate_2026-06-07.md
related_review: null
related_test: null
violates: RP-CD-002
priority: P3
last_updated: 2026-06-07
---

# RP-FR-008: No Phase-2 edit gate (iOS parity)

> iOS-parity review (2026-06-07). Filed `RP-FR` (not `RP-BUG`) because the **data-loss** half is already
> covered on Android by `preserveDirty`, and iOS's main motivation for the gate is a Core-Data-specific
> hazard that Android's architecture largely avoids — so there is no demonstrated user-visible failure
> today. Tracked because the parity control is absent.

## iOS reference

iOS has a first-class `Phase2GatingService` (`Services/Phase2GatingService.swift`):
`beginEditSession(owner:reason:)` / `endEditSession(owner:)` / `canRunPhase2`. Forms register an edit
session (e.g. `AtmosphericLogFormViewModel` begin in `init`, end in `deinit`), and the Phase 2 sync
entry point **defers entirely** if `!canRunPhase2` (`OfflineSync+Projects.swift` Phase 2 entry), then
resumes via `didUnblockNotification`. Motivation: iOS Phase 2 helper-context saves auto-merge into the
**main-thread** `viewContext` (`automaticallyMergesChangesFromParent = true`), so a 100+-entity merge
fan-out competes with the user's gestures while they edit.

## Android current state

- No `Phase2GatingService` equivalent; no `beginEditSession`/`endEditSession`.
- `ProjectMetadataSyncService.syncProjectMetadata` runs unconditionally (no "is a form open?" check);
  `OfflineSyncRepository` calls it without a gate.
- `RocketDryRoomViewModel` (atmospheric/moisture forms) does not signal editing to the sync layer.
- What Android *does* have: every Phase-2 pull save uses `preserveDirty = true` (RP-FR-003), so a
  locally-dirty row the user is editing is **not clobbered** by the pull.

## Why this is RP-FR (and may even be WONTFIX on Android)

The iOS gate addresses two things: (1) data loss — covered on Android by `preserveDirty`; (2)
main-thread merge jank while editing — an iOS Core-Data concern. **Android writes Room off the main
thread (`ioDispatcher`) and the UI observes via `Flow`; there is no automatic main-thread merge**, so
the jank motivation largely does not transfer. The residual concern is minor: background metadata
ingestion competing for IO / DB locks while editing. There is **no demonstrated user-visible failure**.

## Suggested approach (if pursued)

If a concrete stutter/contention is observed while editing during a Phase-2 sync, add a lightweight
"editing in progress" flag (a shared `MutableStateFlow<Boolean>` set by form view models) and have the
metadata sync defer/yield while it is set. Otherwise, confirm `preserveDirty` is sufficient and close
as WONTFIX with a note. **Verify a real symptom before building the gate.**

## Observability

### Success Criteria
- Either: editing a form during a Phase-2 sync is demonstrably smooth (no gate needed → WONTFIX), or a
  gate defers metadata sync while a form edit session is active (iOS parity).
