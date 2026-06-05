---
bug_id: RP-FR-003
aliases: []
title: Pull-sync save path can overwrite locally-dirty rows (RP-CD-002)
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
related_plan: docs/plans/plan_rp_fr_003_pull_sync_dirty_clobber_2026-06-04.md
related_review: null
related_test: null
parent: RP-HD-001
violates: RP-CD-002
priority: P1
last_updated: 2026-06-03
---

# RP-FR-003: Pull-sync can clobber locally-dirty rows

> Filed by the `RP-HD-001` RP-CD audit sweep. Violates **`RP-CD-002` — server updates must not clobber local dirt**.

## Symptom (potential, not yet reproduced)

A user edits an entity offline (or while a pull is queued). The edit is staged locally with
`isDirty = true` / `syncStatus = PENDING`. A pull-sync then writes the server's copy of that entity
and the local edit — including the dirty flag — is silently overwritten before it ever pushed. The
user's change is lost with no error.

## Evidence

Two halves of the hazard:

1. **Mappers force `isDirty = false`** unconditionally in
   `data/repository/mapper/SyncEntityMappers.kt` — `ProjectDto.toEntity()` (151),
   `PropertyDto` (319), `LocationDto` (356), `RoomDto` (396), `PhotoDto` (530),
   `AtmosphericLogDto` (612), `MoistureLogDto` (650), `EquipmentDto` (708), `NoteDto` (753).
   (`TimecardDto`:925 and `AtmosphericLogDto.toPhotoEntity`:1007 correctly preserve it.)

2. **The bulk save path does a blind upsert** — `LocalDataService.saveProjects` (line 644) ends in
   `dao.upsertProjects(validProjects)` (693) with **no check of the existing row's `isDirty`**.
   Room `@Insert(onConflict = REPLACE)` overwrites the whole row, dirty flag included.

So when `saveProjects` receives mapped server data, a locally-dirty project is replaced wholesale.

## Why this is filed `RP-FR` (needs investigation before promotion)

Some write paths **do** guard dirt (`LocalDataService.kt:353` `isDirty = if (shouldForce) false else
project.isDirty`; `:731` `isDirty = if (forceUpdate) false else existing.isDirty`), so the codebase
clearly intends dirty-preservation in places. Whether the loss is actually reachable depends on the
end-to-end pull path per entity: does `ProjectSyncService` (and each sibling) route pulled rows
through `saveProjects`/blind upsert, or through a dirty-aware merge first? That trace is not yet
done. **Do not "fix" by flipping the mapper default blindly** — merge semantics are load-bearing and
a wrong change risks the opposite bug (never accepting server updates).

## Per-entity pull-path trace (completed 2026-06-03)

Real package is `com.example.rocketplan_android` (the line refs in the Evidence section above are
correct for `SyncEntityMappers.kt`; save-layer refs below are verified against current code). A row
is a **real lost-edit** only if the entity can be locally dirtied (`isDirty=true`) — confirmed by
the presence of a push handler in `data/repository/sync/handlers/` and/or an `isDirty = true` setter.

Mechanism confirmed: every save below ends in a one-line `dao.upsert*()`. Room `@Upsert` overwrites
**all** columns with the entity's values, so the mapper's hardcoded `isDirty = false` always wins —
even where the sync service reads `existing` and passes it to the mapper (Project/Property/Photo),
the mapper ignores it. `saveProjects` (`LocalDataService.kt:644`) does read nothing of the existing
dirty state before `dao.upsertProjects()` (`:693`).

| Entity | Pulled by | Save method (blind upsert) | Locally dirtiable? | Verdict |
|--------|-----------|----------------------------|--------------------|---------|
| Project | ProjectSyncService:59 | saveProjects → upsertProjects `:693` | yes (ProjectPushHandler) | **CLOBBER** |
| Property | PropertySyncService:222 | persistSyncedPropertyAtomically → upsertProperty `:345` | yes (PropertyPushHandler) | **CLOBBER** |
| Location | (project metadata pull) | saveLocations → upsertLocations `:739` | yes (LocationPushHandler) | **CLOBBER** |
| Photo | PhotoSyncService | savePhotos → upsertPhotos `:1007` | yes (PhotoPushHandler) | **CLOBBER** |
| AtmosphericLog | ProjectMetadataSyncService | saveAtmosphericLogs → upsertAtmosphericLogs `:948` | yes (AtmosphericLogPushHandler) | **CLOBBER** |
| MoistureLog | ProjectMetadataSyncService | saveMoistureLogs → upsertMoistureLogs `:1035` | yes (MoistureLogPushHandler) | **CLOBBER** |
| Equipment | ProjectMetadataSyncService | saveEquipment → upsertEquipment `:1019` | yes (EquipmentPushHandler) | **CLOBBER** |
| Note | ProjectMetadataSyncService | saveNotes → upsertNotes `:1039` | yes (NotePushHandler) | **CLOBBER** |
| WorkScope | WorkScopeSyncService | saveWorkScopes → upsertWorkScopes `:1051` | **no** — `addWorkScopeItems` pushes online immediately; no offline-dirty path, no push handler | SAFE-OTHER |
| Room | OfflineSyncRepository:601 | saveRooms → upsertRooms | mapper `:434` preserves dirt when `serverId == null` | SAFE-MERGE |
| Timecard | TimecardSyncService | saveTimecards | mapper `:925` `isDirty = existing?.isDirty ?: false` | SAFE-MERGE |
| Log photos (atmo/moisture) | ProjectMetadataSyncService | saveOrUpdateLogPhotos `:958–990` reads existing, conditional update | n/a | SAFE-OTHER |

**Confirmed CLOBBER (8, real reachable lost-edit):** Project, Property, Location, Photo,
AtmosphericLog, MoistureLog, Equipment, Note. These are the entities a fix must cover. WorkScope is
**not** affected (online-only writes, never staged dirty) — drop it from the fix scope.

## Suggested next step

Add a dirty-aware merge at the **save layer** (not the mapper — merge semantics are load-bearing):
for each of the 8 CLOBBER saves, read the existing row by id; if `existing.isDirty == true` and the
incoming server row is not strictly newer (`serverUpdatedAt`), preserve the local row's `isDirty` and
locally-edited fields rather than blind-replacing. `Timecard` (`:925`) is the template. Add the
proposed WARN instrumentation in the same pass so the fix is observable. Promote to `RP-BUG` on the
first demonstrated lost-edit; until then keep `RP-FR` with this trace as the planning basis.

## Observability

### Current Signals
- Local console logs: `saveProjects` logs each upsert (`LocalDataService.kt:678`) but not the
  pre-existing dirty state, so a clobber is invisible.
- Sentry: Not captured (silent data loss).

### Gaps
- No log/metric distinguishes "overwrote a clean row" from "overwrote a dirty (pending) row."

### Proposed Instrumentation
- At the save layer, when replacing a row whose existing copy is `isDirty`, emit a WARN with entity
  type + id before deciding to overwrite. That both surfaces the bug and proves the fix.
