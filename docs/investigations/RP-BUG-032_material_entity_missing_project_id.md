---
bug_id: RP-BUG-032
aliases: []
title: OfflineMaterialEntity lacks projectId field — materials cannot be scoped to project during sync
type: functional
classification: pre_existing_latent
source: review
found_in: "1.0.00"
fixed_in: "1.29 (32)"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_032_material_project_id_2026-06-03.md
related_review: null
related_test: null
last_updated: 2026-06-03
---

# Investigation: OfflineMaterialEntity missing projectId field

## Symptom

`OfflineMaterialEntity` does not have a `projectId` field. The DTO (`DamageMaterialDto`) contains `projectId` but it is not persisted to the entity, and materials are queried globally without project scoping.

## Discovery

- **Source:** Parity review vs iOS RP-BUG-160, RP-BUG-177
- **Evidence:** Code analysis confirmed
- **Found during:** iOS parity investigation 2026-06-03

## Root Cause (Confirmed)

### OfflineMaterialEntity Lacks projectId

**File:** `app/src/main/java/com/example/rocketplan_android/data/local/entity/OfflineEntities.kt`
**Lines:** 565-578

```kotlin
data class OfflineMaterialEntity(
    @PrimaryKey(autoGenerate = true)
    val materialId: Long = 0,
    val serverId: Long? = null,
    val uuid: String,
    val name: String,
    val description: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncVersion: Int = 0,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date(),
    val serverUpdatedAt: Date? = null,
    val lastSyncedAt: Date? = null
    // MISSING: projectId field!
)
```

### DTO Has projectId But It's Not Used

**File:** `app/src/main/java/com/example/rocketplan_android/data/model/offline/OfflineDtos.kt`

```kotlin
data class DamageMaterialDto(
    val id: Long,
    val uuid: String?,
    @SerializedName("project_id")
    val projectId: Long?,           // <-- EXISTS IN DTO
    @SerializedName("room_id")
    val roomId: Long?,
    ...
)
```

### toMaterialEntity() Ignores projectId

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/mapper/SyncEntityMappers.kt`
**Lines:** 667-681

```kotlin
internal fun DamageMaterialDto.toMaterialEntity(): OfflineMaterialEntity {
    val timestamp = now()
    return OfflineMaterialEntity(
        materialId = id,
        serverId = id,
        uuid = uuid ?: UUID.nameUUIDFromBytes("damage-material-$id".toByteArray()).toString(),
        name = title ?: "Material $id",
        description = description,
        syncStatus = SyncStatus.SYNCED,
        // MISSING: projectId = projectId, (not passed!)
        ...
    )
}
```

### Materials Are Queried Globally (No projectId Filter)

**File:** `app/src/main/java/com/example/rocketplan_android/data/local/dao/OfflineDao.kt`
**Lines:** 918-927

```kotlin
@Upsert
suspend fun upsertMaterials(materials: List<OfflineMaterialEntity>)

@Query("SELECT * FROM offline_materials ORDER BY name")
fun observeMaterials(): Flow<List<OfflineMaterialEntity>>  // No projectId filter!

@Query("SELECT * FROM offline_materials WHERE uuid = :uuid LIMIT 1")
suspend fun getMaterialByUuid(uuid: String): OfflineMaterialEntity?

@Query("SELECT * FROM offline_materials WHERE materialId = :materialId LIMIT 1")
suspend fun getMaterial(materialId: Long): OfflineMaterialEntity?
```

### IdRemapService Has No Material ID Remapping

**File:** `app/src/main/java/com/example/rocketplan_android/data/repository/sync/IdRemapService.kt`

The service handles ID remapping for:
- `remapProjectId()` - updates property, location, room payloads
- `remapPropertyId()`
- `remapLocationId()`
- `remapRoomId()` - updates notes, equipment, moisture logs, atmospheric logs, photos, albums, damages, work scopes

**CONFIRMED:** There is NO `remapMaterialId()` method.

## Impact Assessment

| Claim | Assessment |
|-------|------------|
| Materials cannot be scoped to project during sync | PARTIALLY TRUE - sync works via materialId/serverId |
| Cross-project material contamination risk | LOW in practice (serverId uniqueness), but design is fragile |
| Cannot query materials by project efficiently | TRUE - global `observeMaterials()` returns all materials |

**How sync actually works:**
- Materials are globally unique by server ID
- Deduplication via UUID during push
- Materials pulled per-room via moisture log associations

**The real failure scenario:**
1. `DamageMaterialDto.projectId` is available in API response
2. `toMaterialEntity()` never passes it to the entity
3. If same server material ID appears in multiple projects, no projectId-based filtering exists
4. Global `observeMaterials()` returns ALL materials from ALL projects

## Affected Code

| Location | Lines | Issue |
|----------|-------|-------|
| `OfflineEntities.kt` | 565-578 | No `projectId` field in entity |
| `OfflineDtos.kt` | 679-680 | `DamageMaterialDto.projectId` exists but unused |
| `SyncEntityMappers.kt` | 667-681 | `toMaterialEntity()` ignores projectId |
| `OfflineDao.kt` | 918-927 | No DAO query filters by projectId |
| `IdRemapService.kt` | 50-200 | No `remapMaterialId()` method |

## Proposed Fix Approach

1. **Add `projectId: Long` field to `OfflineMaterialEntity`**
2. **Update `toMaterialEntity()` to pass `projectId`**
3. **Add DAO query methods that filter by projectId**
4. **Add `remapMaterialId()` to IdRemapService** for consistency

## Observability

### Current Signals
- Local console logs: `material_cache_hit`, `material_cache_miss`
- Remote logs: None currently

### Gaps
- Cannot trace which project a material belongs to during sync
- Deduplication logic cannot use projectId as discriminator

### Proposed Instrumentation
- Local debug logs: `material_sync_project_scope`, `material_dedup_check`
- Remote logs: Error category `material_project_scope_missing`
- Key fields: `material_id`, `project_id`, `sync_outcome`

---

## Related

- iOS counterpart: `RP-BUG-160` (saveOrUpdateMaterial omits OfflineMaterial.projectId)
- iOS counterpart: `RP-BUG-177` (offline-created project equipment/materials not visible)