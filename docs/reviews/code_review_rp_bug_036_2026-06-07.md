**Bug ID:** RP-BUG-036
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-036_support_duplicate_on_refresh.md)

# Code Review: RP-BUG-036 support duplicate-on-refresh

**Bug ID(s):** RP-BUG-036
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 10:02:29 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

`RP-BUG-036` is a **real bug**. The investigation's core claim is correct: support conversation pulls can create a second local row for the same server record because support create uses a client UUID, push only backfills `serverId`, and pull later upserts by primary key without reconciling by `serverId`.

The same identity split also affects support messages, but the message side needs a slightly tighter fix plan than the investigation currently states: message reconciliation must not only match by `serverId`, it must also ensure the pulled row is attached to the canonical **local** `conversationId`, not the server conversation id.

## Findings

### Must Fix

1. **Conversation duplication bug is valid as written.**
   - Offline create writes a local row with `serverId = null` and a client UUID: `SupportSyncService.createConversation()`.
   - Push success only updates `serverId`: `LocalDataService.updateSupportConversationServerId()` / `OfflineDao.updateSupportConversationServerId()`.
   - Pull maps server DTOs to fresh entities with default PK `0` and saves them through plain `@Upsert`: `SupportSyncService.syncConversations()` → `LocalDataService.saveSupportConversations()` → `OfflineDao.upsertSupportConversations()`.
   - `uuid` uniqueness does not collapse the rows because the local pending row keeps the client UUID while the server row carries the server UUID.
   - `serverId` is indexed but **not unique**, so two rows with the same `serverId` are allowed.
   - `SupportViewModel` renders `observeSupportConversations()` 1:1, so the duplicate is user-visible.

2. **The message side likely needs stronger repair than the investigation currently documents.**
   - `SupportSyncService.syncMessages()` maps DTOs using `dto.toEntity(conversationServerId)`.
   - `SupportMessageDto.toEntity()` sets `conversationId = conversationId ?: 0` from the DTO, while local observation is keyed by the local Room PK: `observeSupportMessages(conversationId)`.
   - If the backend's `conversationId` is the server conversation id (which is the likely reading of this API shape), then pulled messages are not only unreconciled by `serverId`; they are also attached to the wrong parent key for local observation.
   - That means the eventual fix should resolve the canonical local conversation row first, then persist messages against that local `conversationId`.

### Should Fix

1. **Promote the investigation's suggested fix into a concrete plan before implementation.**
   Recommended shape:
   - Add `getSupportMessageByServerId(serverId)`.
   - For conversations: before save, resolve existing local row by `serverId` and preserve its local PK.
   - For messages: resolve the canonical local conversation by `conversationServerId`, then resolve existing local message by message `serverId`, and persist using the canonical local `conversationId`.
   - Add targeted unit/integration coverage for: offline create → push success → pull refresh → exactly one conversation, exactly one message.

2. **Add local duplicate detection logging while fixing.**
   The investigation's proposed WARN is appropriate and should cover both conversations and messages.

### Consider

1. **Client/server UUID parity is a backend-coordinated improvement, not the first-line fix.**
   Sending client UUIDs on create would simplify reconciliation, but client-side `serverId`-based reconciliation is still required and should be implemented independently.

2. **Consider a future uniqueness invariant on support `serverId` once migration story is defined.**
   A unique index on nullable `serverId` could prevent this class of duplicate after data cleanup/migration, but it is not a safe drop-in fix without first reconciling existing rows.

### Verified Safe

1. **This is not an RP-CD-014 pending-create-drop bug.**
   The pending support row is not deleted by pull; the defect is duplicate insertion from identity mismatch.

2. **The investigation's conversation-side diagnosis is materially correct.**
   I did not find evidence contradicting the central failure path for conversations.

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | Codex | 2026-06-07 |
