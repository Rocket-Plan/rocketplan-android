#!/bin/bash
# RocketPlan Android Log Capture Script
# Usage: ./scripts/capture-logs.sh [output-file|mode] [mode|output-file]
# Modes: dump (default), live, sync-only, errors-only, full
#
# Optional env/config:
#  - APP_ID:   Android applicationId to scope logs (default: com.example.rocketplan_android)
#  - USE_PID:  If set to "true", resolve app PID and add "--pid <pid>" to adb logcat
#  - ADB_SERIAL: If set, passes "-s <serial>" to adb commands
#  - LOG_LINES: Number of lines for dump mode (default: 5000)

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEBUG_DIR="$PROJECT_DIR/debug"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Defaults / env
APP_ID="${APP_ID:-com.example.rocketplan_android}"
USE_PID="${USE_PID:-false}"
ADB_SERIAL="${ADB_SERIAL:-}"
LOG_LINES="${LOG_LINES:-5000}"

# Supported modes
MODES=("dump" "live" "sync-only" "errors-only" "full")

# Resolve arguments
RAW_OUTPUT="${1:-}"
RAW_MODE="${2:-}"

# Helper to test mode membership
is_mode() {
  local candidate="$1"
  for mode in "${MODES[@]}"; do
    if [[ "$candidate" == "$mode" ]]; then
      return 0
    fi
  done
  return 1
}

# Defaults
OUTPUT_FILE="$DEBUG_DIR/current_sync.logcat"
MODE="dump"

# Interpret first argument
if [[ -n "$RAW_OUTPUT" && "$RAW_OUTPUT" != "-" ]]; then
  if is_mode "$RAW_OUTPUT"; then
    MODE="$RAW_OUTPUT"
  else
    OUTPUT_FILE="$RAW_OUTPUT"
  fi
fi

# Interpret second argument (or explicit mode argument)
if [[ -n "$RAW_MODE" && "$RAW_MODE" != "-" ]]; then
  if is_mode "$RAW_MODE"; then
    MODE="$RAW_MODE"
  else
    OUTPUT_FILE="$RAW_MODE"
  fi
fi

# Final sanity: ensure MODE is valid
if ! is_mode "$MODE"; then
  MODE="dump"
fi

adb_prefix() {
  if [[ -n "$ADB_SERIAL" ]]; then
    echo "adb -s $ADB_SERIAL"
  else
    echo "adb"
  fi
}

ensure_device() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "❌ adb not found in PATH. Install Android Platform Tools or add adb to PATH."
    exit 1
  fi

  # Make sure the daemon is running
  $(adb_prefix) start-server >/dev/null 2>&1 || true

  local connected_devices
  connected_devices=$($(adb_prefix) devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ -z "$connected_devices" ]]; then
    echo "❌ No connected Android device or emulator detected."
    echo "   Tip: run 'adb devices' to verify connectivity."
    exit 1
  fi
}

# Optionally resolve PID for app-only logs
resolve_pid_filter() {
  if [[ "$USE_PID" != "true" ]]; then
    echo ""
    return 0
  fi
  local pid
  pid=$($(adb_prefix) shell pidof "$APP_ID" 2>/dev/null | tr -d '\r') || true
  if [[ -n "$pid" ]]; then
    echo "--pid $pid"
  else
    echo ""
  fi
}

# Logging tags used in the app
SYNC_TAGS=(
  "API:*"
  "SyncQueueManager:*"
  "OfflineSyncRepository:*"
)

UI_TAGS=(
  "ProjectDetailVM:*"
  "ProjectDetailFrag:*"
  "ProjectsViewModel:*"
  "ProjectsFragment:*"
  "MapViewModel:*"
  "MapFragment:*"
  "RoomDetailVM:*"
  "RoomDetailFrag:*"
  "MainActivity:*"
)

AUTH_TAGS=(
  "LoginFragment:*"
  "EmailCheckFragment:*"
  "AuthRepository:*"
)

SYSTEM_TAGS=(
  "RemoteLogger:*"
  "PhotoCacheManager:*"
  "PendingRemoteLogStore:*"
)

# Error levels
ERROR_TAGS=(
  "*:E"  # All errors
  "*:W"  # All warnings
)

# Build filter based on mode
build_filter() {
  local tags=()

  case "$MODE" in
    live)
      tags=("${SYNC_TAGS[@]}" "${UI_TAGS[@]}" "${ERROR_TAGS[@]}")
      ;;
    sync-only)
      tags=("${SYNC_TAGS[@]}" "${ERROR_TAGS[@]}")
      ;;
    errors-only)
      tags=("${ERROR_TAGS[@]}")
      ;;
    full)
      tags=("${SYNC_TAGS[@]}" "${UI_TAGS[@]}" "${AUTH_TAGS[@]}" "${SYSTEM_TAGS[@]}" "${ERROR_TAGS[@]}")
      ;;
    dump|*)
      tags=("${SYNC_TAGS[@]}" "${UI_TAGS[@]}" "${ERROR_TAGS[@]}")
      ;;
  esac

  # Convert to adb logcat filter format
  local filter=""
  for tag in "${tags[@]}"; do
    filter="$filter -s $tag"
  done
  echo "$filter"
}

