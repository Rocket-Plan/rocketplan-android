# RocketPlan Android Scripts

## UI Flow Runner (adb)

`adb_flow_runner.py` - Runs small, file-driven UI flows using `adb` (tap/input/dump-backed clicks).

Example (dev build): 
```bash
python scripts/adb_flow_runner.py --flow scripts/flows/login_and_project_detail.json
```
- Uses `ui_dumps/001_project_detail_clickables.txt` for deterministic taps on the Project Detail screen (indices 5 and 10).
- Override creds via env: `RP_TEST_EMAIL`, `RP_TEST_PASSWORD`.
- App/package defaults in the sample flow target the dev variant (`com.example.rocketplan_android.dev`).

## Log Capture Script

`capture-logs.sh` - Streamlined Android device log capture with intelligent filtering

### Usage

```bash
# Quick dump to debug/current_sync.logcat (most common)
./scripts/capture-logs.sh

# Dump to specific file
./scripts/capture-logs.sh debug/test.logcat

# Live capture (shows on screen + saves to file)
./scripts/capture-logs.sh debug/live.logcat live

# Sync logs only (API calls, sync operations)
./scripts/capture-logs.sh debug/sync.logcat sync-only

# Errors and warnings only
./scripts/capture-logs.sh debug/errors.logcat errors-only

# Full capture (all tags)
./scripts/capture-logs.sh debug/full.logcat full
```

### Fastest Invocation Tips

- Run the script directly or with `bash -c`, not a login shell. Avoid `bash -lc` because it loads your full shell profile and slows startup.
- Prefer PID scoping to capture only this app’s logs (see options below).

Examples:

```bash
# Fast one-shot dump, scoped to app PID
USE_PID=true LOG_LINES=2000 ./scripts/capture-logs.sh dump

# Live capture while reproducing an issue
USE_PID=true ./scripts/capture-logs.sh debug/room_click.logcat live

# If multiple devices are connected
ADB_SERIAL=emulator-5554 USE_PID=true ./scripts/capture-logs.sh debug/current_sync.logcat sync-only
```

### Modes

- **dump** (default) - Dumps last 5000 lines with sync, UI, and error logs
- **live** - Real-time capture with screen output (Ctrl+C to stop)
- **sync-only** - Only API and sync-related logs
- **errors-only** - Only errors and warnings
- **full** - All tags including auth and system logs

### Options (Environment Variables)

- `APP_ID` — Android applicationId to scope by process name. Default: `com.example.rocketplan_android`
- `USE_PID` — If `true`, auto-resolves the app PID and adds `--pid <pid>` to `adb logcat` to capture only this app’s logs.
- `ADB_SERIAL` — If set, uses a specific device/emulator: passed as `adb -s <serial>`.
- `LOG_LINES` — Number of lines to dump in `dump` mode. Default: `5000`.

Examples:

```bash
# Staging build variant
APP_ID=com.example.rocketplan_android.staging USE_PID=true ./scripts/capture-logs.sh dump

# Limit log size when sharing
LOG_LINES=1200 USE_PID=true ./scripts/capture-logs.sh debug/shareable.logcat dump
```

### Filtered Tags

The script automatically filters for these log tags:

**Sync Tags:**
- `API` - API calls and sync operations
- `SyncQueueManager` - Sync queue management
- `OfflineSyncRepository` - Repository sync logic

**UI Tags:**
- `ProjectDetailVM` - Project detail view model
- `ProjectDetailFrag` - Project detail fragment
- `ProjectsViewModel` - Projects list view model
- `ProjectsFragment` - Projects list fragment
- `RoomDetailVM` - Room detail view model
- `MainActivity` - Main activity

**Auth Tags:**
- `LoginFragment` - Login screen
- `EmailCheckFragment` - Email check screen
- `AuthRepository` - Authentication logic

**System Tags:**
- `RemoteLogger` - Remote logging
- `PhotoCacheManager` - Photo caching
- `PendingRemoteLogStore` - Log store

**Always Included:**
- All errors (`*:E`)
- All warnings (`*:W`)

### Output

The script provides:
- Line count of captured logs
- Summary statistics (sync starts, projects, rooms, photos, errors, warnings)
- Last 10 important events with emoji markers (✅ ❌ ⚠️ 🔄 📸 🏠 📚)

### Examples

```bash
# Daily workflow: Quick dump and review
./scripts/capture-logs.sh

# Debugging a specific feature
./scripts/capture-logs.sh debug/room_sync_$(date +%H%M).logcat live

# Check for errors after a test run
./scripts/capture-logs.sh - errors-only

# Full detailed capture for bug reports
./scripts/capture-logs.sh debug/bug_report.logcat full
```

### Adding to PATH (Optional)

Add this to your `~/.zshrc` or `~/.bash_profile`:

```bash
alias rp-logs='/Users/kilka/GitHub/Rocketplan_android/scripts/capture-logs.sh'
```

Then reload: `source ~/.zshrc`

Usage becomes: `rp-logs` or `rp-logs debug/test.logcat live`

### Troubleshooting

- “No connected device”: Run `adb devices` and ensure your device appears as `device`. If more than one device is listed, set `ADB_SERIAL`.
- “No logs captured for app”: Ensure the app is running. With `USE_PID=true`, the script needs a live PID.
- Very slow start: Ensure you’re not invoking from a login shell; run directly or `bash -c './scripts/capture-logs.sh …'`.

### Quick Recipe: Room Photos Flicker/Disappear

1) Start live capture filtered to your app PID:

```bash
USE_PID=true ./scripts/capture-logs.sh debug/room_click.logcat live
```

2) Reproduce: open a project → open a room → wait 3–5s → tap into the room.

3) Stop capture (Ctrl+C). Share `debug/room_click.logcat`.

What to look for:
- API: `syncProjectGraph` messages, “Fetching photos for room”, “Saved N photos for room <id>”, “INFO … no photos (404)”.
- UI: `RoomDetailVM`, `ProjectDetailVM/Frag` events that might trigger a refresh.
- Errors: any `❌` around photos/albums or database writes.
