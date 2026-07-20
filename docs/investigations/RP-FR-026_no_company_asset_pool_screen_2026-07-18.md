---
bug_id: RP-FR-026
aliases: []
title: Serialized equipment — no company-wide asset pool screen (room-scoped only)
type: feature
classification: new_feature
source: internal
evidence: inferred
found_in: "iOS parity review 2026-07-18"
found_at: "2026-07-18 10:43:18 PDT"
fixed_in: "feat/RP-FR-019-serialized-equipment"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: plans/plan_rp_fr_026_company_asset_pool_2026-07-18.md
related_review: null
related_test: null
priority: P2
last_updated: 2026-07-19
---

> **Feature-parity gap** surfaced comparing the Android RP-FR-019 serialized-equipment
> client against the iOS RP-BUG-344 implementation (`ios.rocketplantech.com`, branch `dev`).
> Not a defect in shipped behaviour — a capability iOS has that Android lacks.
> Parent: [RP-FR-019](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md). iOS reference: `SerializedEquipmentContentView`.

## Symptom

On Android, serialized equipment is only reachable **inside a room**
(`SerializedRoomEquipmentFragment` → `SerializedRoomEquipmentViewModel`). That screen
shows the units deployed in the current room plus a "pool" that is scoped to *what can be
deployed into this room*. There is **no standalone, company-wide asset pool** where a user
can:

- browse/search every unit the company owns across all statuses (`available`, `deployed`,
  `maintenance`, `retired`),
- register a new unit from the pool (not only register-and-deploy into a room),
- see at a glance what is currently out on jobs vs. available.

iOS ships exactly this as `SerializedEquipmentContentView` (paginated list, pull-to-refresh,
load-more, offline cache) mounted from the equipment tab when
`equipmentMode == .serialized`.

## Current Android state (verified 2026-07-18)

- Read endpoint already wired: `OfflineSyncApi.getCompanyEquipmentAssets` →
  `GET /api/companies/{companyId}/equipment-assets` (`OfflineSyncApi.kt:527`), paginated DTO
  present.
- Pull already populates the company pool: `EquipmentAssetPullService.pullCompanyPool`
  (paginated, `RP-HD-009` bounded).
- UI: only `SerializedRoomEquipmentFragment` exists. No pool fragment, no nav destination.
  `SerializedRoomEquipmentViewModel` derives `pool` from `observeAvailableAssets(companyId)`
  but only to offer deploy-into-this-room choices — it is not a browsable company view.

So the **data layer is ready**; this is a UI/navigation build only.

## Scope / acceptance

- New pool screen listing all company assets with status filter + search
  (`status`, `search`, `catalog_uuid`, `per_page`, `page` query params already supported by
  the endpoint), paginated with load-more and pull-to-refresh, reading from Room via a Flow
  (offline-first), matching the mount rules in RP-FR-019 (mount only when
  mode == ON; UNKNOWN → retryable state, never legacy).
- Register-a-unit entry point from the pool.
- Tapping a unit opens the asset detail screen ([RP-FR-027]) once that exists.

Non-goals: timeline/Gantt (not built on either platform); placement correction/delete
([RP-FR-030]/[RP-FR-031]).

## Observability

### Current Signals
- Local console logs: `EquipmentAssetPullService` logs pool pull pages.
- Remote logs: pull failures partially covered (see RP-HD-008 gaps).
- Sentry: none specific to a pool screen (doesn't exist yet).
- Existing metrics/watchdogs: none.

### Gaps
- No new failure surface until the screen exists.

### Proposed Instrumentation
- Remote log on pool-load failure in the new ViewModel (reuse RP-HD-008 category once landed).
- Local debug log for filter/search/pagination transitions.

### Success Criteria
- QA: with the flag ON, the pool lists every company unit, filters by status, searches, and
  paginates; works offline from cache; register-from-pool round-trips.
- Wild: pool-load failures visible in remote logs rather than a silent empty list.
