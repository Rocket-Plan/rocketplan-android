# Agent Kickoff — RP-FR-018 H1–H4 + M2 (Equipment Move/Transfer fixes)

You are implementing five fixes in the **Rocketplan_android** repo (branch `master`, working tree
has the RP-FR-018 equipment work uncommitted). Implement them, add the unit tests, build, and report.

## Spec

Follow `docs/plans/plan_rp_fr_018_h1_h4_m2_fixes_2026-07-10.md` — it has the root cause, exact fix
code, files, test plan, and rollback for each of H1, H2, H3, H4, M2. Implement all five.

## MANDATORY correction to the spec (do NOT follow the plan verbatim here)

**M2 feature-flag JSON key is camelCase, not snake_case.** The plan shows
`@SerializedName("equipment_move_transfer")` — that is WRONG. The backend flag key is
**`equipmentMoveTransfer`** (verified: `mongoose` migration
`2026_07_10_000005_add_equipment_move_transfer_feature_flag.php` → `private const KEY =
'equipmentMoveTransfer'`; the `/auth/user/feature-flags` `values` map is keyed by `$flag->key`
verbatim; the webapp consumes it as `equipmentMoveTransfer`). Use:

```kotlin
@SerializedName("equipmentMoveTransfer")
val equipmentMoveTransfer: Boolean? = null,
```

A snake_case key leaves the flag permanently `null` → the feature is invisible even when the
server enables it. This is the whole point of the gate, so get it right and verify against a real
`/auth/user/feature-flags` response if you can.

## Backend contract facts you must respect (source of truth = mongoose MONGOOSE-FR-014, on QA)

- **`EquipmentRoomResource` has NO `project_id`.** This is the root of H1 — a transferred pivot's
  project must come from `equipment.projectId` (the source), never `destPivot.projectId` (→ 0).
- **The 409 body key is `current_updated_at`** and is ALREADY handled correctly in
  `SyncHandlerUtils.extractUpdatedAt` (C4, done). Do NOT change that.
- Move/transfer responses are `{data:[EquipmentRoomResource,…]}`; pivot id = `id`, uuid = `uuid`.
- Equipment is count-based (quantity on a pivot), not serialized. Partial moves split quantity.

## Scope guardrails

- These five fixes only. Do not refactor unrelated sync code.
- Shared files (`EquipmentPushHandler.kt`, `ProjectMetadataSyncService.kt`, `LossInfoModels.kt`)
  are used by other entities — keep changes additive and do not regress other entity syncs.
- **Do NOT bump the Room DB version.** Migration 30→31 already added the catalog columns H3 relies
  on (`catalogServerId`/`catalogUuid`); no new columns are needed.
- Feature-flag gate (M2) must default to **hidden/off** when the flag is absent or false
  (`equipmentMoveTransfer != true`), matching iOS/webapp dark-launch behavior.

## Files

- `app/src/main/java/com/example/rocketplan_android/data/repository/sync/handlers/EquipmentPushHandler.kt` (H1, H2, H4)
- `app/src/main/java/com/example/rocketplan_android/data/repository/sync/ProjectMetadataSyncService.kt` (H3)
- `app/src/main/java/com/example/rocketplan_android/data/model/LossInfoModels.kt` (M2 flag field)
- `app/src/main/java/com/example/rocketplan_android/ui/rocketdry/EquipmentRoomFragment.kt` + `EquipmentRoomViewModel.kt` (M2 gate — match how existing flags like `rocketScope` gate UI)

## Acceptance / verification

Add and run these unit tests (from the plan's Test Plan):
- H1a: full transfer → source row tombstoned (`isDeleted=true, syncStatus=SYNCED`); partial → reduced quantity.
- H1b: transferred placement has `projectId = equipment.projectId` (not 0).
- H2: move to a locally-existing-but-unsynced room (`serverId=null`) → `resolveToRoomServerId` returns `null` → `handleMove` returns `SKIP`.
- H3: same catalog in two rooms → pull reconcile produces two rows, correct `roomId`, no duplicate.
- H4: `handleMove`/`handleTransfer` 422 → `DROP` (not `RETRY`).
- M2: with `equipmentMoveTransfer=false`/absent, move/transfer/history entry points hidden; with `true`, visible.

Then build:

```
./gradlew :app:compileDevStandardDebugKotlin
./gradlew :app:testDevStandardDebugUnitTest --tests "*EquipmentPushHandler*" --tests "*ProjectMetadataSync*" --tests "*EquipmentDtoMapper*"
```

Report: which fixes landed (file:line), test results, and any deviation from the spec (especially
confirm the `equipmentMoveTransfer` camelCase key against a real feature-flags response).

## Out of scope (note only, do not implement here)

From the cross-repo review, lower-priority Android items NOT in this pass: M3 (full-move-into-merge
reconcile), M4 (transfer `note` dropped), M5 (dest name defaults to "equipment"), M6
(`LocationDto.toEntity` uuid-preservation regression), history timestamp device-tz. Flag them if
you touch adjacent code, but the deliverable is H1–H4 + M2.
