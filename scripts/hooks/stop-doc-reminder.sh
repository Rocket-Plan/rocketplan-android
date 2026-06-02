#!/bin/bash
# Stop-hook: nudge if Kotlin changed in this session without any docs updates.
# Soft reminder only — exits 0 so the turn never blocks. False positives
# (refactors / tests / build-config) are accepted; the alternative is silently
# forgetting the bug-doc lifecycle.

set +e
cd "$CLAUDE_PROJECT_DIR" 2>/dev/null || cd "$(dirname "$0")/../.." || exit 0

kt=$(git diff --name-only HEAD 2>/dev/null | grep -c 'app/src/.*\.kt$')
docs=$(git diff --name-only HEAD 2>/dev/null | grep -c 'docs/.*\.md$')

if [ "${kt:-0}" -gt 0 ] && [ "${docs:-0}" -eq 0 ]; then
  printf '{"systemMessage":"Reminder: %s Kotlin file(s) changed this session without docs updates — if this changes bug/feature behaviour, update the matching investigation/plan/tracker doc per CLAUDE.md before commit."}' "$kt"
fi

exit 0
