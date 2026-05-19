---
bug_id: RP-BUG-022
aliases: []
title: Inefficient LRU Calculation in Photo Cleanup
type: performance
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
priority: P3
---

# Investigation: Inefficient LRU Calculation in Photo Cleanup

## Symptom

LRU cleanup may not properly track removed bytes if deletion fails.

## Discovery

- **Reported by:** Code review
- **Evidence:** `PhotoCacheManager.kt:216-228`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/local/cache/PhotoCacheManager.kt`

```kotlin
var totalBytes = entries.sumOf { it.totalBytes }
...
lru.forEach { entry ->
    if (totalBytes <= maxBytes) return@forEach
```

## Root Cause

Recalculates `totalBytes` by subtraction in loop but doesn't track removed bytes properly if victim deletion fails.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into cleanup effectiveness
- Deletion failures not tracked

### Proposed Instrumentation
- Calculate bytes to remove upfront
- Validate before deletion loop
- Track cleanup success/failure