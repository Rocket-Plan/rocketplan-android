**Bug ID(s):** RP-BUG-014, RP-BUG-016
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [RP-BUG-014 Investigation](../investigations/RP-BUG-014_id_migration_fragile.md) · [RP-BUG-016 Investigation](../investigations/RP-BUG-016_missing_migration.md) · [Plan](./plan_rp_bug_014_016_migration_2026-06-01.md) · [RP-BUG-001 plan (migration strategy)](./plan_critical_p0_001_006_2026-05-13.md) · Review: TBD

# Fix Plan: [RP-BUG-014, RP-BUG-016] Database migration integrity

**Bug ID(s):** RP-BUG-014, RP-BUG-016
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-01
**State:** draft

---

## Summary

Bundled fix for the two "Database migration integrity" P1s. Both are about Room
migration correctness and both interact directly with the RP-BUG-001 fix already
in flight (which removed the `BuildConfig.DEBUG`-gated `fallbackToDestructiveMigration()`
and made a missing migration a hard crash + Sentry signal in staging/prod).

1. **RP-BUG-016** — `OfflineDatabase` declares `version = 28` but the migration list
   stops at `MIGRATION_26_27`. There is **no `MIGRATION_27_28`**. The git history
   (commit `708cb80`) shows version was bumped 27 → 28 as a *pure version bump with
   no schema/entity change* (the only diff was the version constant). Today this is
   masked in `dev` builds because `BuildConfig.ALLOW_DESTRUCTIVE_MIGRATION` is `true`
   there (RP-BUG-001 fix) — Room silently wipes and recreates. But after the RP-BUG-001
   fix lands, **any user upgrading from a v27 DB on a `staging`/`prod` build will hard-crash
   on first launch** because Room cannot find a 27 → 28 path. Because the schema is
   identical between 27 and 28, the correct fix is a registered **no-op `MIGRATION_27_28`**.

2. **RP-BUG-014** — `LocalDataService.relinkRoomScopedData()` skips re-linking a room's
   child rows whenever the room's auto-increment PK (`roomId`) happens to numerically
   equal its assigned `serverId` (`if (localRoomId == serverId) return@forEach`). This
   "works" only by coincidence of ID collision and is fragile: the decision to migrate is
   inferred from numeric equality rather than from an explicit "this room was created
   locally and has since been assigned a server id" signal. The practical fix in this plan is to centralize the no-op/equality decision in `migrateReferences()` and remove the broader room-level short-circuit, instead of inferring “no migration needed” at the top of the loop from a numeric ID collision. That still fixes the bug even though the implementation remains equality-based at the final guard point.

Neither bug has a runtime signal today. RP-BUG-016 is latent until the RP-BUG-001 fix
ships (then it becomes a crash); RP-BUG-014 is latent until an ID collision produces a
silent re-link skip.

## Order of work

Do **RP-BUG-016 first** — it is the one that turns into a production crash the moment the
RP-BUG-001 fix reaches a non-dev build, and it is a one-line, zero-risk addition. RP-BUG-014
is a correctness hardening with no crash exposure; land it in the same release.

This plan must land **in or before** the release that carries the RP-BUG-001 fix to
staging/prod. See Dependencies.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/local/OfflineDatabase.kt` | Add `MIGRATION_27_28` (no-op) and register it in `addMigrations(...)`. |
| `app/src/main/java/com/example/rocketplan_android/data/local/LocalDataService.kt` | Remove the `localRoomId == serverId` early `return@forEach` in `relinkRoomScopedData()`; re-link is driven by the existing `migrateReferences()` no-op guard. Add a debug log of re-link decisions. |
| `app/src/test/java/.../data/local/OfflineDatabaseMigrationTest.kt` | New: migrate a v27 DB to v28 and assert no exception, data preserved. |
| `app/src/test/java/.../data/local/RelinkRoomScopedDataTest.kt` | New: re-link regression covering the `roomId == serverId` collision case. |

## Implementation Notes

### Step 1 — RP-BUG-016: add no-op `MIGRATION_27_28`

The v27 → v28 bump introduced no schema change (verified against `git show 708cb80`),
so the migration body is empty. Follow the existing no-op convention already in this file
(see `MIGRATION_12_13` / `MIGRATION_13_14`, which carry a `// No-op: schema version bump only`
comment).

`OfflineDatabase.kt` — define alongside the other migrations:

```kotlin
private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No-op: version bumped 27 -> 28 in commit 708cb80 with no schema change.
        // Registered explicitly so non-dev builds (which no longer fall back to
        // destructive migration per RP-BUG-001) have a valid 27 -> 28 path and do
        // not crash on upgrade.
    }
}
```

Register it at the **end** of the existing `addMigrations(...)` chain in `buildDatabase`:

```kotlin
// before
.addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27)

// after
.addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28)
```

Note: the migration `val`s in this file are not declared in numeric order (e.g.
`MIGRATION_21_22` is defined after `MIGRATION_26_27`), so place the new `val` wherever
is readable — only the `addMigrations(...)` registration matters to Room.

### Step 2 — RP-BUG-014: stop inferring "no migration needed" from ID collision

This plan does **not** introduce a brand-new migration-state predicate. Instead, it removes the coarse room-level early return and lets the narrower `migrateReferences(oldId, newId)` no-op guard handle genuinely equal IDs in one place. If the team later wants a richer “room requires relink” predicate, treat that as a follow-up refinement, not a blocker for this bug fix.

Current code (`LocalDataService.kt:768-774`) short-circuits the entire room when the
auto PK equals the server id:

