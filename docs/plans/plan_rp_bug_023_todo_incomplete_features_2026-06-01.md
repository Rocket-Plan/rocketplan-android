**Bug ID(s):** RP-BUG-023
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-023_todo_incomplete_features.md) · [Plan](./plan_rp_bug_023_todo_incomplete_features_2026-06-01.md) · Review: n/a (assessment doc)

# Assessment (NOT a fix plan): [RP-BUG-023] TODO comments for incomplete features in PeopleFragment

**Bug ID(s):** RP-BUG-023
**Author:** Claude (Opus 4.8)
**Date:** 2026-06-01
**State:** needs-product-decision (investigation left `open` — not converted to a fix plan)

---

## Why this is not a bug fix

The two TODOs in `PeopleFragment.kt` are not defects in existing behavior; they mark **unbuilt features**:

- Line 29 (`onEditClicked`): no edit-person flow exists. The handler shows a `Toast` ("Edit ${person.name}") as a placeholder.
- Line 85 (`confirmDeletePerson` positive button): no delete path exists. It shows a success Toast (`R.string.person_deleted`) and dismisses the dialog **without calling any API**.

Inspection of the surrounding layers confirms there is nothing to "fix back into place":

- `PeopleViewModel.kt` exposes only `loadUsers()` / `refresh()` — no `deletePerson`/`editPerson`/`updateUser` methods.
- There is no people/user write repository or endpoint for edit/delete; `data/api/OfflineSyncApi.kt` and `CrewPushHandler.kt` are the only person-adjacent write paths and neither implements person delete/edit from this screen.

Implementing these requires product and backend decisions (permissions model, soft vs hard delete, offline-queue semantics, server endpoints, confirmation/undo UX). That is feature scope, not a regression or latent crash.

## The one genuine concern worth flagging

The **delete** placeholder is actively misleading: it shows `R.string.person_deleted` ("deleted") even though nothing happened. A user reasonably believes the person was removed. This is a UX-correctness issue that *could* be addressed cheaply without the full feature.

### Recommended interim mitigation (small, optional, no API needed)

Until the real feature lands, make the placeholders honest instead of implying success:

```kotlin
// onEditClicked
Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()

// confirmDeletePerson positive button — do NOT claim success
Toast.makeText(requireContext(), R.string.feature_coming_soon, Toast.LENGTH_SHORT).show()
dialog.dismiss()
```

Or, preferably, hide/disable the edit and delete affordances entirely until the feature exists, so users are not offered actions that do nothing.

## Recommendation

1. Convert the two TODOs into a tracked **product/feature** ticket (Edit person, Delete person via API) with backend dependencies, rather than a bug fix.
2. Optionally land the interim mitigation above to remove the false "deleted" confirmation. If desired, that mitigation can be promoted to its own small fix plan; it is the only part of RP-BUG-023 that is actionable today without product/API decisions.

## Decision Needed

Product/design needs to choose one of three paths before engineering should treat this as planned work:

1. Hide or disable the edit/delete affordances until the feature exists.
2. Keep the affordances but replace the misleading success copy with an honest “coming soon / not yet supported” placeholder.
3. Open a full feature implementation ticket covering backend/API support, permissions, offline semantics, and UX.

## Tracker / Lifecycle Status

- `BUG_TRACKER.md` should remain `open`, not `planned`, unless the interim mitigation is split into its own small fix plan.
- This doc is intentionally an assessment/decision artifact, not a fix-plan template instance.

## Dependencies

- Product decision on whether the interim behavior should be disable, placeholder, or full feature work.
- Backend/API support for edit/delete operations if the full feature path is chosen.
- Permissions and offline-queue semantics for destructive actions.

## Status

Investigation `state` left as `open` (not `planned`) because no engineering fix plan applies — this is feature/product work. No source changes proposed here beyond the optional interim mitigation, which requires a product call on whether to disable vs. honestly label the affordances.
