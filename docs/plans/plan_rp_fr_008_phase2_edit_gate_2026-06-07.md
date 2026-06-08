# Fix Plan: RP-FR-008 — Phase-2 edit gate (verify-first)

**Bug:** [RP-FR-008](../investigations/RP-FR-008_no_phase2_edit_gate.md)
**Date:** 2026-06-07 · **Author:** Claude · **Priority:** P3

## Goal

Decide whether Android needs an iOS-style "block Phase 2 metadata sync while a form is being edited"
gate — and if so, add a minimal one. The data-loss risk is already covered by `preserveDirty`, and
iOS's main-thread-merge motivation is Core-Data-specific, so this plan is **verify-first**; it may end
in WONTFIX.

## Phase 0 — verify a real symptom (do this before building anything)

1. Reproduce the worst case on the tablet: open an atmospheric/moisture-log form on a project with a
   large metadata set, trigger a Phase-2 metadata sync (force refresh) while typing, and watch for:
   - input lag / dropped frames (Choreographer "Skipped frames", Davey logs),
   - the edited row's value flickering or reverting (it should NOT — `preserveDirty` guards it).
2. Confirm where Room writes run (expected: `ioDispatcher`, off-main) and that UI only observes via
   `Flow` — i.e. there is no main-thread auto-merge analogous to iOS.

**If no symptom:** close RP-FR-008 as WONTFIX with the evidence (preserveDirty + off-main writes make
the gate unnecessary). Stop here.

## Phase 1 — minimal gate (only if Phase 0 shows contention)

1. A process-wide `EditSessionTracker` exposing `MutableStateFlow<Int>` (active edit sessions) +
   `canRunPhase2: Boolean`. Form view models `begin()` in `init`/onResume and `end()` in
   `onCleared`/onPause (mirror iOS `beginEditSession`/`endEditSession`), with a stale-session timeout.
2. In `syncProjectMetadata` (Phase 2 entry), if `!canRunPhase2`, **defer**: return an
   `Incomplete`/skip outcome without consuming the checkpoint, and re-trigger when the last edit session
   ends (observe the flow). Do not touch Phase 1 essentials.
3. Keep it scoped to the heavy forms iOS gates (atmospheric/moisture; extend only if needed).

## Tests
- Unit: `EditSessionTracker` ref-counts begin/end correctly; `canRunPhase2` flips at 0.
- Unit: `syncProjectMetadata` defers (no checkpoint consumed) when a session is active, runs when clear.

## Lifecycle
`open → planned`. Phase 0 may resolve this as WONTFIX; otherwise `→ fixed` when the gate + tests land.
