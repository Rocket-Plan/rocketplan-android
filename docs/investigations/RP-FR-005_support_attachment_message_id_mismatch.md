---
bug_id: RP-FR-005
aliases: []
title: Support message attachments store server message id in local FK field, so future attachment reads will not reconcile
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

# RP-FR-005: Support attachments keyed by server message id, not local message PK

> Surfaced alongside [[RP-BUG-036]] during the support-pull identity trace. Same identity-mismatch
> family (server id vs local PK) as RP-BUG-036 / RP-CD-014, but on the **attachment → message** edge.
> Filed `RP-FR` (architectural / rule violation), not `RP-BUG`, because there is **no user-visible
> failure path today** — see "Currently latent" below.

## The mismatch

`SupportSyncService.syncMessages` builds each attachment row with the **server** message id as its
local foreign key:

```kotlin
OfflineSupportMessageAttachmentEntity(
    serverId  = attachment.id,
    messageId = dto.id,        // dto.id is the SERVER message id
    ...
)
```

But `OfflineSupportMessageEntity.messageId` is an **autoGenerate local primary key**, distinct from the
server id. The only read path keys on that local PK:

```kotlin
@Query("SELECT * FROM offline_support_message_attachments WHERE messageId = :messageId ORDER BY attachmentId")
suspend fun getAttachmentsForSupportMessage(messageId: Long)
```

So attachments are stored under the server message id while any lookup passes the local message PK —
the two will not join (except by coincidence when the ids happen to coincide). This is the same
server-id-vs-local-id confusion that caused [[RP-BUG-036]], on the attachment→message link.

## Currently latent

`getAttachmentsForSupportMessage` has **no callers** — a repo-wide search finds no `ui/support` code
that reads message attachments (the chat screen does not render attachments yet). So nothing reads the
mis-keyed rows today and there is no user-visible failure. The defect becomes user-visible the moment
attachment rendering is wired up: attachments would silently fail to appear under their message.

## Affected code

| Concern | File:line |
|---------|-----------|
| Attachment built with server message id | `SupportSyncService.syncMessages` (~:99–110, `messageId = dto.id`) |
| Attachment entity FK field | `OfflineSupportMessageAttachmentEntity.messageId` |
| Read keys on local PK | `OfflineDao.getAttachmentsForSupportMessage` (`OfflineDao.kt:1385`) |
| No callers (latent) | no references in `ui/support` |

## Fix (implemented 2026-06-07)

`SupportSyncService.syncMessages` now resolves each attachment's owning message to its **canonical
local message PK** via `getSupportMessageByServerId(dto.id)` (messages are saved first, so both
reconciled and newly-inserted rows are resolvable by server id) and stores that local `messageId` on
the attachment. If the owning message cannot be resolved, the attachments are skipped (with a
`support_attachment_unresolved_message` WARN) rather than keyed by the wrong id. Covered by
`SupportSyncServiceTest` ("saves attachments when present" asserts the local PK; "skips attachments
when owning message cannot be resolved").

### Original suggested fix (for reference)

Resolve the attachment's owning message to its **canonical local message PK** before persisting —
look up the message by its server id (`getSupportMessageByServerId`, added in RP-BUG-036) and store
that local `messageId` on the attachment. Mirror the message reconciliation already implemented for
RP-BUG-036.

## Rule note

No existing `RP-CD-###` rule precisely states "local foreign-key columns must hold local PKs; reconcile
a server id to the local PK before linking." This is the nearest sibling of RP-CD-014 (identity
reconciliation on pull) — a candidate for a dedicated rule if more local-FK-vs-server-id cases appear.

## Observability

### Current Signals
- None — the path is unused, so there is nothing to observe yet.

### Gaps
- When attachment rendering is added, a missing-attachment would be silent (empty list), not an error.

### Proposed Instrumentation
- When wiring attachment reads, add a debug assertion/log if an attachment's `messageId` does not match
  any local message PK — surfaces residual mis-keying.

### Success Criteria
- Attachments resolve to their message via the local PK; `getAttachmentsForSupportMessage(localPk)`
  returns the pulled attachments.
