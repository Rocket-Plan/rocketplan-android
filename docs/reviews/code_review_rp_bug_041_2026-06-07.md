**Bug ID:** RP-BUG-041
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-041_no_per_item_download_sync_cloud_indicator.md) · [Plan](../plans/plan_rp_bug_041_download_sync_indicator_2026-06-07.md)

# Code Review: RP-BUG-041 per-item cloud/download indicator

**Bug ID(s):** RP-BUG-041
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 17:21:33 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

`RP-BUG-041` appears correctly framed as a real UX gap, but there is **no implementation to review yet**. This review covers the investigation and fix plan only.

The diagnosis is reasonable: Android lacks a generalized per-item indicator for
- **pending local changes** (`cloud-up`), and
- **not downloaded locally yet** (`cloud-down`)

while iOS exposes both with explicit precedence.

## Findings

### Must Fix

1. **Tighten the plan's scope before implementation.**
   The investigation correctly distinguishes two meanings of "not downloaded":
   - binary/file not cached locally
   - whole child collection not yet pulled

   The plan chooses the first one, which is good, but the bug title/investigation still speak broadly about "items" across project list, rooms, photos, documents, etc. Before coding, the implementation scope should be stated more concretely as:
   - Phase 1: photos/documents with real local-cache evidence
   - later: collection-level download completeness once a trustworthy signal exists

2. **Do not ship a project-list cloud-down indicator until its data source is defined.**
   The current plan says "project list + photo gallery" first, but the same plan also admits that project-level/collection-level not-downloaded state is not cleanly modeled yet. Without a solid source of truth, that indicator risks being noisy or misleading.

### Should Fix

1. **Add an explicit data-source table to the plan.**
   For each initial surface, define exactly what drives the icon:
   - Photo row: cached original/thumbnail presence
   - Document/PDF row: downloaded file presence
   - Note/support row: `isDirty` / `syncStatus`
   - Project row: defer unless a concrete aggregate signal exists

2. **Separate upload-state and download-state derivation in the design.**
   A single `DownloadSyncState` enum is workable, but the underlying signals are different enough that the plan should explicitly model them before collapsing to UI state.

3. **Call out performance constraints more concretely.**
   The plan notes avoiding per-row IO on main, which is correct. It should also specify that adapter binding must consume precomputed state rather than perform cache existence checks inline.

### Consider

1. **A narrower first rollout may be safer than the current plan.**
   Starting with photo/document rows only may yield a higher-confidence implementation than including project list in phase 1.

2. **Existing note/support indicators may need visual normalization.**
   If a shared cloud metaphor lands, those screens may eventually want to converge on the same indicator language instead of keeping separate ad-hoc widgets.

### Verified Safe

1. **The investigation's parity claim is plausible.**
   The referenced Android code supports the claim that there is no generalized per-row cloud indicator today, and the iOS references establish the intended behavior.

2. **Classification as `ui_bug` is reasonable.**
   This is a real user-facing affordance gap, not just architectural drift.

3. **The plan correctly identifies the key ambiguity.**
   The main implementation risk is not the icon itself; it's defining a trustworthy source of truth for "not downloaded".

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | Codex | 2026-06-07 |
