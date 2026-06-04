**Bug ID(s):** RP-BUG-033
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-033_moisture_log_missing_drying_goal.md) · [Plan](./plan_rp_bug_033_moisture_drying_goal_2026-06-03.md) · Review: pending

# Fix Plan: [RP-BUG-033] Send dryingGoal in MoistureLogRequest

**Bug ID(s):** RP-BUG-033
**Author:** jeremie@rocketplantech.com
**Date:** 2026-06-03
**State:** draft

---

## Summary

`OfflineMoistureLogEntity.dryingGoal` is set locally and mapped back from `MoistureLogDto`, but `MoistureLogRequest` has no `dryingGoal` field and `toRequest()` never includes it. On push the value is dropped; the next pull overwrites the local value with the server's null/default. User-set drying goals silently reset.

The fix adds `dryingGoal` to `MoistureLogRequest` (with the correct `@SerializedName("drying_goal")`) and passes it through in `toRequest()`.

## Affected Code

| File | Change |
|------|--------|
| `data/model/offline/OfflineDtos.kt` (≈241–249) | Add `@SerializedName("drying_goal") val dryingGoal: Double? = null` to `MoistureLogRequest`. |
| `data/repository/mapper/SyncEntityMappers.kt` (≈656–665) | `toRequest()` sets `dryingGoal = dryingGoal`. |

## Implementation Notes

### Step 1: Request DTO field

```kotlin
data class MoistureLogRequest(
    val reading: Double? = null,
    val removed: Boolean? = null,
    val location: String? = null,
    @SerializedName("drying_goal")
    val dryingGoal: Double? = null,        // NEW
    @SerializedName("idempotency_key")
    val idempotencyKey: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)
```

Confirm the wire key with the server contract — `MoistureLogDto` reads it back as `@SerializedName("drying_goal")`, so the request should use the same key.

### Step 2: Mapper

```kotlin
internal fun OfflineMoistureLogEntity.toRequest(updatedAtOverride: String? = null) =
    MoistureLogRequest(
        reading = moistureContent,
        removed = removed,
        location = location,
        dryingGoal = dryingGoal,           // NEW
        idempotencyKey = uuid,
        updatedAt = updatedAtOverride ?: updatedAt.toApiTimestamp()
    )
```

### Step 3: Verify both endpoints accept it

`createMoistureLog` (POST `/api/rooms/{roomId}/damage-materials/{id}/logs`) and `updateMoistureLog` (PUT `/api/damage-material-room-log/{logId}`) both take `MoistureLogRequest`; the new field flows to both. Confirm server ignores or accepts `drying_goal` on both — coordinate with backend if create vs update differ.

## Observability

- Console log `moisture_log_drying_goal_set` already implied; add the value to the existing `moisture_log_sync` log so QA can confirm it is transmitted.

## Test Plan

- [ ] Unit: `toRequest()` includes `dryingGoal` when the entity has one; `null` when not set.
- [ ] Serialization test: `MoistureLogRequest` emits `"drying_goal"`.
- [ ] Manual QA:
  1. Set a drying goal on a moisture log; sync.
  2. Force a pull/refresh.
  3. Expected: drying goal persists (server round-trips it) instead of resetting.

## Rollback Plan

Revert both edits. No schema change. The field is nullable and additive, so older clients are unaffected.

## Dependencies

- Requires: backend accepts `drying_goal` on the moisture-log create/update endpoints (verify; the field already appears in responses).
- Blocking: none.

## Changelog Entry

```markdown
### Fixed
- [RP-BUG-033] Drying goals set on moisture logs are now sent to the server, so they persist across sync instead of silently resetting.
```
