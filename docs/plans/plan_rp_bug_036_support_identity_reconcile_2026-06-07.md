# Fix Plan: RP-BUG-036 — Support conversations/messages duplicate on refresh

**Bug:** [RP-BUG-036](../investigations/RP-BUG-036_support_duplicate_on_refresh.md)
**Review:** [code_review_rp_bug_036_2026-06-07.md](../reviews/code_review_rp_bug_036_2026-06-07.md)
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Rule:** RP-CD-014 (related; this is an *identity reconciliation* bug, not an RP-CD-014 drop)
**Author:** Claude
**Date:** 2026-06-07

## Goal

Make the support pull idempotent by reconciling server rows to existing local rows **by `serverId`**,
so a record created offline (local PK + client `uuid`) and later pulled back (server `uuid`) collapses
into **one** row instead of duplicating. Per review, the message side additionally must attach pulled
messages to the **canonical local `conversationId`**, not the server id or a duplicate conversation row.

## Root cause (summary)

Client `uuid` is never sent on create → server mints its own `uuid`; push sets `serverId` but not
`uuid`; the pull maps server DTOs to entities with PK `0` and `@Upsert` matches on PK only → a second
row is inserted (different uuid, same `serverId`); observe does no dedup → user sees a duplicate.

## Changes

### 1. DAO + LocalDataService — message lookup by serverId
- `OfflineDao`: add `getSupportMessageByServerId(serverId: Long): OfflineSupportMessageEntity?`
  (`getSupportConversationByServerId` already exists).
- `LocalDataService`: add the `getSupportMessageByServerId` wrapper.

### 2. Conversation pull reconcile — `SupportSyncService.syncConversations`
For each mapped server entity, look up `getSupportConversationByServerId(serverId)`. If found, copy the
existing row's local `conversationId` (PK) and `uuid` onto the entity before save, so the upsert
**updates in place** instead of inserting. New conversations (no local match) insert as before.

### 3. Message pull reconcile + canonical conversationId — `SupportSyncService.syncMessages`
- Resolve the canonical local conversationId once:
  `localConversationId = getSupportConversationByServerId(conversationServerId)?.conversationId`
  (fallback to the DTO/0 if not yet synced).
- For each mapped message entity:
  - set `conversationId = localConversationId` (attach to the canonical conversation — review constraint),
  - look up `getSupportMessageByServerId(serverId)`; if found, copy its local `messageId` (PK) + `uuid`
    so the upsert updates in place; otherwise insert.
- **Ordering:** conversation reconcile (step 2) must run before message reconcile so the canonical
  conversationId is resolvable. The existing sync flow already syncs conversations before messages; the
  plan does not change ordering, only relies on it (note it explicitly).

### 4. Observability
Emit a one-line debug log per pull with reconciled/inserted counts
(`support_pull_reconciled: type=… reconciled=… inserted=…`), mirroring the WorkScope merge log. This
replaces the proposed `support_duplicate_server_id` WARN — once reconciliation lands, duplicates can no
longer be created, so a counted reconcile log is the more useful signal.

## Tests (`SupportSyncServiceTest`, new or existing)
1. **Conversation re-pull is idempotent:** pre-seed a local conversation (serverId=Y, client uuid),
   pull a server conversation with serverId=Y + different uuid → exactly one row, local PK preserved.
2. **Message re-pull is idempotent:** same for a message (serverId=Y_msg).
3. **Cross-id reattachment:** local conversation has local PK X / serverId Y; pull messages keyed to
   conversationServerId=Y → messages attach to `conversationId == X`, not Y or a new row.
4. **New records still insert:** server rows with no local match insert normally.

## Out of scope
- Sending/echoing client `uuid` on create (needs coordinated backend change). Client-side `serverId`
  reconciliation is the self-contained fix.
- Attachment keying (`messageId = dto.id` in `syncMessages`) — separate concern, not addressed here.

## Lifecycle
`open → planned` on writing this; `→ fixed` when the change + tests land.
