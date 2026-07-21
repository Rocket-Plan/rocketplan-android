---
bug_id: RP-FR-034
aliases: []
title: Android has no project search on the project list — iOS surfaces a magnifier→search field that filters projects by address/name/RP-number; Android only has status tabs
type: feature
classification: feature_gap
source: Android↔iOS parity review
evidence: verified against iOS source
found_in: "feat/RP-FR-019-serialized-equipment (35-dev) — pre-existing gap, surfaced during parity review"
found_at: "2026-07-20 20:59:30 PDT"
fixed_in: null
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_fr_034_android_project_search_2026-07-20.md
related_review: null
related_test: null
priority: P2
last_updated: 2026-07-20
---

# RP-FR-034 — Add project search to the Android project list (iOS parity)

> **Parity gap** found during an Android↔iOS review (2026-07-20). iOS ships a project search on the
> project list; Android has none. Not equipment-related — a general project-list capability.

## Symptom

On Android, the project list (`ui/projects/ProjectListFragment`) offers only **status tabs**
(`statusFilter` — My Projects / WIP / etc.). There is **no way to search/filter projects by text**.
A user with many projects must scroll. iOS lets you tap a magnifier and type to filter instantly.

## Resolution scope note (2026-07-20)

Implemented with an **intentional deviation from iOS**: the Android search is **global across every
category** — a non-blank query filters the union of My Projects + all status lists (deduped by
projectId) from whatever tab you're on. iOS, by contrast, scopes search to the active status tab
(`ProjectListPageViewModel.fetchCompanyProjectsFromAPI` sends `status: projectStatus` together with
`search`, and the offline/client-side filters are status-scoped; only the nil-status "My Projects"
tab spans statuses within assigned). Product chose the broader Android behaviour. Search box
placement still mirrors iOS (under the status tabs, above the list).

## iOS reference (verified in source, `ios.rocketplantech.com` branch `dev`)

The search is genuinely surfaced (not dead VM code):

- **UI** — `RocketPlan/Views/Project/List/ProjectListContentView.swift:93` renders
  `Button(action: viewModel.searchAction)` (a magnifier that turns `.mainPurple` when active,
  `:98`); when `viewModel.isSearching` it shows a `SearchInputField` bound to `viewModel.searchText`
  with a cancel action (`:105-115`). Wired across every project tab (`searchText:` bindings
  `:131-194`).
- **Filtering** — primarily **client-side** over loaded items:
  `RocketPlan/Views/Project/List/ProjectListPageView.swift:39-46` filters by `contains` on
  `shortAddress`, `alias` (name), and `uid` (RP number). There is *also* a server path
  (`ProjectListPageViewModel.searchChanged` → API `search:` param, `:99`/`:242-256`), so it's a
  hybrid, but the visible behaviour is client-side substring filtering.
- **VM state** — `ProjectListViewModel`: `isSearching`, `searchText`, `searchAction()` (toggle +
  clear + dismiss keyboard).

## Current Android state (verified)

- `ui/projects/ProjectListFragment.kt` — only `statusFilter: ProjectStatus?`; no search field, no
  query state. (Other "search" in the `ui/projects` package is unrelated: Google address
  autocomplete `AddressSearchFragment`, and the work-scope picker in `RoomDetailFragment`.)
- API (`data/api/OfflineSyncApi.kt`): `getCompanyProjects` takes only `page` /
  `filter[updated_date]` / `filter[assigned_to_me]`; `getUserProjects` only `page` /
  `filter[updated_date]`. **No `search` param.**
- Android is offline-first: the full project list is already cached in Room and rendered via Flow.

## Recommended approach (fits offline-first + matches iOS's primary mechanism)

**Client-side filter over the cached project list** — not a server search. This mirrors iOS's visible
behaviour (`ProjectListPageView` substring filter), needs **no API change**, and — unlike iOS's
server path — **works offline**. Match on the same fields iOS uses: **address, project/alias name,
and RP number (uid)**, case-insensitive `contains`.

## Impact

- Usability gap for users with many projects; a visible feature-parity difference vs iOS. P2 (not a
  correctness/data issue; purely additive UX).

## Observability

- None required beyond existing screen logging. (iOS logs `isSearching`/search-text transitions; a
  light `uiTap`-equivalent is optional.)

## Notes

- Scope is the project **list** only. Do not touch the unrelated address-autocomplete or work-scope
  search.
- Purely additive; no data-model/sync/migration change.
