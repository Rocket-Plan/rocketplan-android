---
bug_id: RP-BUG-001
aliases: []
title: Example bug - Brief description of the issue
type: crash | hang | threading | memory | ui_bug | performance | functional
classification: pre_existing_latent | new_code_bug | regression | pre_existing_worsened
source: sentry | qa | review | customer | internal
found_in: "1.0.00"
fixed_in: null
released_in: null
state: investigating
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
last_updated: YYYY-MM-DD
---

# Investigation: [Short Title]

## Symptom

What the user sees/experiences. Be specific about the failure mode.

## Discovery

- **Reported by:** Who/how discovered it
- **Evidence:** Sentry crash ID, QA repro steps, customer report, etc.

## Affected Code

Path to relevant files/functions.

## Root Cause

(investigating state: hypothesized mechanism)
(open state: confirmed root cause)

## Fix Approach

(when state is `planned` or `fixed`)

## Observability

### Current Signals
- Local console logs:
- Remote logs:
- Sentry:
- Existing metrics/watchdogs:

### Gaps
- What failure is currently silent?
- What is ambiguous today?

### Proposed Instrumentation
- Local debug logs to add:
- Remote logs to add:
- Log category names:
- Key fields:
- Sampling / throttling:
- Build/env gating:

### Success Criteria
- How we'll know the fix worked in QA
- How we'll detect recurrence in the wild

---

## Related

- Plan: `docs/plans/RP-BUG-001_placeholder_YYYY-MM-DD.md`
- Review: `docs/reviews/code_review_rp_bug_001_YYYY-MM-DD.md`
- Related bugs: `RP-BUG-XXX` (same root cause)