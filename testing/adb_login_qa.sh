#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

DEVICE_ID="${DEVICE_ID:-emulator-5554}"
PACKAGE="com.example.rocketplan_android.dev"
ACTIVITY="com.example.rocketplan_android.MainActivity"

: "${RP_QA_EMAIL:?Set RP_QA_EMAIL before running}"
: "${RP_QA_PASSWORD:?Set RP_QA_PASSWORD before running}"

PROJECT_STREET="${RP_QA_PROJECT_STREET:-2 Codex Way}"
PROJECT_CITY="${RP_QA_PROJECT_CITY:-Cleveland}"
PROJECT_STATE="${RP_QA_PROJECT_STATE:-OH}"
PROJECT_COUNTRY="${RP_QA_PROJECT_COUNTRY:-USA}"
PROJECT_POSTAL="${RP_QA_PROJECT_POSTAL:-44114}"

STEP_DELAY="${RP_QA_STEP_DELAY:-2}"
PASSWORD_STEP_DELAY="${RP_QA_PASSWORD_STEP_DELAY:-0.2}"
LOGIN_WAIT="${RP_QA_LOGIN_WAIT:-2}"
NAV_WAIT="${RP_QA_NAV_WAIT:-2}"
STOP_AFTER_LOGIN="${RP_QA_STOP_AFTER_LOGIN:-false}"

street_escaped="${PROJECT_STREET// /%s}"
city_escaped="${PROJECT_CITY// /%s}"
country_escaped="${PROJECT_COUNTRY// /%s}"

log "Launching app"
adb -s "$DEVICE_ID" shell am start -n "$PACKAGE/$ACTIVITY"

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

if [[ "$STOP_AFTER_LOGIN" == "true" ]]; then
  log "Stopping after login (RP_QA_STOP_AFTER_LOGIN=true)"
  exit 0
fi

log "Go to Projects tab"
adb -s "$DEVICE_ID" shell input tap 405 2300
sleep "$NAV_WAIT"

log "Tap New Project FAB"
# Create a new project from the projects list (FAB)
adb -s "$DEVICE_ID" shell input tap 943 2053
sleep "$NAV_WAIT"

log "Fill manual address"
# Manual address entry screen
adb -s "$DEVICE_ID" shell input tap 540 490
adb -s "$DEVICE_ID" shell input text "$street_escaped"
adb -s "$DEVICE_ID" shell input tap 540 852
adb -s "$DEVICE_ID" shell input text "$city_escaped"
adb -s "$DEVICE_ID" shell input tap 540 1034
adb -s "$DEVICE_ID" shell input text "$PROJECT_STATE"
adb -s "$DEVICE_ID" shell input tap 540 1216
adb -s "$DEVICE_ID" shell input text "$country_escaped"
adb -s "$DEVICE_ID" shell input tap 540 1400
adb -s "$DEVICE_ID" shell input text "$PROJECT_POSTAL"
adb -s "$DEVICE_ID" shell input tap 540 1607

log "Select property type: Single Unit"
# Property type selection: Single Unit
sleep "$NAV_WAIT"
adb -s "$DEVICE_ID" shell input tap 540 679

log "Delete project via menu"
# Open project menu and delete project
sleep "$NAV_WAIT"
adb -s "$DEVICE_ID" shell input tap 922 410
sleep "$STEP_DELAY"
adb -s "$DEVICE_ID" shell input tap 820 525
sleep "$STEP_DELAY"
adb -s "$DEVICE_ID" shell input tap 884 1325
