# docs/ — RocketPlan Android Documentation Structure

## Folders

### `architecture/`
Normative and reference documentation maintained alongside the codebase. These are the docs you read before making changes. If a doc here conflicts with inline comments, the doc wins.

### `reviews/`
Code review snapshots tied to a specific branch, version, or commit range. Historical — do not update; open a new file for each review.

### `investigations/`
One-off bug investigations, incident write-ups, and Sentry issue analyses. Once an investigation is closed, the file stays here as a historical record. Each active investigation doc should have YAML front matter linking back to `BUG_TRACKER.md` — see the template in that file.

### `plans/`
Implementation plans, fix proposals, and test plans. Active during development, then kept as a record.

### `testing/`
Test plans, test case documentation, and QA verification records.

### `reference/`
External reference material (e.g. iOS architecture snapshots) kept for parity comparison. Not authoritative for Android — read `architecture/ARCHITECTURE.md` for the Android source of truth.

### Authoritative docs

- `architecture/ARCHITECTURE.md` — Android architecture (source of truth).
- `architecture/RP-CD_rules.md` — coding rules (`RP-CD-###`) referenced by `BUG_TRACKER.md` when filing `RP-FR-###` (architectural-violation) bugs.
- `BUG_TRACKER.md` — single source of truth for bug state.

- `TEST_PLAN_<feature>_<date>.md` — test plan for a feature or fix
- `TEST_CASE_<id>_<description>.md` — individual test case documentation
- `QA_SIGN_OFF_<ticket>_<date>.md` — QA verification and sign-off records

## File naming conventions

| Type | Convention | Example |
|------|-----------|---------|
| Architecture | `SCREAMING_SNAKE.md` or `kebab-case.md` | `OFFLINE_ARCHITECTURE.md` |
| Review | `CODE_REVIEW_<scope>.md` or `<description>_<date>.md` | `code_review_sync_2026-05-13.md` |
| Investigation | `<description>.md` or `RP-BUG-###_<description>.md` | `RP-BUG-023_todo_incomplete_features.md` |
| Plan | `<description>_<date>.md` | `sync_fix_plan_2026-05-13.md` |
| Test Plan | `TEST_PLAN_<feature>_<date>.md` | `TEST_PLAN_offline_sync_2026-05-13.md` |
| Test Case | `TEST_CASE_<id>_<description>.md` | `TEST_CASE_RP-BUG-023_verify_fix.md` |
| QA Sign-off | `QA_SIGN_OFF_<ticket>_<date>.md` | `QA_SIGN_OFF_1234_2026-05-13.md` |

## Adding new documents

Before creating a new doc, ask the user which folder it belongs in if there is any ambiguity. Do not default silently — confirm the destination first.

- **Architecture change** → `architecture/`. Update the file in place; no new file per change.
- **Code review** → `reviews/CODE_REVIEW_<scope>.md`
- **Bug investigation** → `investigations/RP-BUG-###_<description>.md`; add YAML front matter (see template in `BUG_TRACKER.md`)
- **Fix/test plan** → `plans/<description>_<date>.md`
- **Test plan or QA verification** → `testing/TEST_PLAN_<feature>_<date>.md` or `testing/QA_SIGN_OFF_<ticket>_<date>.md`
- **Ambiguous** (e.g. "write a doc for this issue") → ask the user whether it is an investigation or a plan before creating the file.

## Bug doc lifecycle

**Master tracker:** `docs/BUG_TRACKER.md` is the single source of truth for bug status. Every step below that changes bug state must also update the tracker.

1. **Register** → Add a row to `docs/BUG_TRACKER.md` with `state: investigating`. Assign a `RP-BUG-###` ID.
2. **Investigate** → Create in `docs/investigations/` with YAML front matter (template in `BUG_TRACKER.md`). Update tracker: `state: investigating`.
3. **Plan** → Once root cause is confirmed, create or update a doc in `docs/plans/`. Update tracker: `state: planned`, add `related_plan` in investigation front matter.
4. **Implement** → Fix the bug. Update tracker: `state: fixed`, set `fixed_in`, `release_state: unreleased`.
5. **Review** → Save code review in `docs/reviews/`. Update `related_review` in investigation front matter.
6. **Test** → Add test doc in `docs/testing/`. Update `related_test` in investigation front matter.
7. **Document** → Update `docs/architecture/` and/or release notes if the change affects long-term behavior.
8. **Release** → When the version ships, update tracker: `released_in: <version>`, `release_state: released`.
9. Keep investigation docs as historical records. Cross-link investigation ↔ plan ↔ review using the front matter fields.

### Required backlinks in bug-related docs

**Investigation docs** use YAML front matter (see template in `BUG_TRACKER.md`) — the front matter fields satisfy the bug ID, tracker link, and related-doc requirements. No separate visible block is needed.

**Plans, reviews, and test docs** do not have YAML front matter and must include a visible block at the top:

```
**Bug ID:** RP-BUG-###
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/...) · [Plan](../plans/...) · [Review](../reviews/...)
```

For bug fix plans, include a short observability subsection if the fix changes logging or failure detection.