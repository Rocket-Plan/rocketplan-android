---
bug_id: RP-BUG-021
aliases: []
title: Hardcoded Magic Numbers for Retry Configuration
type: functional
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

# Investigation: Hardcoded Magic Numbers for Retry Configuration

## Symptom

Backend limit changes require code updates; no configuration flexibility.

## Discovery

- **Reported by:** Code review
- **Evidence:** `ImageProcessorQueueManager.kt:78-84`

## Affected Code

`app/src/main/java/com/example/rocketplan_android/data/queue/ImageProcessorQueueManager.kt`

```kotlin
private const val MAX_RETRY_ATTEMPTS = 13
private const val INITIAL_RETRY_TIMEOUT_SECONDS = 10
private const val MAX_RETRY_TIMEOUT_SECONDS = 1800
```

## Root Cause

Magic numbers not derived from configuration. If backend changes limits, these must be manually updated.

## Observability

### Current Signals
- Local console logs: None
- Remote logs: None
- Sentry: Not captured
- Existing metrics/watchdogs: None

### Gaps
- No visibility into retry configuration
- No flexibility for backend changes

### Proposed Instrumentation
- Consider fetching limits from server config
- Document rationale for values