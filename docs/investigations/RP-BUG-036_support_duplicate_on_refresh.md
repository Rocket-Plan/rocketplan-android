---
bug_id: RP-BUG-036
aliases: []
title: Support conversations/messages duplicate on refresh — pull does no serverId reconciliation and client UUID is never sent, so a second row is inserted per server id
type: functional
classification: pre_existing_latent
source: review
evidence: inferred
found_in: "1.0.00"
fixed_in: null
released_in: null
state: open
release_state: unreleased
regression_of: null
tracker: docs/BUG_TRACKER.md
related_plan: null
related_review: null
related_test: null
violates: RP-CD-014
priority: P2
last_updated: 2026-06-07
---

# RP-BUG-036: Support conversations/messages duplicate on refresh

> Surfaced by the 2026-06-07 RP-CD-014 verification trace (one of four candidates checked). The
> RP-CD-014 *drop* scenario does **not** apply here — the pending row is never dropped — but the same
> trace revealed a distinct **duplicate-on-refresh** defect. Filed separately because the failure
> mode (extra row) is not the rule's failure mode (lost row).

## Symptom

A user creates a support conversation (or sends a support message) while offline, the create later
syncs successfully, and then the user refreshes (or the conversation/message list re-syncs). The
conversation/message then appears **twice** in the UI — two rows representing the same server record.

## Root Cause (traced, not yet reproduced on device)

The local create and the subsequent server pull produce two rows that share a `serverId` but differ
on both primary key and `uuid`, and nothing reconciles them.

1. **Offline create** — `SupportSyncService.createConversation` / `sendMessage` writes a row with a
   local primary key, a **client-generated** `uuid` (`UuidUtils.generateUuidV7()`), `serverId = null`,
   `syncStatus = PENDING`.
2. **Client UUID is never sent to the server.** `CreateSupportConversationRequest`
   (`OfflineDtos.kt:810`) carries `category_id`, `subject`, `body`, `idempotency_key`;
   `CreateSupportMessageRequest` (`OfflineDtos.kt:817`) carries `body`, `idempotency_key`. **No `uuid`
   field.** The server therefore mints its own `uuid`.
3. **Push updates `serverId` but not `uuid`.** `SupportPushHandler` →
   `LocalDataService.updateSupportConversationServerId` / `updateSupportMessageServerId`
   (`OfflineDao` `UPDATE ... SET serverId, isDirty, syncStatus, lastSyncedAt WHERE <pk>`) leaves the
   local row's client `uuid` in place.
4. **Pull does a PK-only upsert with no `serverId` reconciliation.** `syncConversations` /
   `syncMessages` map the server DTO to an entity with the autoGenerate PK defaulting to `0`, then call
   `saveSupportConversations` / `saveSupportMessages` → `dao.upsertSupportConversations` /
   `upsertSupportMessages` (plain `@Upsert`). `@Upsert` matches on the **primary key** only; the server
   row's PK `0` matches nothing, so Room **inserts a new row**. The unique index is on `uuid`, and the
   server `uuid` ≠ the client `uuid`, so there is no conflict to collapse the rows. There is **no**
   `getSupportMessageByServerId` lookup, and `getSupportConversationByServerId` exists
   (`LocalDataService.kt:1842`) but is **not** used by the pull save.
5. **Observe shows both.** `observeSupportConversations` /
   `observeSupportMessages(conversationId)` filter only on `isDeleted = 0`; no dedup by `serverId`.
   The UI (`SupportViewModel`, `SupportChatViewModel`) maps rows 1:1, so the user sees a duplicate.

### Why this is NOT RP-CD-014
RP-CD-014 is about a pull *dropping* a pending offline create. Here the pending row survives — the bug
is an *extra* row, caused by identity mismatch (client uuid vs server uuid) plus the absence of
`serverId`-based reconciliation on the pull.

## Affected code

| Concern | File:line |
|---------|-----------|
| Conversation create (client uuid) | `SupportSyncService.createConversation` |
| Message create (client uuid) | `SupportSyncService.sendMessage` |
| Create requests omit uuid | `OfflineDtos.kt:810` (conversation), `OfflineDtos.kt:817` (message) |
| Push sets serverId not uuid | `SupportPushHandler`; `LocalDataService.updateSupportConversationServerId` / `updateSupportMessageServerId` |
| Pull plain upsert, no reconcile | `SupportSyncService.syncConversations` (~:79) / `syncMessages` (~:96); `LocalDataService.saveSupportConversations` (:1858) / `saveSupportMessages` (:1905); `OfflineDao.upsertSupportConversations` / `upsertSupportMessages` |
| Observe (no dedup) | `OfflineDao.observeSupportConversations`, `OfflineDao.observeSupportMessages` |

## Suggested fix (not yet implemented)

Reconcile by `serverId` on the support pull, mirroring how other entities resolve an existing local
row before upsert. For each incoming server row, if a local row already exists with that `serverId`,
update **that** row in place (preserve its local PK) instead of inserting a new one. A
`getSupportMessageByServerId` query must be added (the conversation equivalent already exists). The
WorkScope merge pattern (`WorkScopeSyncService.syncRoomWorkScopes`) is the closest template, but note
the reconciliation key here is `serverId`, not pending-create absence. Alternatively/additionally,
send the client `uuid` on create and have the server echo it, making `uuid` a stable cross-side
identity — but that requires a coordinated backend change, so the `serverId` reconciliation on the
client is the self-contained fix.

## Observability

### Current Signals
- Local console logs: `syncConversations` / `syncMessages` log a synced count, but not per-row identity.
- Remote logs: none for this path.
- Sentry: not captured (no crash; silent duplication).
- Existing metrics/watchdogs: none.

### Gaps
- Nothing distinguishes "inserted a new server row" from "updated an existing local row," so a
  duplicate is invisible until the user notices two identical entries.

### Proposed Instrumentation
- Local debug log to add: when the pull save inserts a row whose `serverId` already exists locally,
  emit a WARN (`support_duplicate_server_id`) with entity type + serverId. That both surfaces the bug
  and proves the fix (the WARN should stop firing once reconciliation lands).
- Build/env gating: console-only (`Log.w`) — low value as a remote log.

### Success Criteria
- QA: create conversation + message offline, go online, let it sync, pull-to-refresh → exactly one
  row each.
- Wild: the `support_duplicate_server_id` WARN never fires after the fix.
