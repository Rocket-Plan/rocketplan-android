---
bug_id: RP-FR-001
aliases: []
title: ByteArray properties in Room @Entity data classes (RP-CD-008)
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: closed
release_state: n/a
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
parent: RP-HD-001
violates: RP-CD-008
priority: P2
last_updated: 2026-06-02
---

# RP-FR-001: `ByteArray` in Room `@Entity` data classes

> Filed by the `RP-HD-001` RP-CD audit sweep. Violates **`RP-CD-008` — No `ByteArray` in `@Entity` data classes**.

## Rule violated

`RP-CD-008`: Room `@Entity` data classes must not declare `ByteArray` properties. Kotlin's
generated `equals`/`hashCode` for a `data class` compare arrays by reference, so Room change
detection misfires — duplicate rows, lost updates, and broken `DISTINCT` queries.

## Affected code

`app/src/main/java/com/example/rocketplan_android/data/local/entity/OfflineEntities.kt`

| Entity | Field | Line |
|--------|-------|------|
| `OfflineSyncQueueEntity` | `payload: ByteArray` | 744 |
| `OfflineConflictResolutionEntity` | `localVersion: ByteArray` | 816 |
| `OfflineConflictResolutionEntity` | `remoteVersion: ByteArray` | 817 |

## Resolution — closed 2026-06-02 (already mitigated, no fix warranted)

On verification, **both entities already implement the second compliant remedy listed in
`RP-CD-008`** — explicit `equals`/`hashCode` overrides using `contentEquals`/`contentHashCode`:

- `OfflineSyncQueueEntity` — `equals`/`hashCode` overridden at `OfflineEntities.kt:757-798`
  (`payload.contentEquals(...)` / `payload.contentHashCode()`).
- `OfflineConflictResolutionEntity` — `equals`/`hashCode` overridden at `OfflineEntities.kt:831-872`
  (`localVersion`/`remoteVersion` use `contentEquals`/`contentHashCode`).

`RP-CD-008`'s entire stated failure mode is reference-equality breaking Room change detection. With
correct structural `equals`/`hashCode` in place, that hazard **cannot occur** — duplicates, lost
updates, and broken `DISTINCT` are all prevented. The rule's "How to apply" explicitly accepts
"override `equals`/`hashCode` explicitly" as an alternative to moving the bytes out.

A `String`/base64 migration was considered and **rejected**: `payload` is consumed as raw bytes in
several hot paths (`String(op.payload, UTF_8)` in `IdRemapService`, `PropertyPushHandler`;
`extractLockUpdatedAt(existing.payload)` in `SyncQueueProcessor`), so converting the column would be
a wide refactor plus a Room migration **for zero functional benefit**, since the defect is already
neutralized. The migration risk is not justified.

**Closed as not-a-violation.** The original filing relied on a Feb-2026 memory note that predated the
`equals`/`hashCode` overrides being added.

### Follow-up (optional, no code change)
Consider sharpening `RP-CD-008` to state that **entities with correct explicit `contentEquals`/
`contentHashCode` overrides are compliant**, so this doesn't get re-flagged by the next audit sweep.
