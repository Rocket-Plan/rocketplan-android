#!/bin/bash
# Auto-start log capture after app launches
# Usage: ./scripts/auto-log.sh [mode]
# Modes: live (default), dump, sync-only, errors-only, full

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
DEBUG_DIR="$PROJECT_DIR/debug"
MODE="${1:-live}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Archive old logs if they exist
if [[ -f "$DEBUG_DIR/current_sync.logcat" ]]; then
    mv "$DEBUG_DIR/current_sync.logcat" "$DEBUG_DIR/archive_${TIMESTAMP}.logcat"
    echo "📦 Archived old logs to archive_${TIMESTAMP}.logcat"
fi

# Detect which device the app is running on
echo "⏳ Detecting device..."
APP_ID="com.example.rocketplan_android.dev"

# Get all connected devices
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
DEVICE_COUNT=$(echo "$DEVICES" | wc -l | tr -d ' ')

if [[ $DEVICE_COUNT -eq 0 ]]; then
    echo "❌ No devices connected"
    exit 1
fi

# If multiple devices, find which one has the app running
SELECTED_DEVICE=""
if [[ $DEVICE_COUNT -gt 1 ]]; then
    echo "📱 Found $DEVICE_COUNT devices, checking which one is running the app..."
    for device in $DEVICES; do
        PID=$(adb -s "$device" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || echo "")
        if [[ -n "$PID" ]]; then
            SELECTED_DEVICE="$device"
            echo "✅ Found app running on device: $SELECTED_DEVICE (PID: $PID)"
            break
        fi
    done

    if [[ -z "$SELECTED_DEVICE" ]]; then
        echo "⏳ App not running yet, waiting 5 seconds..."
        sleep 5
        for device in $DEVICES; do
            PID=$(adb -s "$device" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || echo "")
            if [[ -n "$PID" ]]; then
                SELECTED_DEVICE="$device"
                echo "✅ Found app running on device: $SELECTED_DEVICE (PID: $PID)"
                break
            fi
        done
    fi
else
    # Single device
    SELECTED_DEVICE=$(echo "$DEVICES" | head -1)
    echo "📱 Using device: $SELECTED_DEVICE"

    # Wait for app to launch
    sleep 3
    PID=$(adb -s "$SELECTED_DEVICE" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || echo "")
fi

if [[ -z "$SELECTED_DEVICE" ]]; then
    echo "❌ Could not find device running app '$APP_ID'"
    exit 1
fi

if [[ -z "$PID" ]]; then
    PID=$(adb -s "$SELECTED_DEVICE" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || echo "")
fi

if [[ -z "$PID" ]]; then
    echo "❌ App '$APP_ID' is not running on $SELECTED_DEVICE"
    exit 1
fi

echo "✅ App running with PID: $PID on $SELECTED_DEVICE"
echo "🎬 Starting log capture in $MODE mode..."
echo ""

# Start the log capture with the selected device
env APP_ID="$APP_ID" USE_PID=true ADB_SERIAL="$SELECTED_DEVICE" bash "$SCRIPT_DIR/capture-logs.sh" "$MODE"
