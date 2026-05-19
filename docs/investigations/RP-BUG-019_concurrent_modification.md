---
bug_id: RP-BUG-019
aliases: []
title: Potential ConcurrentModificationException in PhotoCacheManager
type: crash
classification: pre_existing_latent
source: review
found_in: "1.0.00+000"
fixed_in: null
released_in: null
state: open
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P2
---

# Investigation: Potential ConcurrentModificationException in PhotoCacheManager

## Symptom

App may crash with `ConcurrentModificationException` when caching photos concurrently.

## Discovery

- **Reported by:** Code review
- **Evidence:** `PhotoCacheManager.kt:41-56`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt`

```kotlin
suspend fun cachePhotos(photos: List<OfflinePhotoEntity>) {
    photos.forEach { photo ->
        runCatching { cachePhoto(photo) }.onFailure { error ->
```

## Root Cause

Sequential `forEach` with suspend function `cachePhoto` - if called concurrently from multiple contexts, the underlying data structures may not be thread-safe.

## Observability

### Current Signals
- Local console logs: ConcurrentModificationException
- Remote logs: None
- Sentry: Would capture crash
- Existing metrics/watchdogs: None

### Gaps
- No visibility into concurrent access patterns

### Proposed Instrumentation
- Add thread-safety to underlying data structures
- Track concurrent access attempts