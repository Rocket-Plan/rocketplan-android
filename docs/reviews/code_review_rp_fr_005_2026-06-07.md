**Bug ID:** RP-FR-005
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-FR-005_support_attachment_message_id_mismatch.md) · [RP-BUG-036 Plan](../plans/plan_rp_bug_036_support_identity_reconcile_2026-06-07.md) · [Test](../../app/src/test/java/com/example/rocketplan_android/data/repository/sync/SupportSyncServiceTest.kt)

# Code Review: RP-FR-005 support attachment message-id mismatch

**Bug ID(s):** RP-FR-005
**Reviewer:** Codex
**Date:** 2026-06-07
**Timestamp:** 2026-06-07 10:29:58 PDT
**Uncommitted files at review start (`git status --porcelain`):**
```text
?? data/
?? docs/reviews/deep_review_2026-06-05/
?? workflows/
```

## Summary

`RP-FR-005` is correctly classified and the implemented fix is sound.

The bug was latent: support attachments were being persisted with `messageId = dto.id` where `dto.id` is the **server** message id, but attachment reads key on the **local** `OfflineSupportMessageEntity.messageId` primary key. The fix now resolves each attachment owner through `getSupportMessageByServerId(dto.id)` after message save/reconcile, and persists the attachment against the canonical local message PK instead.

## Findings

### Must Fix

None.

### Should Fix

1. **Consider adding explicit dedup/reconcile comments near attachment upsert as a long-term guard.**
   The current inline comment is good; keeping the identity invariant documented near both the message reconcile path and the attachment save path will help prevent regressions.

2. **If attachment UI lands soon, add an integration-style test that reads back attachments by local message PK.**
   Current tests verify the write contract via mocks; a read-path test would lock the FK expectation end-to-end.

### Consider

1. **A dedicated architecture rule may eventually be warranted.**
   This is adjacent to RP-CD-014, but the actual invariant here is more general: local FK columns must store local PKs, with server ids reconciled before linking.

### Verified Safe

1. **Fix shape is correct.**
   In `SupportSyncService.syncMessages()`, messages are saved first, then attachments resolve `localMessageId` via `localDataService.getSupportMessageByServerId(dto.id)` and persist `OfflineSupportMessageAttachmentEntity(messageId = localMessageId, ...)`. That directly addresses the mismatch.

2. **Failure fallback is safe.**
   If the owning message cannot be resolved, the code logs `support_attachment_unresolved_message` and skips attachment persistence rather than storing a wrong FK.

3. **Required local lookup plumbing exists.**
   `LocalDataService.getSupportMessageByServerId()` and `OfflineDao.getSupportMessageByServerId()` are present and correctly key on `offline_support_messages.serverId`.

4. **Targeted tests cover the core behavior.**
   `SupportSyncServiceTest` now verifies:
   - attachments are saved with the **local** message PK (`messageId == 700L` in the test), and
   - attachments are skipped when the owning message cannot be resolved.

5. **Classification remains correct.**
   I found no current `ui/support` attachment read path, so `RP-FR-005` remains an architectural/latent issue rather than a present user-visible `RP-BUG`.

## Sign-off

| Role | Reviewer | Date |
|------|----------|------|
| Primary | Codex | 2026-06-07 |
