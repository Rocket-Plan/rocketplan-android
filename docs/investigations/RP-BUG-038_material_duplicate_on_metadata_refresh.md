---
bug_id: RP-BUG-038
aliases: []
title: Offline-created materials duplicate after metadata refresh — material pull maps to a server-id PK with no serverId reconciliation, and the server-minted uuid differs from the local uuid
type: functional
classification: pre_existing_latent
source: review
evidence: reproducible
found_in: "1.0.00"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: app/src/test/java/com/example/rocketplan_android/data/local/MergePulledRowsByServerIdTest.kt
violates: RP-CD-014
priority: P2
last_updated: 2026-06-07
---

# RP-BUG-038: Offline-created materials duplicate on refresh

> Same root cause as [[RP-BUG-037]] — but on **materials**, which the original RP-BUG-037 sweep did
> NOT cover (it handled notes/equipment/moisture/atmospheric). Surfaced by a "are we missing something
> in the model?" review of `OfflineMaterialEntity`. Verified against backend + the `@Upsert` probe.

## Symptom

A user creates a material offline (RocketDry drying goal / damage material), it syncs, and on the next
project-metadata refresh the material appears **twice**. Materials are rendered
(`observeMaterialsForProject`, RP-BUG-032), so the duplicate is user-visible.

## Root cause (verified)

1. **Offline create** — `RocketDryRoomViewModel` (`:206`) writes `OfflineMaterialEntity` with
   `materialId = -timestamp` (local PK), a client `uuid`, `serverId = null`, `PENDING`.
2. **Push keeps local identity** — `MoistureLogPushHandler.ensureMaterialSynced` (`:325`) does
   `material.copy(serverId = createdDto.id, …)`, preserving the local `materialId` + `uuid`.
3. **Server mints its own uuid** — the create request (`DamageMaterialRequest`) sends the client value
   only as `idempotency_key`; backend `DamageMaterial` uses `HasUuid` (mints `orderedUuid` when none
   provided). So the record's `uuid` is server-minted, not the client's.
4. **Pull mapper does NOT reconcile** — `DamageMaterialDto.toMaterialEntity` (`SyncEntityMappers.kt:672`)
   takes no `existing` and sets `materialId = id` (server id), `uuid = uuid ?: derived` (≠ client uuid).
   (Contrast `EquipmentDto.toEntity` right below it, which reconciles via `existing`.)
5. **Save is a plain upsert** — `saveMaterials` → `upsertMaterials` (`@Upsert`, PK-only, no reconcile,
   not even `preserveDirty`).

So the pulled row (`materialId = server id`, `uuid = server`) and the local row
(`materialId = -timestamp`, `uuid = client`) share a `serverId` but differ on PK and uuid → the
`unique(uuid)` index does not collapse them (proven by `UpsertIdentityProbeTest`) → **duplicate row**.

`OfflineMaterialEntity` having no `isDirty`/`isDeleted` column is *not* the bug — it uses `syncStatus`;
the bug is the missing serverId reconciliation on the pull.

## Fix (implemented 2026-06-07)

- Add `getMaterialsByServerIds` (`OfflineDao`).
- `saveMaterials(materials, reconcileByServerId = …)`: when reconciling, use the shared
  `mergePulledRowsByServerId` helper with `isDirty = { false }` (materials have no dirty column) and
  `adoptLocalIdentity = { s, l -> s.copy(materialId = l.materialId, uuid = l.uuid) }` — so a pulled
  material with an existing `serverId` updates the local row in place instead of inserting a duplicate.
  Local-write callers (`RocketDryRoomViewModel`, `MoistureLogPushHandler`) keep the default (no
  reconcile); only the pull callers (`ProjectMetadataSyncService:155/238`) pass `reconcileByServerId = true`.
- Test: `MergePulledRowsByServerIdTest` "materials config (no isDirty) always adopts local identity".

## Scope note
Same identity-reconciliation family as RP-BUG-036 / RP-BUG-037 / RP-FR-005 / RP-FR-006. Pre-existing
duplicates created before this fix are not retroactively cleaned (prevents new ones).
