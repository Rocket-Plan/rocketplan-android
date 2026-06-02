---
bug_id: RP-BUG-009
aliases: []
title: Bitmap Memory Leak in Thumbnail Generation
type: memory
classification: pre_existing_latent
source: review
found_in: "1.0.00"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_009_019_022_photo_cache_2026-06-01.md
related_review: docs/reviews/code_review_bug_bundle_2_2026-06-01.md
related_test: null
priority: P1
last_updated: 2026-06-01
---

# Investigation: Bitmap Memory Leak in Thumbnail Generation

## Symptom

App may crash with `IllegalStateException` when recycling bitmaps, or OOM if `compress()` fails.

## Discovery

- **Reported by:** Code review
- **Evidence:** `PhotoCacheManager.kt:179-188`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt`

```kotlin
val thumbnailFile = File(originalFile.parentFile, "${originalFile.nameWithoutExtension}_thumb.jpg")
FileOutputStream(thumbnailFile).use { output ->
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
}
if (scaled != sampled) {
    scaled.recycle()
}
sampled.recycle()
```

## Root Cause

Two issues:
1. `scaled.recycle()` may be called when `scaled === sampled` (when no scaling needed), causing double-recycle crash
2. If `compress()` fails, bitmaps are leaked

## Observability

### Current Signals
- Local console logs: IllegalStateException from bitmap recycling
- Remote logs: None
- Sentry: Would capture OOM crashes
- Existing metrics/watchdogs: None

### Gaps
- No visibility into bitmap recycling failures
- OOM events not well characterized

### Proposed Instrumentation
- Add check `scaled !== sampled` before double recycling
- Use try-finally for proper cleanup
- Track bitmap creation/cleanup metrics