---
bug_id: RP-BUG-009
aliases: []
title: Bitmap Memory Leak in Thumbnail Generation
type: memory
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
priority: P1
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