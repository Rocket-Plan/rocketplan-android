**Bug ID(s):** RP-BUG-021
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-021_magic_numbers.md) · [Plan](./plan_rp_bug_021_magic_numbers_2026-06-01.md) · Review: TBD

# Fix Plan: [RP-BUG-021] Hardcoded retry-configuration magic numbers

**Bug ID(s):** RP-BUG-021
**Author:** Claude (Opus 4.8)
**Date:** 2026-06-01
**State:** draft

---

## Summary

`ImageProcessorQueueManager` hardcodes its retry policy as private companion-object constants (lines 78-84): `MAX_RETRY_ATTEMPTS = 13`, `INITIAL_RETRY_TIMEOUT_SECONDS = 10`, `MAX_RETRY_TIMEOUT_SECONDS = 1800`. These are already named constants with two clarifying comments ("Match iOS cap: exponential backoff up to 30 minutes" and the stuck-threshold note), so this is a P3 maintainability concern, not a correctness bug. The values are referenced across many call sites (778, 1066, 1100, 1164, 1274, 1504, 1668, 1690).

This is **partially actionable now** and **partially product/server-dependent**. The investigation's "fetch limits from server config" suggestion is real work that depends on a backend contract that does not exist yet. The pragmatic, self-contained fix is to (1) consolidate the constants into a single `RetryConfig` value object with documented rationale, and (2) make them overridable via the existing `ImageProcessingConfigurationRepository` if/when the server exposes them — without blocking on the server. We scope this plan to the local consolidation and document the server-config follow-up explicitly rather than implementing speculative API plumbing.

## Why this remains a bug fix

The concrete defect is policy drift risk in production retry handling: the retry contract is currently spread across many call sites, which makes it easy for one path or future edit to diverge from the intended retry semantics without any single authoritative source. This plan fixes that by centralizing the live retry policy in one documented object, even though it intentionally preserves runtime values in the first pass.

## Affected Code

| File | Change |
|------|--------|
| `app/src/main/java/com/example/rocketplan_android/data/queue/ImageProcessorQueueManager.kt` | Replace the three loose constants with a single documented `RetryConfig` object (or keep constants but add a `data class RetryConfig` defaulting to them); reference it everywhere the constants are used. No behavior change. |

## Implementation Notes

### Step 1: Introduce a documented config value

Within `ImageProcessorQueueManager` (replacing/augmenting the companion object at lines 76-85):

```kotlin
companion object {
    private const val TAG = "ImgProcessorQueueMgr"
    private const val ONE_OFF_RETRY_WORK_NAME = "image_processor_retry_one_off"
    // If server says "pending" for longer than this, consider it stuck and retry (5 minutes)
    private const val PENDING_STUCK_THRESHOLD_MS = 5 * 60 * 1000L

    /** Default retry policy. Values mirror the iOS client so behavior is consistent across platforms. */
    val DEFAULT_RETRY_CONFIG = RetryConfig(
        // ~13 attempts gives roughly a full day of exponential backoff before giving up.
        maxRetryAttempts = 13,
        // First retry waits 10s, then doubles each attempt.
        initialRetryTimeoutSeconds = 10,
        // Caps backoff at 30 minutes to match the iOS client.
        maxRetryTimeoutSeconds = 1800,
    )
}

/** Tunable retry policy. Currently constant; see RP-BUG-021 follow-up for server-driven values. */
data class RetryConfig(
    val maxRetryAttempts: Int,
    val initialRetryTimeoutSeconds: Int,
    val maxRetryTimeoutSeconds: Int,
)
```

Hold a field `private val retryConfig: RetryConfig = DEFAULT_RETRY_CONFIG` (or accept it as a constructor parameter defaulting to `DEFAULT_RETRY_CONFIG` so tests/future server config can inject overrides).

### Step 2: Replace constant references

Update every reference. For example, the backoff computation (lines 1668-1669):

```kotlin
val timeout = INITIAL_RETRY_TIMEOUT_SECONDS * (1 shl retryCount)
return timeout.coerceAtMost(MAX_RETRY_TIMEOUT_SECONDS)
```

becomes:

```kotlin
val timeout = retryConfig.initialRetryTimeoutSeconds * (1 shl retryCount)
return timeout.coerceAtMost(retryConfig.maxRetryTimeoutSeconds)
```

and each `MAX_RETRY_ATTEMPTS` use (778, 1066, 1100, 1164, 1274, 1504) becomes `retryConfig.maxRetryAttempts`. Line 1690 (`INITIAL_RETRY_TIMEOUT_SECONDS.toLong()`) becomes `retryConfig.initialRetryTimeoutSeconds.toLong()`. This is a mechanical, behavior-preserving substitution — defaults are identical to the current constants.

### Step 3: Document the deferred server-config follow-up

Add a comment on `RetryConfig` noting that server-driven values are a future enhancement gated on a backend contract (see Dependencies). Do not implement speculative API plumbing in this change.

## Test Plan

- [ ] Unit test: `computeRetryTimeout` (the function at ~1668) returns identical values before and after the refactor for retryCount 0..13 (e.g. 10, 20, 40, ... capped at 1800).
- [ ] Unit test: an assembly with `failsCount == maxRetryAttempts` is still skipped/permanently-failed exactly as before.
- [ ] Build: `./gradlew compileDevStandardDebugKotlin` (run in background per project convention).

## Rollback Plan

Revert the single-file change; constants return to their inline form. No persisted-data or API changes.

## Dependencies

- Requires: none for the scoped local consolidation in this plan.
- Blocking: none for the local fix. Server-driven configuration is a separate follow-up that depends on a backend contract and is explicitly out of scope here.

## Changelog Entry

```markdown
### Changed
- [RP-BUG-021] Consolidated image-processor retry limits into a single documented config object (no behavior change), paving the way for future server-driven tuning.
```
