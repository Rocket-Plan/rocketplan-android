**Bug ID:** RP-FR-024
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-024_equipment_dtos_missing_serializedname_2026-07-17.md) · Plan (this doc)

# Plan — Add explicit @SerializedName to equipment DTOs (RP-CD-006)

## Scope
`data/model/offline/EquipmentAssetDtos.kt` and any equipment request/timeline/catalog DTOs in that file
(and adjacent DTO files if the equipment envelopes live elsewhere). Purely additive annotation work — no
behavior change, no wire change.

## Fix
For **every** field on each equipment DTO / request body, add `@SerializedName("<wire_key>")` matching the
exact JSON key, even when the Kotlin name already matches. Cover at minimum the ~18 flagged fields:
`id, uuid, name, manufacturer, model, vendor, note, status, placements, data, meta, idempotent,
idempotency, project, bars, asset, room, address, uid` plus request-body fields `name, manufacturer, model,
vendor, note`.

Guidance:
- Determine each wire key from the existing golden fixtures under
  `app/src/test/resources/fixtures/equipment_assets/` (source of truth for the real JSON) — do NOT guess.
  snake_case wire keys (`project_id`, `catalog_uuid`, `serial_number`, `asset_tag`, `purchase_price`,
  `rental_day_rate`, `warranty_expires_at`, `current_placement`, `date_in`, `date_out`) must map explicitly.
- Do not change field nullability or types — annotation only.
- Keep the `proguard-rules.pro:72` keep rule in place; this change makes the DTOs robust independent of it
  (defense-in-depth), it does not replace it.

## Verification
- `compileDevStandardDebugKotlin` + `compileDevFlirDebugKotlin` clean.
- Run the existing `EquipmentAssetDtoParseTest` (golden-fixture parse tests) — all must still pass,
  proving no wire-key regressions were introduced by the annotations.
- Spot-check: temporarily narrow the proguard keep rule locally and confirm a release parse test still
  passes (optional, proves the defense-in-depth goal); revert the keep-rule change.

## Observability
N/A (compile-time contract).
