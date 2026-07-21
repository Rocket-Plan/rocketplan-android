---
bug_id: RP-FR-024
aliases: []
title: Equipment DTOs omit explicit @SerializedName on name-matching fields (RP-CD-006) — safe today only via a proguard keep rule
type: functional
classification: new_code_bug
source: review
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-17 22:16:43 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_fr_024_equipment_serializedname_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-20
---

# RP-FR-024 — Equipment DTOs missing explicit @SerializedName (RP-CD-006)

**Rule:** RP-CD-006 (DTO fields use explicit `@SerializedName` even when names appear to match, because
R8 can rename fields). No demonstrated user-visible failure → `RP-FR`, not `RP-BUG`.

## Deviation
~18 equipment DTO fields rely on Gson field-name reflection instead of an explicit annotation, across
`data/model/offline/EquipmentAssetDtos.kt` and the request/timeline/catalog DTOs:
`id, uuid, name, manufacturer, model, vendor, note, status, placements, data, meta, idempotent,
idempotency, project, bars, asset, room, address, uid` (+ request bodies `name, manufacturer, model,
vendor, note`).

## Live risk: none today
`app/proguard-rules.pro:72` keeps `com.example.rocketplan_android.data.model.offline.**` intact, so R8
(minify on for release) does not rename these fields — deserialization works. The gap is defense-in-depth:
if that keep rule is ever narrowed/removed, these fields would silently deserialize to null. It is also the
established codebase convention (consistent, not unique to equipment).

## Proposal
Add explicit `@SerializedName(...)` to every field on the equipment DTOs (matching the wire key), so the
contract is robust independent of the proguard keep rule.

## Observability
N/A (compile-time contract).

## Update 2026-07-17 — partial application
Timeline DTOs (`TimelineProjectDto`, `TimelineBarAssetDto`, `TimelineBarRoomDto`, `TimelineBarDto`) were
fully annotated, and the `EquipmentAssetPlacementResponse.idempotency` key mismatch (vs `idempotent`
elsewhere) was fixed. **Still outstanding** (ticket stays `planned`): 11 matching-name fields on the two
core DTOs remain bare — `EquipmentAssetDto.{id, uuid, name, manufacturer, model, status, vendor, note}` and
`EquipmentAssetPlacementDto.{id, uuid, note}`. Add `@SerializedName("<same-name>")` to each. Compile + the
`EquipmentAssetDtoParse` golden-fixture tests currently pass, so this is defense-in-depth only, not a live
break — but full RP-CD-006 conformance is not yet reached, and the DTOs are now inconsistently annotated.