# Ensure debug directory exists
mkdir -p "$DEBUG_DIR"

# Verify adb/device availability
ensure_device

# Main execution
case "$MODE" in
  live)
    echo "📱 Starting LIVE log capture..."
    echo "   Output: $OUTPUT_FILE"
    echo "   Tags: API, Sync, UI, Errors"
    echo "   Press Ctrl+C to stop"
    echo ""

    # Clear old logs
    $(adb_prefix) logcat -c

    # Start live capture with tee (shows on screen and saves to file)
    PIDF="$(resolve_pid_filter)"
    if [[ -n "$PIDF" ]]; then
      echo "   Using PID filter for $APP_ID: $PIDF"
    fi
    $(adb_prefix) logcat $PIDF $(build_filter) | tee "$OUTPUT_FILE"
    ;;

  sync-only)
    echo "📱 Capturing SYNC logs only..."
    $(adb_prefix) logcat -c
    echo "   Output: $OUTPUT_FILE"
    echo "   Capturing... Press Ctrl+C to stop"
    PIDF="$(resolve_pid_filter)"
    if [[ -n "$PIDF" ]]; then
      echo "   Using PID filter for $APP_ID: $PIDF"
    fi
    $(adb_prefix) logcat $PIDF $(build_filter) | tee "$OUTPUT_FILE"
    ;;

  errors-only)
    echo "🔴 Capturing ERRORS and WARNINGS only..."
    if [[ "$OUTPUT_FILE" == "$DEBUG_DIR/current_sync.logcat" ]]; then
      OUTPUT_FILE="$DEBUG_DIR/errors_${TIMESTAMP}.logcat"
    fi
    PIDF="$(resolve_pid_filter)"
    $(adb_prefix) logcat -d $PIDF $(build_filter) > "$OUTPUT_FILE"

    LINE_COUNT=$(wc -l < "$OUTPUT_FILE" | tr -d ' ')
    echo "   Saved $LINE_COUNT lines to: $OUTPUT_FILE"

    # Show summary
    ERROR_COUNT=$(grep -c " E " "$OUTPUT_FILE" || true)
    WARN_COUNT=$(grep -c " W " "$OUTPUT_FILE" || true)
    echo "   📊 Summary: $ERROR_COUNT errors, $WARN_COUNT warnings"

    # Show last 10 errors
    if [ "$ERROR_COUNT" -gt 0 ]; then
      echo ""
      echo "   Last 10 errors:"
      grep " E " "$OUTPUT_FILE" | tail -10 | sed 's/^/     /'
    fi
    ;;

  dump|*)
    echo "📱 Dumping existing logs..."
    echo "   Output: $OUTPUT_FILE"

    # Dump last N lines with filters
    PIDF="$(resolve_pid_filter)"
    if [[ -n "$PIDF" ]]; then
      echo "   Using PID filter for $APP_ID: $PIDF"
    fi
    $(adb_prefix) logcat -d -t "$LOG_LINES" $PIDF $(build_filter) > "$OUTPUT_FILE"

    LINE_COUNT=$(wc -l < "$OUTPUT_FILE" | tr -d ' ')
    echo "   ✅ Saved $LINE_COUNT lines"

    # Show summary of key events
    echo ""
    echo "   📊 Quick Summary:"
    echo "   - Sync starts:  $(grep -c "Starting sync" "$OUTPUT_FILE" || echo "0")"
    echo "   - Projects:     $(grep -c "Fetched.*projects" "$OUTPUT_FILE" || echo "0")"
    echo "   - Rooms:        $(grep -c "Saved.*rooms" "$OUTPUT_FILE" || echo "0")"
    echo "   - Photos:       $(grep -c "Saved.*photos" "$OUTPUT_FILE" || echo "0")"
    echo "   - Errors:       $(grep -c " E " "$OUTPUT_FILE" || echo "0")"
    echo "   - Warnings:     $(grep -c " W " "$OUTPUT_FILE" || echo "0")"

    # Show last 10 important lines
    echo ""
    echo "   🔍 Last 10 important events:"
    grep -E "✅|❌|⚠️|🔄|📸|🏠|📚|ERROR|WARN" "$OUTPUT_FILE" | tail -10 | sed 's/^/     /'
    ;;
esac

echo ""
echo "✅ Done!"
