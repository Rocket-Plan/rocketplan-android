# Sweep: offline-sync identity reconciliation + delete-409 recovery

**Reviewer:** Claude
**Date:** 2026-06-07 14:01:46 PDT
**Branch:** `rp-cd-audit-fixes-2026-06-02`
**Commit range:** `be2b9f1..f30f9d8`
**Scope:** offline-first sync — pull-side identity reconciliation (duplicate-on-refresh) and
push-side delete conflict handling, verified against the `mongoose` backend and the iOS app.
**Status:** final. Working tree clean at write time (untracked `data/`,
`docs/reviews/deep_review_2026-06-05/`, `workflows/` are pre-existing and unrelated).

---

## TL;DR

A sweep of the offline sync layer surfaced two systemic bug classes, both now fixed with regression
tests and code review:

1. **Duplicate-on-refresh** — entities created offline keep a local (negative) PK + client `uuid` and
   only gain a `serverId` on push; the backend mints its own `uuid` (`HasUuid`) and is not given the
   client's, so a pull maps the server row to a *server-id* PK with a *different* `uuid`, and a
   PK-only `@Upsert` inserts a **second row**. Fixed per entity by reconciling pulled rows to the
   existing local row **by `serverId`** before upsert (shared `mergePulledRowsByServerId` helper).
2. **Delete-409 non-recovery** — every delete handler sends an optimistic-lock `updated_at` from the
   local row; most backend `destroy()` methods `assertNotStale` → **409** on a stale value. Handlers
   either retried the same stale timestamp until abandoned (Group A) or silently swallowed the 409 as
   success (Group B). Fixed by retrying once with `updated_at = null` (backend skips the lock →
   delete-wins), via shared `SyncHandlerUtils` helpers.

The discipline that ran through the sweep: **verify the linchpin against real code/backend before
filing or fixing.** It killed two false leads and caught two mid-implementation mistakes.

---

## Fixed (with tests + review)

| ID | Area | Root cause | Key fix |
|----|------|-----------|---------|
| RP-BUG-036 | Support conversations/messages | Pull upserts on PK only; server `uuid` ≠ client `uuid`; messages keyed to server conversation id | Reconcile by `serverId`; attach pulled messages to the **canonical local `conversationId`**; preserve existing local id even when the conversation is unresolvable |
| RP-FR-005 | Support attachments | Attachment FK stored the *server* message id, not the local message PK | Resolve owning message via `getSupportMessageByServerId`; skip if unresolved |
| RP-FR-006 | Support attachments | Attachment pull had no `serverId` reconcile → duplicates on re-pull | Reconcile attachments by `serverId`, preserve local PK + cached `localPath` |
| RP-BUG-037 | Notes / Equipment / MoistureLog / AtmosphericLog | Offline-create duplicate-on-refresh (the class above) | Extract `mergePulledRowsByServerId`; reconcile all four `save*` pull merges |
| RP-BUG-038 | Materials | Same class; missed by the RP-BUG-037 sweep | `saveMaterials(reconcileByServerId)`; `getMaterialsByServerIds` |
| RP-BUG-039 | Timecards | Down-sync **unwired** (`getTimecards`/`saveTimecards` had no callers) — server timecards never appeared locally (iOS pulls them) | Add `TimecardSyncService.syncTimecards`, wire into `TimecardViewModel`; reconcile by `serverId` so the new pull doesn't duplicate |
| RP-BUG-040 | All delete handlers | Stale-timestamp delete-409 not recovered (Group A retry-fail; Group B silent-swallow) | `resolveDeleteWithStaleRetry` / `resolveDeleteThrowingWithStaleRetry`; retry once with `updated_at = null` |
| RP-FR-003 | Locations | Embedded-locations pull clobbered dirty rows (missing `preserveDirty=true`) | Pass `preserveDirty = true` |

## Verified safe (checked, no change needed)

