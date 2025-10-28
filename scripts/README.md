# RocketPlan Android Scripts

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

### Modes

- **dump** (default) - Dumps last 5000 lines with sync, UI, and error logs
- **live** - Real-time capture with screen output (Ctrl+C to stop)
- **sync-only** - Only API and sync-related logs
- **errors-only** - Only errors and warnings
- **full** - All tags including auth and system logs

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