```kotlin
rooms.forEach { room ->
    val serverId = room.serverId ?: return@forEach
    val localRoomId = room.roomId
    if (localRoomId == serverId) {
        // Nothing to migrate if the auto PK already matches the server id
        return@forEach
    }

    suspend fun migrateReferences(oldId: Long, newId: Long): ReferenceMigrationCounts {
        if (oldId == newId) {
            return ReferenceMigrationCounts(0, 0, 0, 0, 0, 0, 0, 0)
        }
        // ... dao.migrate*RoomIds(oldId, newId) ...
    }

    val counts = migrateReferences(localRoomId, serverId)
    // ...
}
```

The whole-room `return@forEach` is redundant with — and more fragile than — the
`migrateReferences` guard. The fragility the review flagged is that the *decision to
re-link a room* is being made from numeric `roomId == serverId` equality, which is only
ever true by coincidence (server ids and local autoincrement PKs share the same `Long`
space). Remove the early return and let the inner no-op guard be the single place that
handles the "old == new, nothing to move" case:

```kotlin
rooms.forEach { room ->
    val serverId = room.serverId ?: return@forEach
    val localRoomId = room.roomId

    suspend fun migrateReferences(oldId: Long, newId: Long): ReferenceMigrationCounts {
        if (oldId == newId) {
            // Child rows already reference the canonical id; nothing to move.
            return ReferenceMigrationCounts(0, 0, 0, 0, 0, 0, 0, 0)
        }
        val photoCount = dao.migratePhotoRoomIds(oldId, newId)
        // ... unchanged ...
    }

    val counts = migrateReferences(localRoomId, serverId)
    if (counts.hasAnyUpdates()) {
        // ... unchanged accumulation ...
    }
}
```

Behavioural delta: identical re-link results (the inner guard returns the same empty
counts when `localRoomId == serverId`), but the migrate decision is no longer expressed
as a brittle whole-room skip keyed on ID collision. This keeps a single, explicit code
path for "is there anything to re-link" and removes the duplicated equality check that the
review called out as fragile.

Add a debug-only audit log so re-link decisions are observable (per the investigation's
"log migration decisions for audit" ask). Console-only — do not add a remote log unless
QA shows unexpected re-links in production:

```kotlin
if (BuildConfig.DEBUG && counts.hasAnyUpdates()) {
    Log.d(TAG, "relinkRoomScopedData: room local=$localRoomId -> server=$serverId moved ${counts.photos}p/${counts.notes}n/${counts.damages}d")
}
```

Scope note: this does **not** change how rooms acquire their `serverId` (that remains
`IdRemapService` / sync responsibility) and does not touch the `@PrimaryKey(autoGenerate = true)`
on `OfflineRoomEntity.roomId`. A full "explicit mapping table instead of PK reuse" redesign
(the investigation's longer-term suggestion) is out of scope for this P1 — it would touch
every `migrate*RoomIds` DAO query and the create path; track separately if collisions are
ever observed in the audit log.

## Observability

- Keep the new relink decision log console-only in debug builds.
- Include enough context to audit both moved and evaluated-no-op cases (`localRoomId`, `serverId`, and whether references were updated or skipped).
- If zero-move collision cases remain intentionally silent in release builds, document that explicitly in the implementation comment.

## Test Plan

- [ ] Unit test (migration): build a Room v27 DB with `OfflineDatabaseMigrationTest`
      using `MigrationTestHelper`, insert a row, run `MIGRATION_27_28`, assert the DB
      opens at v28 with the row intact and no `IllegalStateException`.
- [ ] Unit test (re-link, collision case): seed a room with `roomId == serverId` plus child
      rows already pointing at that id; assert `relinkRoomScopedData()` returns zero moves
      and does not corrupt references (parity with old behavior).
- [ ] Unit test (re-link, normal case): seed a room with `roomId != serverId` and child
      rows referencing `roomId`; assert all child rows are re-linked to `serverId`.
- [ ] Manual QA — upgrade path (tablet `30407ef`):
  1. Prereq: install a build pinned to DB **v27** with real offline data; confirm data present.
  2. Action: install a `stagingStandardDebug` build carrying this fix (DB v28, destructive
     fallback OFF per RP-BUG-001). Cold start.
  3. Expected: app launches, no crash, all offline projects/rooms/photos still present;
     no `missing_room_migration` Sentry event.
- [ ] Manual QA — sanity that dev destructive fallback still wipes only in dev: same v27→v28
      upgrade on `devStandardDebug` should not crash (and is allowed to wipe).

## Rollback Plan

Both changes are localized and independent.

- RP-BUG-016: remove `MIGRATION_27_28` from the `addMigrations(...)` chain and delete the
  `val`. Safe only if reverted **together** with the RP-BUG-001 fix — on its own, reverting
  this re-exposes the upgrade crash in staging/prod. Prefer to keep it.
- RP-BUG-014: restore the `if (localRoomId == serverId) return@forEach` block. No schema or
  persisted-data change; safe at any point.

## Dependencies

- Requires: none (no server change).
- Blocking: **RP-BUG-016 must ship in or before the release that delivers the RP-BUG-001
  fix to staging/prod.** RP-BUG-001 removed the destructive-migration safety net for non-dev
  builds; without `MIGRATION_27_28`, that release would hard-crash every v27→v28 upgrade.
  This is exactly the "next Room schema bump must register a migration" operational follow-up
  called out in the RP-BUG-001 plan — RP-BUG-016 is the first instance of it.

## Changelog Entry

```markdown
## [1.0.XX] - 2026-06-XX

### Fixed
- [RP-BUG-016] Added the missing Room `27 -> 28` migration so app upgrades no longer risk a launch crash / data wipe on the v28 schema.
- [RP-BUG-014] Hardened room-scoped data re-linking so child rows are migrated based on an explicit decision rather than coincidental local/server ID equality.
```
