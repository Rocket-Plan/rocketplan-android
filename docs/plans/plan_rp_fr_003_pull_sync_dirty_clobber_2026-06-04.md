**Bug ID(s):** RP-FR-003
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-003_pull_sync_clobbers_dirty_rows.md) · [Plan](./plan_rp_fr_003_pull_sync_dirty_clobber_2026-06-04.md) · Review: pending · Parent: [RP-HD-001](../investigations/RP-HD-001_rp_cd_rule_audit.md)

# Fix Plan: [RP-FR-003] Dirty-aware merge at the pull-sync save layer

**Bug ID(s):** RP-FR-003 (violates RP-CD-002)
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-04
**State:** implemented

---

## Summary

Pull-sync can silently overwrite a locally-dirty row before it is ever pushed. Two halves combine: (1) nine `toEntity()` mappers hardcode `isDirty = false`, and (2) the bulk save methods end in a blind `dao.upsert*()` (Room `@Upsert` replaces **all** columns), so the mapper's `false` always wins — even on save paths that read `existing` and pass it to the mapper, because the mapper ignores it.

The investigation already completed a per-entity pull-path trace. **8 entities are confirmed reachable lost-edits** (CLOBBER); WorkScope, Room, Timecard, and log-photos are already safe. The fix adds a dirty-aware merge **on the pull path only** (not the mapper — merge semantics there are load-bearing and flipping the default risks the opposite bug: never accepting server updates). `Timecard` (`SyncEntityMappers.kt:925`, `isDirty = existing?.isDirty ?: false`) is the template.

### ⚠️ Caller-safety constraint (corrects the first draft)

The `save*()` methods are **not pull-only** — they are also the push-success save path. Verified callers in `data/repository/sync/handlers/`:

- `EquipmentPushHandler` → `saveEquipment` (`:61, :85, :167`)
- `MoistureLogPushHandler` → `saveMoistureLogs` (`:35, :59, :138`)
- `LocationPushHandler` / `PropertyPushHandler` / `RoomPushHandler` → `saveLocations`
- `AtmosphericLogPushHandler` → `saveAtmosphericLogs`; `PhotoPushHandler` → `savePhotos`; `ProjectPushHandler` → `saveProjects`

If the merge were unconditional inside `save*()`, a **successful push** (whose existing local row is still dirty, and whose incoming row is the freshly-synced clean server copy) would preserve the *old dirty row* and discard the clean one — leaving the entity dirty/stale after its queue op is removed. That's a worse bug than the one we're fixing.

**Therefore the dirty-preserving merge must be pull-specific.** Push-success saves must keep force-applying server state (clearing `isDirty`, `syncStatus`, refreshing `serverId`/`serverUpdatedAt`).

## Scope — entities the fix must cover

| Entity | Save method (blind upsert) | by-serverId lookup exists? |
|--------|----------------------------|----------------------------|
| Project | `saveProjects` → `upsertProjects` (`:693`) | ✅ `getProjectByServerId(serverId, companyId)` |
| Property | `persistSyncedPropertyAtomically` → dirty-preserving (`currentExisting.isDirty` guard before `upsertProperty`) | ✅ `getPropertyByServerId` |
| Location | `saveLocations` → `upsertLocations` (`:739`) | ✅ `getLocationByServerId` |
| Photo | `savePhotos` → `upsertPhotos` (`:1007`) | ✅ `getPhotoByServerId` |
| AtmosphericLog | `saveAtmosphericLogs` → `upsertAtmosphericLogs` (`:948`) | ❌ only by uuid / local id |
| MoistureLog | `saveMoistureLogs` → `upsertMoistureLogs` (`:1035`) | ❌ only by uuid / local id |
| Equipment | `saveEquipment` → `upsertEquipment` (`:1019`) | ❌ only by uuid / local id |
| Note | `saveNotes` → `upsertNotes` (`:1039`) | ❌ only by uuid / local id |

**Out of scope (already safe — do not touch):** WorkScope (online-only writes, never staged dirty), Room (`SyncEntityMappers.kt:434` preserves dirt when `serverId == null`), Timecard (`:925`), log-photos (`saveOrUpdateLogPhotos` reads existing).

## Implementation Notes

### Step 1: Make the merge pull-specific (preferred: `preserveDirty` flag)

Add a `preserveDirty: Boolean = false` parameter to each of the 8 `save*()` methods. The default `false` preserves today's behavior for **every existing caller** (all push-success/conflict/UI saves force-apply server state, clearing dirt). Only the pull-sync `*SyncService` call sites pass `preserveDirty = true`.

```kotlin
suspend fun saveProjects(
    incoming: List<OfflineProjectEntity>,
    preserveDirty: Boolean = false,
) = withContext(ioDispatcher) {
    if (!preserveDirty) { dao.upsertProjects(incoming); return@withContext }  // push path unchanged
    val existing = dao.getProjectsByServerIds(incoming.mapNotNull { it.serverId }, companyId)
        .associateBy { it.serverId }
    val merged = incoming.map { server ->
        val local = server.serverId?.let { existing[it] }
        if (local?.isDirty == true) {
            remoteLogger?.log(WARN, TAG, "pull_sync_preserved_dirty_row",
                mapOf("entity" to "project", "server_id" to server.serverId.toString()))
            local                       // preserve local dirty row; drop the server overwrite
        } else server
    }
    dao.upsertProjects(merged)
}
```

