# Code Review: Planned-batch fixes (RP-BUG-003/007/008/010/011/014/015/016 + RP-HD-002/003)

**Bug ID(s):** RP-BUG-003, 007, 008, 010, 011, 014, 015, 016; RP-HD-002, RP-HD-003
**Reviewer:** Claude (manual verification against the diffs + a deep pass on RP-BUG-010)
**Date:** 2026-06-02
**Branch:** bug-fixes-planned-2026-06-02
**Status:** final

---

## Scope

Implementation of the remaining `planned` bugs. 8 source files changed; RP-HD-002/003
were already present in `master` (tracker correction only). Verified each fix against
its plan; ran `compileDevStandardDebugKotlin` + full `testDevStandardDebugUnitTest`.

## Per-bug verdicts

| Bug | Change | Verdict |
|-----|--------|---------|
| RP-BUG-016 | No-op `MIGRATION_27_28` added + registered in `addMigrations` | ✅ correct |
| RP-BUG-008 | `SyncCheckpointStore` → `EncryptedSharedPreferences` + fail-safe null fallback + Sentry on init failure + legacy cleartext file deleted | ✅ correct |
| RP-BUG-014 | Removed redundant outer `localRoomId == serverId` skip; inner `oldId == newId` guard remains | ✅ correct |
| RP-BUG-007 | `PhotoCacheManager` uses `RetrofitClient.plainHttpClient` instead of bare `OkHttpClient()` | ✅ correct |
| RP-BUG-011 | Config-driven cert pins via `BuildConfig.CERT_PINS` (from `local.properties`), safe empty default, only `apiHost` pinned | ✅ correct code — real prod pins must be added to `local.properties` (`cert.pins.prod`/`staging`) to actually pin |
| RP-BUG-003 | Lock-guarded `enqueueLocked`/`pollLocked`/`clearLocked` helpers around `queue`/`taskIndex` | ✅ ok |
| RP-BUG-015 | `750` → `PENDING_OPS_DEBOUNCE_MS` constant (behavior unchanged) | ✅ cosmetic |
| RP-BUG-010 | Removed `syncJob.join()`; concurrency cap via `deferredProjectSyncs` set drained on completion | ✅ correct after rework (see below) |
| RP-HD-002 | Migration body guarded against non-cancellation exceptions (`CancellationException` rethrown; `Throwable` → Sentry) | ✅ already in master |
| RP-HD-003 | Crash-safety ordering comment for legacy-clear vs encrypted-save | ✅ already in master |

## RP-BUG-010 — detail

The first cut of the concurrency cap re-enqueued the deferred job into the live
queue and `return`ed, which (with `join()` removed) caused `processLoop` to
busy-spin re-deferring while a project sync was in-flight, plus an unsynchronized
read of `activeProjectSyncJobs.size`. The committed version fixes both:

- Deferred project syncs are held in a `deferredProjectSyncs` set (not the live
  queue), so `processLoop` suspends normally on an empty queue — no spin.
- On sync completion the `finally` block drains one deferred job under the mutex
  and re-enqueues it outside the lock; defer-add and drain are serialized by the
  same mutex, so no lost wakeups.
- The cap check (size check + register/defer) is fully inside one `mutex.withLock`,
  and `syncJob.start()` is gated on a boolean returned from that block — no
  unsynchronized map reads remain.

The post-`join()` follow-up work (photo-sync enqueue, foreground resume) was moved
into the `syncJob` coroutine body so it runs on completion without blocking the loop.

## Verification

- `compileDevStandardDebugKotlin` — clean.
- `testDevStandardDebugUnitTest` — full suite green.
- Room-migration pre-commit guard exercised: blocks a version bump without a
  registered migration, passes the `MIGRATION_27_28` bump.

## Follow-ups

- RP-BUG-011: supply production cert pins in `local.properties` before relying on pinning.
- RP-BUG-023 (`open`) remains a product decision; RP-HD-001 (`investigating`) is an ongoing audit.
