#!/bin/bash
#
# Watch property sync logs in real-time
# Usage: ./watch-property-sync.sh
#

echo "Clearing log buffer..."
adb logcat -c

echo ""
echo "Watching property-related sync logs (Press Ctrl+C to stop)..."
echo "============================================================"
echo ""

adb logcat -s API:D | grep --line-buffered -E "Property|🏠|fetchProjectProperty|LossInfo"
