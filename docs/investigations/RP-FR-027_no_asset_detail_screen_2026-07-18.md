---
bug_id: RP-FR-027
aliases: []
title: Serialized equipment — no per-unit asset detail screen
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
related_plan: plans/plan_rp_fr_027_asset_detail_screen_2026-07-18.md
related_review: null
related_test: null
priority: P3
last_updated: 2026-07-19
---

> **Feature-parity gap** (Android RP-FR-019 vs iOS RP-BUG-344). Parent:
> [RP-FR-019](../plans/plan_rp_fr_019_serialized_equipment_2026-07-13.md). iOS reference: `SerializedAssetDetailView`.

## Symptom

Android exposes serialized lifecycle actions inline on the room-list rows
(`SerializedRoomEquipmentFragment`), but there is **no dedicated per-unit detail screen**.
A user cannot open a single unit to see:

- full metadata already carried on the DTO — `manufacturer`, `model`, `serial_number`,
  `asset_tag`, `purchase_date`, `purchase_price`, `vendor`, `warranty_expires_at`,
  `rental_day_rate`, `note`, `is_standard`, `status`,
- its current placement (room/project/date_in),
- all lifecycle actions (deploy/check-in, move, check-out, retire, edit, view history) in one
  place, with an optimistic-lock (`409` stale) refresh on conflict.

iOS ships this as `SerializedAssetDetailView` and it is the hub the pool
([RP-FR-026]) and history ([RP-FR-028]) screens navigate into.

## Current Android state (verified 2026-07-18)

- `OfflineSyncApi.getEquipmentAsset` → `GET /api/equipment-assets/{assetId}`
  (`OfflineSyncApi.kt:550`) is wired; the DTO carries all fields above.
- No detail fragment / nav destination exists. The room ViewModel projects only
  `name` + a short `detailLine()` for the list row.

## Scope / acceptance

- New detail screen keyed by asset local id, reading from Room via Flow, rendering the full
  field set + current placement.
- Hosts the lifecycle actions (delegating to the existing repo offline methods; edit depends
  on [RP-FR-029]).
- 409 conflict → reload latest server `updated_at` and re-present.
- Navigable from the pool screen and (once built) the room list rows.

## Observability

### Current Signals
- Sentry / logs: none (screen doesn't exist).

### Gaps
- Full asset metadata is fetched/stored but never shown — invisible to users and to QA.

### Proposed Instrumentation
- Remote log on detail-load / action failure (reuse RP-HD-008 category).

### Success Criteria
- QA: opening a unit shows all metadata + current placement; every lifecycle action works
  and a concurrent server edit surfaces as a 409-refresh, not a silent overwrite.
