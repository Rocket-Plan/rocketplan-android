# Fix Plan: RP-BUG-042 — seed global reference data before Phase 2

**Bug:** [RP-BUG-042](../investigations/RP-BUG-042_reference_data_not_seeded_before_phase2.md)
**Date:** 2026-06-07 · **Author:** Claude · **Priority:** P3

## Goal

At company-context-ready (before/alongside `SyncProjects`), proactively prefetch the **global**
reference catalogs so offline pickers are populated before any project's metadata has synced — matching
iOS (`AppViewModel` RP-BUG-177).

## Scope (verify each is global, not project-scoped)

Candidates from the iOS seed list + Android pickers: **damage types, damage causes, claim types, scope
actions (work-scope catalog), project (property) types**. Room types are **already** prefetched
(`MainActivity:352`) — leave as-is.

**Step 0 (verify before wiring):** for each type, confirm a **global/company-scoped** GET endpoint
exists in `OfflineSyncApi` (not a per-project one) and a local cache table + `replace*`/`save*` path.
Drop any that are genuinely project-scoped on the backend.

## Changes

1. Add a `prefetchReferenceCatalogs(companyId)` step (a `RoomTypeRepository`-style repo method or a new
   `ReferenceDataSyncService`) that fetches the confirmed global types and caches them, each wrapped in
   `runCatching` (best-effort, non-blocking — one failure must not abort the rest).
2. Wire it into `SyncQueueManager.ensureInitialSync` right after `EnsureUserContext` (so company context
   is known) and **before** `SyncProjects`, mirroring iOS ordering. Also re-run on reconnect
   (`SyncNetworkMonitor` online transition), like iOS re-seeds on reconnect.
3. Idempotent + cheap on repeat (use `forceRefresh = false` / checkpoint semantics like
   `prefetchOfflineCatalog`).

## Tests
- Unit (service): on init it calls each confirmed reference endpoint and persists results; a single
  endpoint failure doesn't prevent the others.
- Verify ordering: reference prefetch is enqueued before `SyncProjects`.

## Risks / notes
- Don't block project sync on these (fire-and-forget / parallel) — they're enhancements, not
  prerequisites for rendering a project.
- Confirm the backend treats these as global; if a type is actually project-scoped, leave it on the
  per-project path and note it.

## Lifecycle
`open → planned` on writing this; `→ fixed` when the confirmed global types are seeded up front + on
reconnect, with tests.
