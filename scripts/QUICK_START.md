# Quick Start: Log Capture

## Most Common Usage

```bash
# Quick dump (your current workflow)
./scripts/capture-logs.sh

# Live capture while testing
./scripts/capture-logs.sh debug/live.logcat live

# Check errors only
./scripts/capture-logs.sh debug/errors.logcat errors-only
```

## What Gets Captured

✅ **Automatically filtered for relevant logs:**
- API calls and responses (`API:*`)
- Sync operations (`SyncQueueManager:*`, `OfflineSyncRepository:*`)
- UI state (`ProjectDetailVM:*`, `ProjectsViewModel:*`)
- All errors and warnings (`*:E`, `*:W`)

🚫 **Filters out noise:**
- System logs
- Framework logs
- Unrelated app logs

## Output Example

```
📱 Dumping existing logs...
   Output: debug/current_sync.logcat
   ✅ Saved 1012 lines

   📊 Quick Summary:
   - Sync starts:  2
   - Projects:     1
   - Rooms:        9
   - Photos:       568
   - Errors:       0
   - Warnings:     3

   🔍 Last 10 important events:
     ✅ Project found: 201 Faker Road
     🏠 Loading 9 rooms
     📚 Loading 5 albums
     ...
```

## Quick Alias (Recommended)

Add to `~/.zshrc`:
```bash
alias rp-logs='cd /Users/kilka/GitHub/Rocketplan_android && ./scripts/capture-logs.sh'
```

Then just use: `rp-logs`
