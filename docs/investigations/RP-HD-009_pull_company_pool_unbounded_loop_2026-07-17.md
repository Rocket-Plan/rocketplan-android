---
bug_id: RP-HD-009
aliases: []
title: pullCompanyPool trusts the server-echoed currentPage and has no iteration cap — a misbehaving server can loop forever
type: functional
classification: new_code_bug
source: review
found_in: "feat/RP-FR-019-serialized-equipment (35-dev)"
found_at: "2026-07-17 21:18:13 PDT"
fixed_in: null
released_in: null
state: planned
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_hd_009_pool_pagination_cap_2026-07-17.md
related_review: null
related_test: null
last_updated: 2026-07-17
---

# RP-HD-009 — Bound pullCompanyPool pagination (preventive)

**Hardening:** the primary defense (real servers echo the requested page) already works; this adds a
second line so a misbehaving server can't hang the pull.

## Rationale
`EquipmentAssetPullService.pullCompanyPool` (~lines 82-110) uses `while (true)` and advances via the
response's `meta.currentPage` (`page = current + 1`), not the requested page, with no upper bound. A
server that returns a fixed `currentPage < lastPage` regardless of the `page` query would loop
forever refetching the same page (upserting the same rows), never completing the pull. Requires a
broken server — no confirmed live failure — hence preventive/P3.

## Proposal
Advance the iteration by the **requested** page and add a hard `maxPages` backstop; on hitting the
cap, treat the pull as incomplete (so no reconciliation-deletion runs) and emit a remote WARN. Keep
the existing `meta`-based completeness logic for the `complete` flag.

## Observability
Reuses the RP-HD-008 `remoteLogger` on the pull service; adds a WARN when the cap is exceeded.
