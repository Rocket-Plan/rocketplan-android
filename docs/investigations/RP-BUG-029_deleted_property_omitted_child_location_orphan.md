---
bug_id: RP-BUG-029
aliases: []
title: "Android deletion sync marks omitted deleted properties but does not reconcile child locations/rooms, so a backend cascade-child omission can leave orphaned stale locations"
type: functional
classification: pre_existing_latent
source: "Parity review 2026-06-04 against iOS RP-BUG-268 / backend MONGOOSE-BUG-013 field incident; no Android field incident confirmed yet"
found_in: "1.0.00 (DeletedRecordsSyncService / LocalDataService deletion apply path)"
fixed_in: null
released_in: null
state: planned
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: docs/plans/plan_rp_bug_029_deleted_property_child_orphan_2026-06-04.md
related_review: null
related_test: null
last_updated: 2026-06-04
---

# RP-BUG-029 — Android can leave orphaned locations when `/sync/deleted` omits cascade-deleted children

## Summary

Android has the same defensive gap shape as iOS [[RP-BUG-268]], though no Android customer incident has
been confirmed yet. If the backend `/api/sync/deleted` response contains a deleted property id but omits
its cascade-deleted child location/room ids (backend [[MONGOOSE-BUG-013]]), Android marks the property
as deleted but does **not** cascade or reconcile the property's child locations/rooms. Those children can
remain live in Room and later be used by project/room/photo UI or sync paths.

Backend fix + remediation should clear the known live incident once the missing location id is explicitly
reported, because Android already applies explicit location deletions. This ticket is Android
**defense-in-depth** so the client does not remain vulnerable to future omitted cascade children.

## Relevant upstream incident

Backend/iOS incident from 2026-06-03:

- Project `17278`, duplicate property `13306` and child location `15306` were soft-deleted.
- Corrected backend/iOS analysis: `/sync/deleted` did report `properties: [13306]`, but omitted
  `locations: [15306]` because the location's parent property was also trashed.
- iOS marked the property deleted but kept the child location alive, producing intermittent 404s.
- Backend bug: MONGOOSE-BUG-013.
- iOS defensive counterpart: RP-BUG-268.

## Android code evidence

### Deletion apply does not cascade property -> locations/rooms

`DeletedRecordsSyncService.applyDeletedRecords()` cascades only project deletions:

```kotlin
val cachedPhotos = localDataService.cascadeDeleteProjectsByServerIds(response.projects)

localDataService.markPropertiesDeleted(response.properties)
localDataService.markRoomsDeleted(response.rooms)
localDataService.markLocationsDeleted(response.locations)
```

`applyProjectChildDeletions()` has the same independent apply shape for project-scoped deleted sync:

```kotlin
localDataService.markPropertiesDeleted(response.properties)
localDataService.markRoomsDeleted(response.rooms)
localDataService.markLocationsDeleted(response.locations)
```

`LocalDataService.markPropertiesDeleted()` only calls:

```kotlin
dao.markPropertiesDeletedByServerIds(serverIds)
```

and the DAO query only updates `offline_properties`:

```kotlin
UPDATE offline_properties SET isDeleted = 1 WHERE serverId IN (:serverIds) AND isDirty = 0
```

So if the backend sends `properties: [13306]` and `locations: []`, Android deletes the local property
row but leaves any matching `offline_locations` rows live.

### Android cannot safely add a naive property cascade today

`OfflineLocationEntity` stores `projectId` and `parentLocationId`, but **not** `propertyId` /
`propertyServerId`. A naive "property deleted -> mark all project locations deleted" would be unsafe on
projects with a live replacement property, because it could delete the live property's locations too.

This is different from iOS, where `OfflineLocation.propertyId` exists and the direct cascade can be more
surgical. Android needs either stronger location-property identity or an authoritative reconciliation
strategy.

### Partial self-heal exists but is not sufficient

`RoomSyncService.fetchRoomsForLocation(locationId)` handles a 404 by marking that location deleted:

```kotlin
if (error is HttpException && error.code() == 404) {
    localDataService.markLocationsDeleted(listOf(locationId))
}
```

However normal project essentials sync fetches rooms only for `nestedLocations` returned by the current
live property locations endpoint. An orphaned omitted child location is not necessarily re-probed, so the
404 self-heal is opportunistic rather than reliable.

## Impact

**P3 unless an Android field incident is confirmed; promote to P2 if this causes visible broken project,
room, or photo screens.**

No server data loss is expected. The risk is stale local Room rows that can:

- keep deleted locations/rooms visible or selectable,
- cause sync or UI paths to hit 404s for stale location/room ids,
- keep client state inconsistent until explicit backend remediation, full re-sync, or reinstall.

## Proposed fix shape

Do **not** blindly cascade property deletion to all project locations.

Safer options:

1. **Persist property identity on locations** — add `propertyServerId` (and/or local `propertyId`) to
   `OfflineLocationEntity` when saving property locations, then cascade only clean synced locations under
   the deleted property. Rooms can then be cascaded through their `locationId`.
2. **Authoritative live-location reconciliation** — when syncing `/api/properties/{id}/locations`, compare
   clean synced local locations for the same property against the authoritative returned live ids and mark
   absent rows deleted. This requires enough local identity to know which local rows belong to that
   property.
3. **404 recovery hardening** — if a cached location/room returns 404, mark the stale clean synced row
   deleted and re-resolve from the current live project/property graph. Treat network failures separately
   from hard 404s.

Deletion safety requirements:

- Cascade/reconcile only clean synced descendants automatically (`serverId != null && isDirty == false`).
- Do not silently delete local-only (`serverId == null`) or dirty rows; preserve, conflict-surface, or
  explicitly mark unrecoverable depending on the parent state.
- Tests must cover the exact payload shape `properties=[deletedProperty], locations=[]` with a child
  location and room cached locally.

## Suggested tests

- Deleted records payload with property id only marks the property deleted and does not leave a clean
  synced child location eligible for normal UI/sync resolution after the fix.
- Dirty/local child location under a deleted property is not silently discarded.
- Live replacement property/location in the same project is preserved.
- Hard 404 for a cached location deletes/re-resolves; transient network failure does not.

## Related

- iOS defensive counterpart: `RP-BUG-268`
- Backend root cause: `MONGOOSE-BUG-013`
