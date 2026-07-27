---
bug_id: RP-BUG-335
aliases: []
title: Deploy/move/check-out success write uses preserveDirty=false, clobbering a concurrent metadata edit (rename/serial lost)
type: functional
classification: new_code_bug
source: review
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-17 21:18:13 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_335_lifecycle_preserve_dirty_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-BUG-335 — Lifecycle-success write clobbers a concurrent metadata edit

**Rule:** RP-CD-002 (server updates must not clobber local dirt). Also a concrete comment/code
mismatch.

## Symptom
A user renames (or edits the serial of) a deployed asset while a deploy/move/check-out for that same
asset is syncing; the rename is silently lost and the row is marked clean/SYNCED. The queued update
op then reads the clobbered row and pushes the server's own values, so the edit never reaches the
server.

## Root cause
`EquipmentAssetPlacementPushHandler` writes the post-lifecycle asset via
`saveEquipmentAssets(listOf(mergeAfterLifecycleSuccess(asset, assetDto)))` at the deploy path
(~line 88) and in `applyAssetResponse` (~line 203, move/check-out) — both with the default
`preserveDirty=false` (blind upsert). The deploy comment (lines 80-85) explicitly promises
`preserveDirty=true`; the code does not pass it. `mergeAfterLifecycleSuccess` reads the `asset`
snapshot captured at handler entry, so a metadata edit made *after* entry but *before* the write is
invisible to it and is overwritten.

## Verified
- `LocalDataService.saveEquipmentAssets(preserveDirty=true)` (LocalDataService.kt:1218) re-reads the
  live row by serverId and merges dirty fields — passing `true` fixes the clobber.
- TOCTOU window only (edit must land during the network call), hence P3; but it is real, silent
  data loss and a direct comment/code contradiction.

See plan for the fix + the lock-token trade-off note.
