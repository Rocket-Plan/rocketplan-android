**Bug ID:** RP-FR-034
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-034_no_project_search_on_android_2026-07-20.md) · Plan (this doc)

# Plan — Add a client-side project search to the Android project list (iOS parity)

## Goal / acceptance

A user on the project list can tap a search affordance, type, and see the list filter instantly by
**address, project/alias name, or RP number (uid)** — case-insensitive substring, working **offline**
over the already-cached list. Clearing/closing search restores the full status-scoped list. Matches
iOS's visible behaviour (`ProjectListContentView` magnifier → `SearchInputField`;
`ProjectListPageView` substring filter on shortAddress/alias/uid).

## Approach — client-side filter (no API change)

Android is offline-first; the project list is already in Room and rendered via Flow. Filter the
in-memory list; do **not** add a server `search` param (that would bypass the offline cache and not
work offline).

### 1. ViewModel (`ui/projects/ProjectListViewModel.kt`)
- Add `searchQuery: StateFlow<String>` (default "") with a `setSearchQuery(String)` setter.
- Where the fragment currently reads the status-scoped list (`state.projectsByStatus[statusFilter]`),
  apply the query filter: keep a project when the query is blank, else when any of
  `address` / `name` (alias) / `uid` (RP number) `contains` the query, case-insensitive
  (`lowercase()`), trimmed. Confirm the exact field names on the project UI model / entity
  (`ProjectDto`/project entity → the list item model the adapter uses); match the same three iOS
  uses.
- Keep it reactive: fold the query into the existing state flow (e.g. `combine` the ui-state flow
  with `searchQuery`) so results update as the user types, per status tab.

### 2. Fragment + layout (`ui/projects/ProjectListFragment.kt`)
- Add a search affordance. Two acceptable options — pick one, note it in the PR:
  - **(a) Toolbar magnifier (closest iOS parity):** a search menu item / icon that toggles an inline
    `SearchView` / `TextInputEditText` above the list; hidden until tapped, with a clear/close
    control. Mirrors iOS's magnifier→field toggle.
  - **(b) Always-visible search box:** a `TextInputLayout` with a search icon pinned above the list.
    Simpler; less iOS-faithful but fine.
- Wire text changes → `viewModel.setSearchQuery(...)` (debounce is optional for a local filter but
  harmless). On close/clear, reset the query.
- The search must apply **within the current status tab** (My Projects / WIP / …) — filter the
  already-selected `statusFilter` list, consistent with how iOS filters each page.
- Handle empty-results state (show the existing empty message with a "no matches" variant if one
  exists, or reuse the empty state).

### 3. Strings
- Search hint (e.g. "Search projects"), and an optional "No matching projects" empty string. Reuse
  existing strings where present.

## Explicitly NOT in scope
- No API/DTO/sync/migration change (client-side only).
- Do not touch the unrelated `AddressSearchFragment` (Google autocomplete) or the `RoomDetailFragment`
  work-scope picker search.
- No change to status tabs / assigned-to-me filtering.

## Verification
- `compileDevStandardDebugKotlin` + `compileDevFlirDebugKotlin` clean.
- Unit test on the VM filter: given a project set, `setSearchQuery` narrows by address / name / uid,
  case-insensitively; blank query returns the full status-scoped list; filter composes with the
  active status tab.
- On-device: on a company with many projects, typing part of an address / name / RP number filters
  the list live; clearing restores it; works with the network off (offline).

## Open choices for the implementer
- Affordance style (a) vs (b) above — recommend (a) for iOS parity.
- Whether to also match on any secondary fields (iOS uses exactly address/alias/uid — stay with those
  unless product asks for more).
