# Fix Plan: RP-BUG-041 — per-item cloud/download indicator

**Bug:** [RP-BUG-041](../investigations/RP-BUG-041_no_per_item_download_sync_cloud_indicator.md)
**Date:** 2026-06-07 · **Author:** Claude · **Priority:** P3

## Goal

Show a per-row cloud glyph that tells the user, at a glance, whether an item has **unsynced local
changes** (cloud-up) or is **not yet downloaded** to the device (cloud-down) — matching iOS, including
its precedence (cloud-up wins over cloud-down).

## Decision needed first

What does "not downloaded" cover? Two options — pick before building:
- **A (start here):** files/binaries not cached locally — photos not in `PhotoCacheManager`, PDFs not
  downloaded. Concrete and high-value.
- **B (later):** whole child collections not yet pulled (a room whose photos/notes haven't synced).
  Broader; needs a "has this scope been pulled" signal we don't cleanly have yet.

Plan below targets **A** first (project list + photo gallery), structured so B can extend it.

## Changes

1. **State model** (`ui/common/` or `data/`):
   ```kotlin
   enum class DownloadSyncState { Synced, PendingUpload, NotDownloaded }
   ```
   Precedence: `isDirty || syncStatus == PENDING` → `PendingUpload`; else not-cached → `NotDownloaded`;
   else `Synced`.
2. **Derivation helpers** — pure functions, unit-testable:
   - entity → upload state from `isDirty`/`syncStatus`.
   - photo → `NotDownloaded` when its cached file is absent (query `PhotoCacheManager` / local path).
   - "only cache positive results" so the icon doesn't strand after Phase 2 (mirror iOS comment).
3. **Drawables** — reuse `ic_cloud_off` for NotDownloaded; add `ic_cloud_upload` (cloud + up-arrow) for
   PendingUpload. A small reusable `bindCloudIndicator(ImageView, DownloadSyncState)` helper.
4. **Adapters (rollout order):**
   - Project list rows (the iOS surface): bind the cloud indicator.
   - Photo gallery / room photos: cloud-down until the file is cached; existing upload spinner stays.
   - Later: documents/PDF, rooms.

## Tests
- Unit: state-derivation precedence (dirty→up beats not-cached→down; synced→none).
- Unit: photo not-cached → NotDownloaded; cached → none.
- Adapter binding: correct drawable/visibility per state.

## Risks / notes
- Cache-presence checks must be cheap (no per-row IO on the main thread) — compute off-main / batch.
- Keep parity with iOS precedence so behavior reads the same cross-platform.

## Lifecycle
`open → planned` on writing this; `→ fixed` when project-list + photo-gallery indicators land with tests.
