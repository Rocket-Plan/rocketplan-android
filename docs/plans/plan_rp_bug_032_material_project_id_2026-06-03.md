**Bug ID(s):** RP-BUG-032
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-032_material_entity_missing_project_id.md) · [Plan](./plan_rp_bug_032_material_project_id_2026-06-03.md) · Review: pending

# Fix Plan: [RP-BUG-032] Add projectId scoping to OfflineMaterialEntity

**Bug ID(s):** RP-BUG-032
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-03
**State:** draft

---

## Summary

`OfflineMaterialEntity` has no `projectId`. `DamageMaterialDto` carries `project_id`, but `toMaterialEntity()` drops it, and `observeMaterials()` returns **all** materials across **all** projects with no scoping. Sync currently survives on server-ID uniqueness, but the design is fragile: a material's project cannot be queried, and there is no `remapMaterialId()` in `IdRemapService`.

The fix adds a nullable `projectId` column to the entity (with a Room migration 28→29), populates it in the mapper, adds project-scoped DAO queries, and adds `remapMaterialId()` for consistency with the other entities.

## Affected Code

| File | Change |
|------|--------|
| `data/local/entity/OfflineEntities.kt` (≈565–578) | Add `val projectId: Long? = null` to `OfflineMaterialEntity`. |
| `data/local/OfflineDatabase.kt` (≈94) | Bump version `28 → 29`; add `MIGRATION_28_29` adding the column. |
| `data/repository/mapper/SyncEntityMappers.kt` (≈667–681) | `toMaterialEntity()` sets `projectId = projectId` from the DTO. |
| `data/local/dao/OfflineDao.kt` (≈918–927) | Add `observeMaterialsForProject(projectId)` and a pending-by-project query. |
| `data/repository/sync/IdRemapService.kt` | Add `remapMaterialId(localId, serverId)` to update material payloads/references on remap. |
| call sites of `observeMaterials()` | Migrate project-scoped screens to `observeMaterialsForProject(projectId)`. |

## Implementation Notes

### Step 1: Entity field (nullable for safe migration)

```kotlin
data class OfflineMaterialEntity(
    @PrimaryKey(autoGenerate = true) val materialId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val projectId: Long? = null,   // NEW — nullable so existing rows backfill as NULL
    val name: String,
    // …unchanged…
)
```

Nullable (`Long?`) avoids a `NOT NULL` migration default and tolerates pre-existing rows whose project is unknown until next pull.

### Step 2: Migration 28 → 29

```kotlin
private val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE offline_materials ADD COLUMN projectId INTEGER")
    }
}
```

Register it in the `addMigrations(...)` list. (`INTEGER` is nullable in SQLite — matches `Long?`.)

### Step 3: Mapper

```kotlin
internal fun DamageMaterialDto.toMaterialEntity(): OfflineMaterialEntity =
    OfflineMaterialEntity(
        materialId = id,
        serverId = id,
        uuid = uuid ?: UUID.nameUUIDFromBytes("damage-material-$id".toByteArray()).toString(),
        projectId = projectId,   // NEW
        name = title ?: "Material $id",
        // …unchanged…
    )
```

### Step 4: Scoped DAO queries

```kotlin
@Query("SELECT * FROM offline_materials WHERE projectId = :projectId ORDER BY name")
fun observeMaterialsForProject(projectId: Long): Flow<List<OfflineMaterialEntity>>
```

Keep the global `observeMaterials()` only if a genuinely cross-project consumer exists; otherwise migrate all callers and remove it.

### Step 5: `remapMaterialId()`

Mirror `remapRoomId()`/`remapPropertyId()`: when a local material's `serverId` arrives, update dependent references. Verify whether any payloads carry a material ID that must be remapped before this is more than defensive.

## Observability

- Console log `material_sync_project_scope` (`material_id`, `project_id`) when a material is saved without a `projectId` (NULL after pull) to surface server responses missing `project_id`.

## Test Plan

- [ ] Migration test: open a v28 DB with materials, migrate to v29, confirm rows survive with `projectId = NULL`.
- [ ] Unit: `toMaterialEntity()` carries `project_id` through.
- [ ] Unit: `observeMaterialsForProject(A)` excludes project B's materials.
- [ ] Manual QA: two projects each with materials → materials list in project A shows only A's materials.

## Rollback Plan

Schema change involved: rolling back the app after install requires the destructive-migration fallback or a 29→28 downgrade path. Prefer rolling forward. Reverting the code before release is clean (drop the migration and field).

## Dependencies

- Requires: server already returns `project_id` in `DamageMaterialDto` (confirmed present).
- Blocking: none. Related to RP-FR-003 (pull-path mapper defaults) — this mapper sets `syncStatus = SYNCED`; do not change that default as part of this fix.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-032] Damage materials now record their project, so material lists are scoped per project instead of showing every project's materials.
```
