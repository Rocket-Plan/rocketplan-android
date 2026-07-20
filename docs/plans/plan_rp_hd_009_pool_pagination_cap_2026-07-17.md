**Bug ID:** RP-HD-009
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-HD-009_pull_company_pool_unbounded_loop_2026-07-17.md) · Plan (this doc)

# Plan — Bound pullCompanyPool pagination

## Fix
`data/repository/sync/EquipmentAssetPullService.kt` — `pullCompanyPool(companyId)` (~lines 82-110).
Advance by the **requested** page and add a hard cap:

```kotlin
var page = 1
val maxPages = 1000  // backstop; 100/page → 100k assets
while (page <= maxPages) {
    val resp = api.getCompanyEquipmentAssets(companyId = companyId, perPage = 100, page = page)
    // …existing empty / malformed-meta / current>=last handling, all returning as today…
    page += 1   // advance by REQUESTED page; ignore server-echoed currentPage for iteration
}
// Fell through the cap → treat as incomplete so no reconciliation-deletion runs.
remoteLogger?.log(LogLevel.WARN, "API", "Equipment pool pagination exceeded cap",
    mapOf("companyId" to companyId.toString(), "pages" to maxPages.toString()))
return PoolSnapshot(seen, complete = false)
```

Keep the existing `meta.currentPage/lastPage/total` logic for the `complete` flag; only the
iteration variable and the cap change.

## Verification
- Both compile gates clean.
- Regression test (add): a fake API that always returns `currentPage=1, lastPage=5` with non-empty
  data must terminate at `maxPages` and return `complete=false` (no infinite loop, no deletion).
- Normal multi-page pull still completes and reconciles as before.

## Observability
Reuses the RP-HD-008 pull `remoteLogger`; WARN when the cap is hit.
