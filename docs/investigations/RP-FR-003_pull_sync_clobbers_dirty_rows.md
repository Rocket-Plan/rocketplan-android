---
bug_id: RP-FR-003
aliases: []
title: Pull-sync save path can overwrite locally-dirty rows (RP-CD-002)
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: open
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
parent: RP-HD-001
violates: RP-CD-002
priority: P1
last_updated: 2026-06-02
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

## Suggested next step

Trace, per entity, the pull → mapper → save chain and identify which ones land on a blind upsert.
For those, add a dirty-aware merge at the **save layer** (preserve `isDirty`/local fields when the
existing row is dirty and the server row isn't newer), not in the mapper. Promote to `RP-BUG` once a
concrete lost-edit path is demonstrated for a specific entity.

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
