---
bug_id: RP-HD-003
aliases: []
title: Document ordering of legacy-token clear vs encrypted save in SecureStorage
type: hardening
classification: new_code_bug
source: review
evidence: preventive
found_in: "1.0.00"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_hd_002_003_secure_storage_2026-05-18.md
related_review: docs/reviews/code_review_RP-BUG-001_006_2026-05-18.md
related_test: null
priority: P3
last_updated: 2026-06-02
---

# Hardening: document the clear-vs-save ordering invariant in `migrateLegacyAuthToken`

## Context

Surfaced during the code review for [RP-BUG-001 / RP-BUG-006](../reviews/code_review_RP-BUG-001_006_2026-05-18.md) (finding **F2**). `migrateLegacyAuthToken()` in `SecureStorage.kt` performs three operations in order:

```kotlin
saveAuthTokenInternal(legacyToken)   // (1) write encrypted prefs
clearLegacyToken()                   // (2) remove from DataStore
authTokenState.compareAndSet(null, legacyToken)  // (3) publish in-memory
```

The ordering matters for crash safety, but the rationale is not in the code. A future refactor that "tidies up" by swapping (1) and (2) or by short-circuiting on a partial success would re-introduce token-loss windows.

## Invariant the order enforces

- **If (1) fails:** encrypted prefs were never written, DataStore still has the legacy token. Next cold start re-attempts the migration. **Safe.**
- **If (2) fails after (1) succeeds:** encrypted prefs has the token, DataStore still has the (now-stale) copy. Next cold start re-migrates the same token onto itself; `compareAndSet` is a no-op against any equal value already in `authTokenState`. **Safe but redundant.**
- **If we reversed the order** (clear first, save second) and (2) failed: DataStore is empty, encrypted prefs never written → user is signed out. **Unsafe.**
- **If (3) is moved before (1):** `authTokenState` claims the token before encrypted prefs has it. A consumer that immediately reads via the flow would race a not-yet-persisted value. **Subtle but unsafe.**

So the rule is: **encrypted save first, then clear legacy, then publish state.** Never reorder.

## Why this is `RP-HD-###` and not `RP-FR-###`

There is no `RP-CD-###` rule today that names "operation ordering with crash-safety implications must be commented." This ticket adds a comment and proposes that we either (a) accept the comment as documentation-only or (b) promote it to a rule (`RP-CD-014`?) if we find a second instance of a similar invariant.

## Proposed change

Add a block comment above the three calls. No behavior change:

```kotlin
// Order matters for crash safety:
//   1. saveAuthTokenInternal — encrypted prefs holds the token first.
//   2. clearLegacyToken — DataStore copy is removed only after the
//      authoritative store accepted it. If we cleared first and the save
//      crashed, the token would be lost.
//   3. compareAndSet — publish to in-memory state last so flow consumers
//      can't observe a token that isn't yet persisted.
saveAuthTokenInternal(legacyToken)
clearLegacyToken()
authTokenState.compareAndSet(null, legacyToken)
```

## Optional follow-up

If a second case of "ordering matters for crash safety" lands in the codebase (e.g. push handlers writing server response then clearing dirty flag), promote this to `RP-CD-014 — Persist authoritative state before clearing source state` in `docs/architecture/RP-CD_rules.md`. Until then, the inline comment is enough.

## Test plan

- No new tests required (documentation change).
- Existing `SecureStorageTest` cases continue to pass.

## Observability

### Current Signals
- None — pure documentation.

### Gaps
- None new. RP-HD-002 covers the throw-handling gap that would let us *detect* if step (1) ever fails in production.

### Proposed Instrumentation
- None.

### Success Criteria
- Review: a reader of `SecureStorage.kt` can answer "why this order?" without consulting git history or this doc.
