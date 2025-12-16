# RoomDetailViewModel Test Coverage Notes

Last updated: `2025-11-12`

## Added/Planned Unit Tests

- **UI readiness flow**: Verifies `RoomDetailViewModel` emits `RoomDetailUiState.Ready` when `LocalDataService.observeRooms/Notes/Albums` resolve, ensuring header text, album list, and photo count match the mocked flows.
- **Tab selection**: Confirms redundant calls to `selectTab` do not emit new values; switching tabs updates `_selectedTab`.
- **Photo refresh throttling**: Uses `SystemClock` stubbing to ensure `ensureRoomPhotosFresh` calls `OfflineSyncRepository.refreshRoomPhotos` only when the room has a `serverId`, honoring the `ROOM_REFRESH_INTERVAL_MS` window.
- **Local capture deferral**: Confirms `onLocalPhotoCaptured` no longer persists placeholder rows, emits a user-facing error, and logs that the image processor will supply the final photos.
- (Optional future work) **Paging transformation**: could use `AsyncPagingDataDiffer` to assert `photoPagingData` filters out photos lacking renderable assets and formats `capturedOn` with the thread-local formatter.

## Test Harness Details

- File: `app/src/test/java/com/example/rocketplan_android/ui/projects/RoomDetailViewModelTest.kt`
- Utilities: `MainDispatcherRule` (sets `Dispatchers.Main`), `app.cash.turbine` for `StateFlow` assertions, `MockK` for stubbing Android logs, repositories, and data services.
- Helpers: `createViewModel` configures all required flows and dependencies; `defaultRoom`, `defaultNote`, `defaultAlbum` seed consistent fixtures.

## Running The Tests

```bash
GRADLE_USER_HOME=$PWD/.gradle ./gradlew app:testDevDebugUnitTest --tests "com.example.rocketplan_android.ui.projects.RoomDetailViewModelTest"
```

If the project’s other unit tests are broken, Gradle may fail before this suite runs. Fix or temporarily disable the failing suites (currently `OfflineSyncRepositoryTest` and `ProjectDetailViewModelTest`) and rerun the command.

## Outstanding Issues

1. `OfflineSyncRepositoryTest` builder helpers need updates for the latest entity signatures (`albums`, `assemblyId`, `tusUploadId`, etc.).
2. `ProjectDetailViewModelTest` references a removed helper (`filterRoomScopedAlbums`) and Truth’s `containsExactly` extension; adjust expectations or bring back the helper.

Once those are resolved, rerun the Gradle command above to confirm the new RoomDetailViewModel coverage passes.
