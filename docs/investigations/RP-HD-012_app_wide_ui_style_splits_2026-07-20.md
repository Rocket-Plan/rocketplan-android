---
bug_id: RP-HD-012
aliases: []
title: App-wide UI styling splits — card stroke/elevation, chevron sizing, and section-header style each have two+ coexisting conventions; unify into shared styles/tokens
type: functional
classification: pre_existing_latent
source: RP-FR-019 serialized-equipment consistency audit (2026-07-20)
evidence: reproduced
found_in: "feat/RP-FR-019-serialized-equipment (35-dev) — pre-existing, app-wide"
found_at: "2026-07-20 22:26:55 PDT"
fixed_in: null
released_in: null
state: open
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
priority: P3
last_updated: 2026-07-20
---

# RP-HD-012 — Unify app-wide UI styling splits (card / chevron / section-header)

> **Surfaced by** the RP-FR-019 serialized-equipment consistency audit. The serialized screens
> themselves were brought in line (button radius, empty-state sizes, title token — committed in
> `9b541f7`), and their headers correctly use the shared ActionBar like the 67/72-screen norm. What
> remains are **pre-existing, app-wide** styling splits — NOT serialized-equipment regressions. This
> ticket captures them for a future design-consistency cleanup; **do not** scope it to the serialized
> feature.

## The splits (each has 2+ coexisting conventions in the codebase)

1. **List-card stroke / elevation.** Two styles coexist:
   - `strokeColor=@color/light_purple`, `cardElevation=0dp`, `strokeWidth=1dp`
     (e.g. `item_serialized_equipment.xml`, `item_placement_history.xml`).
   - `strokeColor=@color/light_border`, `cardElevation=4dp`, `cardUseCompatPadding=true`
     (e.g. `item_equipment_room.xml`, `fragment_rocket_dry.xml` cards).
   Fix: pick one, extract a shared `@style/Widget.RocketPlan.Card`, apply everywhere.

2. **Chevron (nav "›") sizing/tint.** Unenforced: some use `wrap_content`
   (`item_serialized_equipment.xml`, `item_conflict.xml`), and tint varies
   (`light_text_rp` vs `placeholder` vs `main_purple`, e.g. `fragment_project_type_selection.xml`).
   There is a `@dimen/icon_size_chevron` (16dp) token that is not consistently used.
   Fix: standardize chevron rows to `@dimen/icon_size_chevron` + one tint token.

3. **Section-header text style.** Two patterns:
   - Purple, `text_size_body_small` (12sp), bold — e.g. serialized screens, `fragment_login.xml`,
     `fragment_projects.xml`, `fragment_map.xml`, `item_room_scope_line_item.xml`.
   - Grey (`dark_text_rp`/`light_text_rp`), `text_size_title` (16sp) — e.g.
     `fragment_total_equipment.xml`, `fragment_rocket_dry.xml`.
   Fix: decide the canonical section-header style and extract a shared text-appearance/style.

## Scope / acceptance
- App-wide design-token/style unification; extract shared `@style`s (card, chevron row, section
  header) and migrate call sites. Purely visual; no behaviour/data change.
- Low priority (P3) — nothing is broken; these are cosmetic divergences that predate RP-FR-019.

## Related
- Header/toolbar note: the app uses a shared purple ActionBar for 67/72 screens; only 5 layouts carry
  their own `MaterialToolbar` (`total_equipment`, `conflict_list`, `flir_test`, `oauth_webview`,
  `scope_picker`). `TotalEquipmentFragment` is NOT in MainActivity's hide-app-bar list, so it likely
  renders the shared ActionBar **and** its own toolbar (a probable double-bar) — worth checking as
  part of any header cleanup, but out of scope here.
