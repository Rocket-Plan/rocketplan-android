# RocketPlan Android

## Build Variants

- **Dev Standard**: `devStandardDebug` - Development build with `.dev` suffix
- **Dev FLIR**: `devFlirDebug` - Development build for FLIR thermal devices
- **Staging**: `stagingStandardDebug` - Staging build with `.staging` suffix
- **Production**: `prodStandardRelease` - Production release build

## Test Devices

| Device | ADB Serial | Build Variant | Install Command |
|--------|------------|---------------|-----------------|
| FLIR ixx | `b7045238` | `devFlirDebug` | `./gradlew assembleDevFlirDebug && adb -s b7045238 install -r app/build/outputs/apk/devFlir/debug/app-dev-flir-debug.apk` |
| Tablet (9024O) | `30407ef` | `devStandardDebug` | `./gradlew assembleDevStandardDebug && adb -s 30407ef install -r app/build/outputs/apk/devStandard/debug/app-dev-standard-debug.apk` |

**Important:** Only install FLIR builds on FLIR devices and Standard builds on regular devices.

## Fastlane

Fastlane is used for build automation and deployment.

### Setup

Requires Homebrew Ruby (system Ruby won't work):

```bash
brew install ruby
/usr/local/opt/ruby/bin/bundle _2.5.0_ install
```

### Running Lanes

Use the Homebrew Ruby bundle with version specifier:

```bash
/usr/local/opt/ruby/bin/bundle _2.5.0_ exec fastlane <lane>
```

Or add an alias to your shell config:

```bash
alias fastlane='/usr/local/opt/ruby/bin/bundle _2.5.0_ exec fastlane'
```

### Available Lanes

| Lane | Description |
|------|-------------|
| `bump` | Increment build number in build.gradle.kts |
| `build_debug` | Build dev debug APK |
| `build_staging` | Build staging APK |
| `build_release` | Build production release AAB |
| `test` | Run unit tests |
| `bump_and_build` | Bump version + build debug |
| `clean` | Clean build directory |
| `deploy_internal` | Bump + build + deploy to Play Store internal track |
| `deploy_beta` | Bump + build + deploy to Play Store beta track |
| `deploy_production` | Bump + build + deploy to Play Store production |

### Play Store Deployment

To deploy to Play Store, add your Google Play JSON key file path to `fastlane/Appfile`:

```ruby
json_key_file("path/to/your/play-store-key.json")
```

## Gradle Commands

```bash
./gradlew assembleDevStandardDebug      # Build debug APK
./gradlew installDevStandardDebug       # Install to connected device
./gradlew testDevStandardDebugUnitTest  # Run unit tests
./gradlew compileDevStandardDebugKotlin # Compile only (fast check)
```

## FLIR Device

FLIR builds use the `flir` product flavor with ARM64 native libraries.

### Build & Install

```bash
./gradlew installDevFlirDebug           # Build and install FLIR debug APK
```

### WiFi Debugging

FLIR ixx device IP: `192.168.0.57`

To connect over WiFi (after enabling once via USB):

```bash
adb connect 192.168.0.57:5555
```

To enable WiFi debugging (requires USB connection once):

```bash
adb tcpip 5555
adb connect <device-ip>:5555
# Then unplug USB
```

## Version Management

Build number is in `app/build.gradle.kts`:

```kotlin
val buildNumber = 8  // Current as of Jan 2026
versionCode = buildNumber
versionName = "1.29 ($buildNumber)"
```

Use `fastlane bump` to increment automatically.

## Architecture

### Offline-First Data Flow

The app uses an offline-first architecture with Room database as the source of truth:

```
Server API ─► SyncServices ─► Room Database ─► UI (via Flow)
                   ▲                              │
                   └──────── Pending Ops ◄────────┘
```

1. **UI reads from Room** via reactive Flows
2. **User actions** create pending operations in the sync queue
3. **SyncQueueManager** processes pending operations and syncs with server
4. **Server changes** update Room, which triggers UI updates

### Sync Architecture

Key sync components in `data/repository/sync/` and `data/sync/`:

| Component | Purpose |
|-----------|---------|
| `SyncQueueManager` | Orchestrates all sync jobs, manages queue priority |
| `ProjectSyncService` | Syncs project list using incremental checkpoints |
| `DeletedRecordsSyncService` | Fetches deletions from `/api/sync/deleted` endpoint |
| `SyncJob` | Sealed class defining job types (SyncProjects, SyncDeletedRecords, etc.) |

**Sync job flow:**
```
ensureInitialSync() / refreshProjects()
    └─► EnsureUserContext
    └─► ProcessPendingOperations
    └─► SyncDeletedRecords        # Fetch explicit deletions from server
    └─► SyncProjects(force=true)  # Fetch all projects
```

**Deletion sync:** Uses `/api/sync/deleted?since=<timestamp>` endpoint instead of inferring deletions from absence in API responses. This prevents data loss from incomplete API responses.

### Image Processor & Pusher Realtime

Photo uploads use the ImageProcessor system with Pusher for realtime status updates:

| Component | Location | Purpose |
|-----------|----------|---------|
| `ImageProcessorQueueManager` | `data/queue/` | Manages upload queue |
| `ImageProcessorRealtimeManager` | `realtime/` | Handles Pusher status updates |
| `PusherService` | `realtime/` | Pusher connection management |

**Photo upload flow:**
```
Capture ─► LocalDB (pending) ─► Upload ─► Server Processing ─► Pusher Event ─► Complete
              │                              │                      │
              └── UI shows spinner ──────────┴──────────────────────┘
```

**Pusher channels:**
- `imageprocessornotification.AssemblyId.<id>` - Photo/assembly status updates
- Events: `ImageProcessorPhotoUpdated`, `ImageProcessorUpdated`

**Status transitions:**
- `created` → `uploading` → `processing` → `completed`
- UI shows spinner until status becomes `completed` or `failed`

### Key Files

| Area | Files |
|------|-------|
| Sync Queue | `SyncQueueManager.kt`, `SyncJob.kt` |
| Project Sync | `ProjectSyncService.kt`, `DeletedRecordsSyncService.kt` |
| Local Storage | `LocalDataService.kt`, `OfflineDao.kt` |
| Image Upload | `ImageProcessorQueueManager.kt`, `ImageProcessorRealtimeManager.kt` |
| Pusher | `PusherService.kt`, `PusherConfig.kt` |