- **Rooms** — `RoomSyncService.resolveExistingRoomForSync` reconciles by `serverId` then `uuid`.
- **Photos** — no offline create; `persistPhotos` reconciles by `serverId`.
- **Projects** — reconcile by `serverId` then `uuid`; compound unique `(uuid, companyId)`.
- **Properties** — `persistSyncedPropertyAtomically` has a pending-upgrade path + `serverId` dedup.
- **WorkScope (catalog flow)** — pushes selections then pulls; no lingering local-PK ghost.
- **Damages** — pull-only; no offline create, no `isDirty` column, no `DamagePushHandler`.
- **Photo delete (409)** — `PhotoController.destroy` does not `assertNotStale`, so it never 409s.
- **RP-CD-005 (single-use 409 body)** — clean across all handlers (Equipment guards via `isConflict`;
  Property/Room recover via re-fetch; Timecard's `extractUpdatedAt` is the first body consumer).

## Filed/considered but correctly NOT shipped (verification killed the lead)

- **RP-FR-007 (damages tombstone dirty-guard)** — reverted: `offline_damages` has **no `isDirty`
  column** (pull-only), so the "missing guard" is correct by schema, not a defect. The `AND
  isDirty = 0` "fix" wouldn't have compiled.
- **Timecard *duplicate*** — not filed: there is **no timecard list-pull** (the real bug was the
  *unwired* down-sync, RP-BUG-039); the duplicate could not occur.

## Docs corrected

- `ARCHITECTURE.md` "ID Remapping" rewritten — `IdRemapService` is **dead code**; parent-id resolution
  is actually push-time re-resolution + SKIP-until-ready. Added a serverId-reconcile note.
- `ARCHITECTURE.md` migration text (explicit 10→30; destructive = dev-only) and `preserveDirty`.
- `RP-CD_rules.md` — added `RP-CD-014…017`; `CLAUDE.md` now references the rules (iOS-aligned).

---

## Backend / iOS facts established (mongoose, ios.rocketplantech.com)

- `App\Models\Traits\HasUuid::bootHasUuid` mints `Str::orderedUuid()` **only when `uuid` is empty**;
  the offline create requests carry the client value as `idempotency_key`, never `uuid` → server
  `uuid` is **not** echoed (root of the duplicate class). Timecard/Equipment/Moisture don't even
  `use HasUuid`.
- `Concerns/HandlesOptimisticLocking::assertNotStale` → `throw new HttpException(409)` on a stale
  `updated_at`, and **skips the check when `updated_at` is null** (basis for the delete-409 fix).
- iOS reconciles via a centralized `fetchOrCreateOffline*(serverId:)` primitive (fetch-by-serverId,
  uuid fallback) — the Android fixes port that pattern to the Room save layer. iOS pulls timecards
  (`TimecardService.getTimecards`), confirming RP-BUG-039 is a parity gap, not a design choice.

## Lessons / process notes

- **Verify before claiming.** Two earlier audit agents over-claimed (a hallucinated `getPendingDamages`
  line; "5 data-loss bugs"). Reading the actual save semantics (plain `@Upsert` doesn't delete absent
  rows) and the backend prevented filing speculative bugs.
- **Compile/test catches mischaracterization.** The RP-BUG-040 implementation revealed that
  Room/Location/Property delete APIs return `Unit` (Retrofit throws), not `Response<Unit>` — correcting
  the Group A/B split — and that the Equipment/Moisture tests mocked `throws` where production
  *returns* `Response.error` (exactly why the silent-swallow went unnoticed).

## Outstanding (not blocking)

- RP-BUG-039 timecard pull is a full fetch on screen open; could add the `updatedSince` incremental
  filter + checkpoint later.
- Pre-existing duplicates created before the reconcile fixes are not retroactively cleaned (the fixes
  prevent *new* ones).
- `IdRemapService` is dead code — a delete candidate.
- Codifying the recurring "reconcile server-sourced rows by `serverId`" pattern as a dedicated
  `RP-CD-018` rule.

## Commits (`be2b9f1..f30f9d8`, 19)

`be2b9f1` RP-FR-003 · `8cac10f` arch docs + RP-CD-014..017 · `61c613b`/`da36d32`/`ababad1` RP-BUG-036 ·
`71ff812`/`ba66e38` RP-FR-005 · `e73e57a` RP-FR-006 · `3b54e34`/`3be65c7` RP-BUG-037 · `5908693`
ID-remap doc · `3afde3e` RP-BUG-038 · `09fa3e0`/`fc0c518`/`3d239f7` RP-BUG-039 ·
`263e62d`/`1d4050b`/`f30f9d8` RP-BUG-040.
