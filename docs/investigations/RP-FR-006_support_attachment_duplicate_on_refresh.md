---
bug_id: RP-FR-006
aliases: []
title: Support message attachments duplicate on refresh — attachment pull does no serverId reconciliation, so re-pull inserts a new row per server attachment id
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: "1.0.00"
released_in: null
state: fixed
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: app/src/test/java/com/example/rocketplan_android/data/repository/sync/SupportSyncServiceTest.kt
violates: RP-CD-014
priority: P3
last_updated: 2026-06-07
---

# RP-FR-006: Support attachments duplicate on refresh

> The attachment-level sibling of [[RP-BUG-036]], surfaced while fixing [[RP-FR-005]] (attachment FK
> keying). Same identity family (server id vs local PK) and same duplicate-on-refresh failure mode as
> RP-BUG-036, one level down. Filed `RP-FR` (not `RP-BUG`) because it is **currently latent** — see
> below.

## The mismatch

`OfflineSupportMessageAttachmentEntity` has an **autoGenerate** primary key and a **non-unique**
`serverId` index:

```kotlin
@Entity(
    tableName = "offline_support_message_attachments",
    indices = [Index(value = ["messageId"]), Index(value = ["serverId"])]  // serverId NOT unique
)
data class OfflineSupportMessageAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val attachmentId: Long = 0,
    val serverId: Long? = null,
    ...
)
```

`SupportSyncService.syncMessages` builds attachment rows fresh on every pull (`attachmentId` defaults
to `0`) and saves via `upsertSupportMessageAttachments` (`@Upsert`, which matches on the **primary
key** only). Because the incoming PK is `0`, Room inserts a **new** row each pull; the non-unique
`serverId` index does not collapse them. There is no `getSupportMessageAttachmentByServerId`
reconciliation. So each refresh of a conversation's messages **duplicates every attachment**.

## Currently latent

Like [[RP-FR-005]], the read path `getAttachmentsForSupportMessage` has **no callers** (attachment
rendering is not wired in `ui/support`), so the duplicate rows are not yet user-visible. The defect
becomes visible the moment attachment rendering lands: each refresh would show another copy of every
attachment.

## Fix (implemented 2026-06-07)

Added `getSupportMessageAttachmentByServerId` (`OfflineDao` + `LocalDataService`). In `syncMessages`,
each attachment is now reconciled by `serverId`: if a local row already exists, its local
`attachmentId` (PK) is preserved (and its cached `localPath`) so the upsert updates in place instead
of inserting a duplicate; otherwise it inserts as a new row. Mirrors the message reconcile from
RP-BUG-036. Covered by `SupportSyncServiceTest` ("reconciles attachment by serverId preserving local
PK").

## Affected code

| Concern | File:line |
|---------|-----------|
| Attachment build + save (pull) | `SupportSyncService.syncMessages` |
| PK-only upsert | `OfflineDao.upsertSupportMessageAttachments` (`@Upsert`) |
| Non-unique serverId index | `OfflineSupportMessageAttachmentEntity` `@Entity` indices |
| New reconcile query | `OfflineDao.getSupportMessageAttachmentByServerId` |

## Rule note

Same identity-reconciliation family as RP-CD-014 / [[RP-BUG-036]] / [[RP-FR-005]]. The recurring
pattern (server-sourced rows pulled into local autoGenerate-PK tables must reconcile by `serverId`
before upsert) is now a strong candidate for a dedicated `RP-CD-###` rule.

## Observability

### Current Signals
- None — read path unused.

### Gaps
- Duplicate attachment rows are silent until attachment rendering is added.

### Proposed Instrumentation
- When wiring attachment reads, log if `getAttachmentsForSupportMessage` returns multiple rows sharing
  one `serverId` — surfaces residual duplication.

### Success Criteria
- Re-pulling a conversation's messages leaves exactly one attachment row per server attachment id.
