**Bug ID:** RP-BUG-339
**Tracker:** [BUG_TRACKER.md](../BUG_TRACKER.md)
**Related:** [Investigation](../investigations/RP-BUG-339_serialized_resolve_uncaught_throw_spinner_2026-07-17.md) · Plan (this doc)

# Plan — Add an error boundary to resolve()

## Fix
`ui/rocketdry/SerializedRoomEquipmentViewModel.kt` — `resolve()` (~lines 89-133). Add a `.catch { }`
to the flow before `.collect`:

```kotlin
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    app.remoteLogger.log(
                        com.example.rocketplan_android.logging.LogLevel.WARN, "equip_ui",
                        "Serialized room resolve flow threw",
                        mapOf("roomId" to roomId.toString(),
                              "error" to (e::class.java.simpleName + ": " + (e.message ?: "")))
                    )
                    emit(SerializedRoomUiState.Unavailable)   // renders the retry affordance
                }
                .collect { _uiState.value = it }
```

`Unavailable` drives the retry UI (confirmed in the fragment). If the flow's element type rejects
`emit` inside `catch`, set `_uiState.value = SerializedRoomUiState.Unavailable` in the block instead.
Requires importing `kotlinx.coroutines.flow.catch`.

## Verification
- Both compile gates clean.
- Regression test (add): stub a Room read in the resolve chain to throw; assert `uiState` ends on
  `Unavailable`, not `Loading`.
- Manual: hard to force naturally; rely on the unit test + confirming retry works.

## Observability
Adds a `equip_ui` WARN (RP-HD-008 category) so the previously-silent stuck-spinner case is now
diagnosable remotely.
