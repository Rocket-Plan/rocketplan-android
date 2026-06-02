#!/usr/bin/env bash
# Record a production fastlane deploy so BUG_TRACKER.md bookkeeping can be
# audited later. Invoked from `fastlane deploy_production` after the Play Store
# upload.
#
# Appends one line to docs/PROD_DEPLOY_LOG.md with timestamp, marketing
# version, build number, git HEAD, and any tag pointing at HEAD. The
# "Post-production-release bug-doc bookkeeping" rule reads this file to flip
# RP-BUG-* docs from unreleased → released (scripts/flip_released_bugs.py).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

LOG="docs/PROD_DEPLOY_LOG.md"

# Version lives in app/build.gradle.kts:
#   val buildNumber = 32
#   versionName = "1.29 ($buildNumber)"
# Parse the marketing version (the literal before the parenthesised build) and
# the build number separately. sed strips quotes/whitespace so a stray value
# can never leak into the log row.
GRADLE="$REPO_ROOT/app/build.gradle.kts"
BUILD=$(grep -m1 -E 'val[[:space:]]+buildNumber[[:space:]]*=' "$GRADLE" \
  | sed -E 's/.*=[[:space:]]*//; s/[^0-9].*$//' || echo "unknown")
# versionName "1.29 ($buildNumber)" -> marketing = "1.29"
MARKETING=$(grep -m1 -E 'versionName[[:space:]]*=' "$GRADLE" \
  | sed -E 's/.*versionName[[:space:]]*=[[:space:]]*"//; s/[[:space:]]*\(.*$//; s/".*$//; s/[[:space:]]*$//' \
  || echo "unknown")
MARKETING="${MARKETING:-unknown}"
BUILD="${BUILD:-unknown}"
HEAD_SHA=$(git rev-parse --short HEAD)
TAG=$(git tag --points-at HEAD | head -1)
TIMESTAMP=$(date "+%Y-%m-%d %H:%M:%S %Z")

if [ ! -f "$LOG" ]; then
  mkdir -p "$(dirname "$LOG")"
  cat > "$LOG" <<EOF
# Production Deploy Log

Auto-appended by \`fastlane deploy_production\` via \`scripts/record_prod_deploy.sh\`.
Each row is one Play Store upload of the production build.

| Timestamp | Marketing | Build | HEAD | Tag |
|-----------|-----------|-------|------|-----|
EOF
fi

printf "| %s | %s | %s | \`%s\` | %s |\n" \
  "$TIMESTAMP" "$MARKETING" "$BUILD" "$HEAD_SHA" "${TAG:-—}" \
  >> "$LOG"

echo "Recorded prod deploy ${MARKETING}+${BUILD} (HEAD ${HEAD_SHA}) -> $LOG"
echo "   Reminder: flip released bug docs (scripts/flip_released_bugs.py --apply)."
