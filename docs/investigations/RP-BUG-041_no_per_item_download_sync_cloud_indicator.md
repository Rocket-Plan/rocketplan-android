---
bug_id: RP-BUG-041
aliases: []
title: Android shows no per-item cloud/download indicator — users can't tell which content isn't downloaded yet (or has unsynced local changes) like iOS does
type: ui_bug
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
related_plan: docs/plans/plan_rp_bug_041_download_sync_indicator_2026-06-07.md
related_review: null
related_test: null
priority: P3
last_updated: 2026-06-07
---

# RP-BUG-041: No per-item cloud / "not downloaded" indicator (iOS parity)

> Filed from an iOS parity review (2026-06-07). iOS surfaces, per row, whether an item is
> **not yet downloaded** (cloud-down) or has **unsynced local changes** (cloud-up). Android has no
> equivalent per-item indicator on most screens.

## Symptom

On Android, a user browsing a project (project list, photos, rooms, etc.) cannot tell which items are
**not yet downloaded to the device** versus fully cached, nor which items have **local changes not yet
pushed**. There is no per-row cloud icon. On iOS the same lists show a cloud glyph that communicates
offline-availability and sync state at a glance.

## iOS reference behavior

- **Dual cloud indicator with precedence** — `Views/Project/List/ProjectListPageViewModel.swift`
  (~:768–804): the UI shows **cloud-up** when the item has unsynced local changes (`!isSynced`) and
  **cloud-down** when content is **not downloaded**; *"cloud-up takes priority over cloud-down"*. The
  view model is careful only to cache `true` so the cloud icon isn't stranded once Phase 2 finishes.
- **Per-file download state** — `Services/FileDownloaderManager.swift` exposes
  `@Published var isDownloaded`, driving the cloud-down state for downloadable content (PDFs, photos).
- **Conflict UI** also uses `Image(systemName: "cloud")` (`Views/SyncQueue/ConflictResolutionView.swift`).

## Android current state

- `R.drawable.ic_cloud_off` exists but is used only for a global **offline/no-connection** affordance
  (`MainActivity.kt:770`), not per item.
- Per-row sync indicators exist on **only two** entity types and both are *upload*-only (pending push),
  not *download*:
  - Notes — `noteSyncStatus` text (`ProjectNotesAdapter.kt:50`, `item_project_note.xml`).
  - Support conversations/messages — a `ProgressBar` `syncIndicator` keyed on `syncStatus`
    (`SupportConversationsAdapter.kt:80`, `SupportMessagesAdapter.kt:58`).
- **No "not downloaded" (cloud-down) indicator anywhere**, and no per-item cloud-up indicator on the
  project list, photos, rooms, equipment, documents, etc. The data exists to compute it (entities carry
  `syncStatus`/`isDirty`; photos have a cached-file path / `PhotoCacheManager`; lists know which child
  content has been pulled), but it is not surfaced.

## Scope / where it matters most

| Surface | iOS shows | Android |
|---------|-----------|---------|
| Project list rows | cloud-up / cloud-down | none |
| Photos (gallery / room) | cloud-down until file cached | spinner only during upload/processing |
| Documents / PDF forms | cloud-down until downloaded | none |
| Notes / support | (sync state) | upload dot only |

## Suggested approach

1. Define a small per-item `DownloadSyncState { Synced, PendingUpload (cloud-up), NotDownloaded
   (cloud-down) }` with iOS's precedence (cloud-up > cloud-down).
2. Compute it from existing signals: `isDirty`/`syncStatus == PENDING` → cloud-up; content not present
   locally (e.g. photo not in `PhotoCacheManager`, child list not pulled) → cloud-down; else synced/no
   icon.
3. Add the cloud glyph (reuse/add `ic_cloud_off` for not-downloaded, an up-arrow cloud for pending) to
   the highest-value adapters first — **project list** and **photo gallery** — then extend.
4. Mirror iOS's "only cache positive results" so the icon doesn't strand after Phase 2 completes
   (relates to RP-BUG-041-adjacent Phase 2 banner work).

## Observability

### Success Criteria
- Each list row shows: nothing when fully synced+downloaded; a cloud-up when it has unpushed local
  changes; a cloud-down when its content isn't on the device yet — matching iOS precedence.
