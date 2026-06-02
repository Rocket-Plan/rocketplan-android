# Code Review: Bug Bundle 2 (RP-BUG-002/004/005/009/012/013/017/018/019/020/021/022)

**Bug ID(s):** RP-BUG-002, 004, 005, 009, 012, 013, 017, 018, 019, 020, 021, 022 (+ RP-BUG-028 discovered)
**Plans:** plan_rp_bug_002_018_logging_pii, _004_connectivity, _005_auth_token_apply, _009_019_022_photo_cache, _012_application_coroutine_scope, _013_017_020_image_queue, _021_magic_numbers (all 2026-06-01)
**Reviewer:** Claude (high-effort, 7-angle finder + verify)
**Date:** 2026-06-01 15:42:47
**Branch:** bug-bundle-2026-05-18
**Status:** final

---

## Scope

Working-tree implementation of 7 fix plans (12 bugs) plus the review fixes applied
afterward. 9 source/test files changed. Docs excluded from correctness review.

Uncommitted at review start: see `git status --porcelain` (all `app/src` + `docs`
changes for this bundle).

## Method

Seven independent finder angles (line-by-line, removed-behavior, cross-file,
reuse, simplification, efficiency, altitude + test-correctness) run in parallel,
candidates de-duplicated and verified against the code. ~37 candidates → 10
distinct findings.

## Findings and resolutions

| # | Sev | Finding | Resolution |
|---|-----|---------|-----------|
| 1 | High | `SecureStorage.saveAuthToken` published the token to in-memory state even when `commit()` returned false → phantom session, signed out on cold start (undermined RP-BUG-005) | **Fixed**: `saveAuthTokenInternal` returns `Boolean`; `saveAuthToken` throws `IOException` on failure and only publishes on success; migration retains the legacy token on failed commit |
| 2 | Med | 020 recovery catch marked assembly FAILED without failsCount++/backoff/retry/queue-advance | **Fixed**: extracted shared `failAssemblyWithBackoff()` used by both the upload-failure path and the recovery catch |
| 3 | Med | Plan-specified unit tests were missing | **Fixed**: added `PhotoCacheManagerTest` (5), `SyncQueueManagerTest` (2), extended `ImageProcessorQueueManagerTest` (+3) |
| 4 | Low-Med | Sentry test asserted only `captureMessage`, not the tag | **Fixed**: asserts `setTag("event","auth_token_commit_failed")` on a concrete `IScope` mock and that `saveAuthToken` throws `IOException` |
| 5 | Low | PhotoCache partial-delete byte divergence | **Deferred**: new behavior is more correct than the old (does not mark a still-present file as evicted); transient under-count self-corrects |
| 6 | Low-Med | Connectivity one-shot `AtomicBoolean` suppressed all later reasons | **Fixed**: replaced with a per-reason `ConcurrentHashMap`-backed set |
| 7 | Low | `launchStartupTask` swallows recovery failures | **Deferred**: it replaced prior crash-on-throw (safer); escalation is a product call |
| 8 | Low | `shutdown()` 10s `awaitTermination` block | **Fixed**: grace 10s→3s + doc "must not be called on the main thread" |
| 9 | Low | `RetryConfig` was dead indirection | **Fixed**: now a constructor param (real injection seam), exercised by new tests |
| 10 | Low | LRU loop used `return@forEach` (continue) instead of `break` | **Fixed** |

## Discovered during follow-up (registered separately)

- **RP-BUG-028** — the legacy DataStore→encrypted auth-token migration was dead
  code (`createMigrationDeferred`/`initMigrationDeferred` never invoked;
  `getAuthTokenSync` fallback was a no-op `async { null }`). Fixed by lazily
  starting the real migration under the mutex in `getAuthTokenSync` and removing
  the dead `initMigrationDeferred`. Regression of RP-BUG-006 (both introduced in
  commit e261a77).
- **ImageProcessorRealtimeManagerTest** — 3 stale assertions encoded the
  pre-change contract; production `shouldIgnoreUpdate` was deliberately updated
  to ignore premature `"processing"` while QUEUED/CREATING/CREATED/UPLOADING.
  Tests updated to match intended behavior; added a QUEUED case.

## Verification

- `./gradlew compileDevStandardDebugKotlin compileDevStandardDebugUnitTestKotlin` — clean.
- `./gradlew testDevStandardDebugUnitTest --rerun-tasks` — **BUILD SUCCESSFUL**, full
  suite green (no failures). The previously-flaky `SecureStorageTest` NPEs and the
  `ImageProcessorRealtimeManagerTest` failures are resolved.

## Not in scope / still open

- RP-BUG-003/007/008/010/011/014/015/016 remain `planned` (not implemented here).
- RP-BUG-023 remains an open product decision.
- Some test sub-cases dropped for lack of Robolectric / `work-testing` deps
  (real-Bitmap thumbnail path, worker CancellationException, e2e queue recovery).
