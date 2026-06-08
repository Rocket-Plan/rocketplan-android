---
bug_id: RP-BUG-042
aliases: []
title: Global reference data (damage types, claim types, scope actions, project types) is not seeded before Phase 2 sync — offline pickers can be empty until a project's metadata has synced
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_042_seed_reference_data_2026-06-07.md
related_review: null
related_test: null
priority: P3
last_updated: 2026-06-07
---

# RP-BUG-042: Reference data not seeded before Phase 2 (iOS parity)

> iOS-parity review (2026-06-07). iOS seeds **global** reference caches before Phase 2 so offline
> pickers are always populated; Android pulls most of them **per project**, leaving a window where the
> pickers are empty.

## Symptom

On a fresh install (or before any project's metadata has synced), Android dropdowns/pickers that depend
on global reference data — **damage types, damage causes, claim types, scope actions, project types** —
can be **empty or incomplete**, especially offline or when creating an entity before a full project sync
has run. The user can't pick a damage type / claim type / scope action that iOS would already have.

## iOS reference

`Views/App/AppViewModel.swift` (~:1151–1157), at company-context-ready and **before** Phase 2:
- `DamageServiceImpl.prefetchDamageTypesIfNeeded()`
- `DamageServiceImpl.prefetchScopeActionsIfNeeded()`
- `ClaimServiceImpl.prefetchClaimTypesIfNeeded()`
- `ProjectServiceImpl.prefetchProjectTypesIfNeeded()`

Company-scoped catalogs (scope sheets, damage materials, equipment) are also prefetched (~:1185–1192),
and the global types are re-seeded on reconnect (~:1433–1443).

## Android current state (verified)

- The **only** up-front prefetch is the room-type catalog: `MainActivity.kt:352` →
  `RoomTypeRepository.prefetchOfflineCatalog(...)` (property types, levels, room types).
- `SyncQueueManager.ensureInitialSync` enqueues `EnsureUserContext → ProcessPendingOperations →
  SyncDeletedRecords → SyncProjects` — **no** prefetch of damage/claim/scope/project types.
- Damage types, claims, etc. arrive only as part of **per-project** metadata sync
  (`ProjectMetadataSyncService.syncProjectMetadata`) — so they exist after a project has been opened and
  synced, not before.

## Reachability / severity

P3: the gap bites a **fresh-install / first-use / offline-create-before-sync** path. Once any project's
metadata has synced, the relevant types are cached. Worth confirming with product which pickers are
truly global vs project-scoped before deciding how much to seed.

## Step 0 finding (verified 2026-06-07) — scope corrected

Most of the iOS-seeded types are **project-scoped** on the Android backend, so they cannot be seeded
globally before a project exists:
- `getProjectDamageTypes(projectId)` and `getDamageCauses(projectId)` — project-scoped (cached by
  `projectServerId`).
- Claims/claim types — `getProjectClaims(projectId, include=claimType)` — project-scoped.
- Room/property/level types — already prefetched (`RoomTypeRepository.prefetchOfflineCatalog`, `MainActivity:352`).

The only genuinely **company-scoped (global)** catalog that was **not** seeded up front is the
**WorkScope catalog** (`getWorkScopeCatalog(companyId)`), which was fetched only on-demand when a user
opened the work-scope picker (`RoomDetailViewModel:614`).

## Fix (implemented 2026-06-07)

Seed the company-scoped WorkScope catalog at company-context-ready: `MainActivity` now calls
`offlineSyncRepository.fetchWorkScopeCatalog(companyId)` (best-effort, non-blocking, in its own
coroutine) right after the room-type prefetch, so the work-scope picker is populated offline before the
user first opens it. The project-scoped types (damage types/causes, claims) remain on the per-project
metadata path — seeding them globally is not possible without a backend global endpoint (out of scope;
would require a backend change to match iOS).

## Observability

### Success Criteria
- On a fresh install, damage/claim/scope/project-type pickers are populated before opening any project
  (and offline), matching iOS.
