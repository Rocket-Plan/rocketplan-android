#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

DEVICE_ID="${DEVICE_ID:-emulator-5554}"
PACKAGE="com.example.rocketplan_android.dev"
ACTIVITY="com.example.rocketplan_android.MainActivity"

# Defaults for QA login; override via env if needed.
RP_QA_EMAIL="${RP_QA_EMAIL:-jeremie@rocketplantech.com}"
RP_QA_PASSWORD="${RP_QA_PASSWORD:-studmuffin}"

PASSWORD_STEP_DELAY="${RP_QA_PASSWORD_STEP_DELAY:-0.2}"
LOGIN_WAIT="${RP_QA_LOGIN_WAIT:-2}"

log "Launching app"
adb -s "$DEVICE_ID" shell am start -n "$PACKAGE/$ACTIVITY"
sleep 1

log "Entering email"
# Tap email field, clear, and type email
adb -s "$DEVICE_ID" shell input tap 540 1606
adb -s "$DEVICE_ID" shell input keyevent KEYCODE_MOVE_END
# Clear a generous number of characters
adb -s "$DEVICE_ID" shell "input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67;"
adb -s "$DEVICE_ID" shell input text "$RP_QA_EMAIL"

log "Continue to password screen"
# Continue to password screen
adb -s "$DEVICE_ID" shell input tap 540 2022
sleep "$LOGIN_WAIT"

log "Entering password"
# Tap password field, clear, and type password character-by-character
adb -s "$DEVICE_ID" shell input tap 540 815
adb -s "$DEVICE_ID" shell input keyevent KEYCODE_MOVE_END
adb -s "$DEVICE_ID" shell "input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67; input keyevent 67;"

for ((i = 0; i < ${#RP_QA_PASSWORD}; i++)); do
  ch="${RP_QA_PASSWORD:i:1}"
  adb -s "$DEVICE_ID" shell input text "$ch"
  sleep "$PASSWORD_STEP_DELAY"
done

log "Tap Sign In"
# Tap Sign In
adb -s "$DEVICE_ID" shell input tap 540 1247

sleep "$LOGIN_WAIT"

log "Login complete"