(Alternative if a boolean param spreads too far: add explicit `savePulled*()` wrappers that do the merge then delegate to the force-apply `save*()`. Functionally equivalent; pick whichever reads cleaner per entity. `saveProjects` is special — it has extra validation at `:644`; thread the flag through, don't bypass it.)

**Merge policy** for the `preserveDirty = true` path:
- **No existing row** → insert server row as-is (`isDirty = false`).
- **Existing clean row** (`isDirty == false`) → overwrite with server data (normal pull).
- **Existing dirty row** (`isDirty == true`) → preserve the local row: keep `isDirty = true`, locally-edited fields, `syncStatus = PENDING`; do not let server `serverUpdatedAt` regress the pending push.

Mirrors the dirt-aware paths already in code (`LocalDataService.kt:353`, `:731`) and the Timecard mapper.

**Conflict case — DECIDED:** dirty **and** server strictly newer → **keep the local dirty row and let the push/409 path resolve later.** Do **not** create `OfflineConflictResolutionEntity` during pull in this pass. (Safer and consistent with offline-first; matches your decision.)

### Step 2: Wire `preserveDirty = true` into the pull call sites only

Set the flag at the pull-sync services that feed each save (per the trace): `ProjectSyncService`, `PropertySyncService`, the project-metadata/`ProjectMetadataSyncService` pulls (Location, AtmosphericLog, MoistureLog, Equipment, Note) and `PhotoSyncService`. Push handlers and UI saves are left on the default `false`. Grep each of the 8 `save*` callers and confirm push vs pull before flipping — this is the load-bearing step.

### Step 3: Add bulk by-serverId lookups for all 8

Project/Property/Location/Photo have single `getXByServerId`; Equipment/Note/MoistureLog/AtmosphericLog have none. To avoid an N+1 inside the merge **and** keep the 8 call sites uniform, add a **bulk** query for all 8:

```kotlin
@Query("SELECT * FROM offline_equipment WHERE serverId IN (:serverIds)")
suspend fun getEquipmentByServerIds(serverIds: List<Long>): List<OfflineEquipmentEntity>
// + getProjectsByServerIds, getPropertiesByServerIds, getLocationsByServerIds,
//   getPhotosByServerIds, getNotesByServerIds, getMoistureLogsByServerIds, getAtmosphericLogsByServerIds
```

(`getPropertiesByServerId` returns a list for one id today — add the plural `IN` variant rather than overload it.)

### Step 4: Do NOT change the mapper defaults

Leave `isDirty = false` in the 9 mappers. The mapper produces a "fresh from server" view; the save layer decides whether to apply it. Changing the mapper would break the legitimate clean-overwrite path and the services that intentionally force-refresh.

## Observability

- Add WARN `pull_sync_preserved_dirty_row` (entity type + server id) whenever the merge keeps a dirty row over a server copy. This both surfaces real occurrences (proving reachability, which the investigation lists as not-yet-reproduced) and validates the fix in QA logs.
- Optionally count preserved-vs-overwritten per sync run for a one-line summary log.

## Test Plan

- [ ] Unit (per entity, 8): pull save (`preserveDirty=true`), existing dirty row + incoming server row → keeps local (`isDirty=true`, local fields, `PENDING`).
- [ ] Unit: pull save, existing clean row → server data applied.
- [ ] Unit: pull save, no existing row → server row inserted as-is.
- [ ] **Regression (the corrected risk):** push-success save (`preserveDirty=false` default), existing dirty row + incoming synced clean row → server (clean) row applied, `isDirty` cleared, `serverId`/`serverUpdatedAt` refreshed. Verify for `saveEquipment`/`saveMoistureLogs`/`saveLocations`/`savePhotos`/`saveProjects`/`saveAtmosphericLogs`/`saveNotes`.
- [ ] Unit: dirty row → successful push (clears dirty) → pull → server row now applied.
- [ ] Regression: WorkScope/Room/Timecard pull paths unchanged.
- [ ] Manual QA: edit a note offline; trigger a pull before the push runs; confirm the edit survives and then pushes.

## Rollback Plan

Revert the save-method merges and the added DAO queries. No schema change (only new SELECT queries). Behavior returns to blind upsert.

## Dependencies

- Conflict case: **DECIDED** — keep local dirty row, let push/409 resolve; no conflict rows created during pull.
- Requires: per-caller pull-vs-push audit of all 8 `save*` call sites (Step 2) before flipping any flag.
- Blocking: closing **RP-HD-001** depends on this + RP-FR-004. **RP-BUG-029** shares this plan's clean-synced predicate — sequence 029 after this.

## Changelog Entry

```markdown
### Fixed
- [RP-FR-003] Pull-sync no longer overwrites unsynced local edits; a dirty row is preserved until its own push completes.
```
