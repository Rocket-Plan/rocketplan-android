**Bug ID:** RP-BUG-338
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-338_flag_rollback_wrong_screen_2026-07-17.md) · Plan (this doc)

# Plan — Fix flag-rollback fallback destination (+ move dialog self-room)

## Fix 1 — land on the legacy screen, not the project screen
`res/navigation/mobile_navigation.xml` + `ui/rocketdry/SerializedRoomEquipmentFragment.kt:196`.

Add a reverse nav action `serializedRoomEquipmentFragment → equipmentRoomFragment` carrying
`projectId` + `roomId`, with `popUpTo=serializedRoomEquipmentFragment` `popUpToInclusive=true`. On
`LegacyMode`, navigate via this action instead of `popBackStack()`. Landing on a fresh
`equipmentRoomFragment` with the flag now OFF mounts the legacy screen.

**Must re-verify after the change** (do NOT just flip `popUpToInclusive` on the forward action — it
was engineered deliberately): the round-6/7 guarantees still hold —
- `EquipmentRoomFragment.navigateToSerializedOnce` gates on `currentDestination.id == equipmentRoomFragment`,
- `legacyMounted` resets in `onDestroyView`,
so an ON→OFF→ON cycle produces no double-mount and no navigation loop.

## Fix 2 — move dialog self-room (minor)
`ui/rocketdry/SerializedRoomEquipmentViewModel.kt` (`roomChoices()`, ~line 176): filter out the
current room so it isn't offered as a guaranteed-fail move target:

```kotlin
.filter { !it.isDeleted && it.roomId != roomId }
```

## Verification
- Both compile gates clean.
- Manual on-device: with serialized ON, open the serialized screen; flip the company flag OFF
  (backend) and confirm the screen falls back to the legacy equipment screen (not the RocketDry
  project screen). Then flip ON again and confirm the serialized screen returns with no double-mount.
- Open the move dialog; confirm the current room is absent from the list.

## Observability
None required (UI navigation).
