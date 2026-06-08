#!/usr/bin/env bash
# RP-BUG-036/037/038, RP-FR-005/006 — duplicate-on-refresh observability.
#
# Pulls the Room DB off a connected device and flags the *exact* bug signature: a single server
# entity (one serverId) materialized as MORE THAN ONE local row. That happens when an offline-created
# row (client uuid, serverId=null) is reconciled against a pulled row (server uuid, same serverId) by
# PK only — the duplicate the mergePulledRowsByServerId fix is meant to prevent.
#
# Usage: scripts/check_sync_duplicates.sh [ADB_SERIAL]
# Run it after a create-offline -> sync -> pull-refresh cycle. ZERO duplicate rows = fix holding.

set -euo pipefail
SERIAL="${1:-30407ef}"
PKG="com.rocketplantech.rocketplan"
ADB="adb -s $SERIAL"
WORK="$(mktemp -d)"
DB="$WORK/rocketplan_offline.db"

for f in rocketplan_offline.db rocketplan_offline.db-wal rocketplan_offline.db-shm; do
  $ADB exec-out run-as "$PKG" cat "databases/$f" > "$WORK/$f" 2>/dev/null || true
done
[ -s "$DB" ] || { echo "ERROR: could not pull DB (device $SERIAL, pkg $PKG installed & debuggable?)"; exit 1; }

echo "DB pulled: $(stat -f%z "$DB" 2>/dev/null || stat -c%s "$DB") bytes  (integrity: $(sqlite3 "$DB" 'PRAGMA integrity_check;' | head -1))"
echo

# table:label pairs for every reconcile-by-serverId entity
TABLES="offline_notes:Notes offline_equipment:Equipment offline_moisture_logs:MoistureLogs \
offline_atmospheric_logs:AtmosphericLogs offline_materials:Materials \
offline_support_conversations:SupportConversations offline_support_messages:SupportMessages \
offline_support_message_attachments:SupportAttachments offline_timecards:Timecards"

fail=0
printf "%-22s %8s %8s %12s %10s\n" "ENTITY" "rows" "w/srvId" "dupSrvIds" "dupUuids"
printf -- "----------------------------------------------------------------------\n"
for pair in $TABLES; do
  t="${pair%%:*}"; label="${pair##*:}"
  exists=$(sqlite3 "$DB" "SELECT name FROM sqlite_master WHERE type='table' AND name='$t';" 2>/dev/null || true)
  [ -n "$exists" ] || continue
  rows=$(sqlite3 "$DB" "SELECT COUNT(*) FROM $t;")
  withsid=$(sqlite3 "$DB" "SELECT COUNT(*) FROM $t WHERE serverId > 0;")
  # the bug signature: one REAL serverId (>0), multiple local rows. serverId=0/NULL is the
  # unsynced sentinel, not a reconcile-duplicate, so it's excluded.
  dupsid=$(sqlite3 "$DB" "SELECT COUNT(*) FROM (SELECT serverId FROM $t WHERE serverId > 0 GROUP BY serverId HAVING COUNT(*) > 1);")
  # secondary signal: duplicate uuid (should be impossible if unique index holds)
  hasuuid=$(sqlite3 "$DB" "PRAGMA table_info($t);" | awk -F'|' '{print $2}' | grep -cx uuid || true)
  if [ "$hasuuid" -gt 0 ]; then
    dupuuid=$(sqlite3 "$DB" "SELECT COUNT(*) FROM (SELECT uuid FROM $t WHERE uuid IS NOT NULL AND uuid <> '' GROUP BY uuid HAVING COUNT(*) > 1);")
  else
    dupuuid="-"
  fi
  printf "%-22s %8s %8s %12s %10s\n" "$label" "$rows" "$withsid" "$dupsid" "$dupuuid"
  if [ "$dupsid" -gt 0 ] || { [ "$dupuuid" != "-" ] && [ "$dupuuid" -gt 0 ]; }; then
    fail=1
    echo "  ⚠️  $label has duplicates — offending serverIds:"
    sqlite3 -column "$DB" "SELECT serverId, COUNT(*) AS n FROM $t WHERE serverId > 0 GROUP BY serverId HAVING n > 1 LIMIT 10;" | sed 's/^/      /'
  fi
done

echo
if [ "$fail" -eq 0 ]; then
  echo "✅ PASS — no duplicate-on-refresh signature in any reconcilable table."
else
  echo "❌ FAIL — duplicate rows found (see ⚠️ above): one real serverId materialized as multiple local"
  echo "   rows. Either a reconcile-by-serverId gap (RP-BUG-036/037/038 class) or multiple offline-created"
  echo "   rows collapsing to one server entity that were never deduped locally. Investigate the serverIds."
fi
rm -rf "$WORK"
exit $fail
