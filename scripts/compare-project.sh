#!/bin/bash
#
# Compare project data between FLIR device and QA database
# Usage: ./compare-project.sh <project_number>
# Example: ./compare-project.sh RP-25-1001
#

set -e

PROJECT_NUMBER="${1:-RP-25-1001}"
TEMP_DIR="/tmp/rocketplan_compare"
DEVICE_DB="$TEMP_DIR/rocketplan_offline.db"
APP_PACKAGE="com.example.rocketplan_android.dev"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Project Comparison Tool${NC}"
echo -e "${BLUE}  Project: ${PROJECT_NUMBER}${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Create temp directory
mkdir -p "$TEMP_DIR"

# Check device connection
echo -e "${YELLOW}[1/5] Checking device connection...${NC}"
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}ERROR: No device connected${NC}"
    exit 1
fi
DEVICE_NAME=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo -e "${GREEN}Connected to: $DEVICE_NAME${NC}"
echo ""

# Pull database from device
echo -e "${YELLOW}[2/5] Pulling database from device...${NC}"
adb exec-out run-as "$APP_PACKAGE" cat databases/rocketplan_offline.db > "$DEVICE_DB" 2>/dev/null
if [ ! -s "$DEVICE_DB" ]; then
    echo -e "${RED}ERROR: Failed to pull database${NC}"
    exit 1
fi
echo -e "${GREEN}Database pulled successfully ($(du -h "$DEVICE_DB" | cut -f1))${NC}"
echo ""

# Query device database
echo -e "${YELLOW}[3/5] Querying device database...${NC}"
DEVICE_PROJECT=$(sqlite3 -json "$DEVICE_DB" "
    SELECT
        p.projectId,
        p.serverId,
        p.uuid,
        p.title,
        p.projectNumber,
        p.addressLine1,
        p.addressLine2,
        p.propertyId,
        p.status,
        p.syncStatus
    FROM offline_projects p
    WHERE p.uuid LIKE '%${PROJECT_NUMBER}%'
       OR p.projectNumber LIKE '%${PROJECT_NUMBER}%'
    LIMIT 1;
" 2>/dev/null)

if [ -z "$DEVICE_PROJECT" ] || [ "$DEVICE_PROJECT" = "[]" ]; then
    echo -e "${RED}ERROR: Project not found on device${NC}"
    exit 1
fi

# Extract values from device
DEVICE_SERVER_ID=$(echo "$DEVICE_PROJECT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['serverId'] if d else '')")
DEVICE_PROPERTY_ID=$(echo "$DEVICE_PROJECT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['propertyId'] if d else '')")
DEVICE_TITLE=$(echo "$DEVICE_PROJECT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['title'] if d else '')")
DEVICE_ADDR1=$(echo "$DEVICE_PROJECT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['addressLine1'] or '' if d else '')")
DEVICE_ADDR2=$(echo "$DEVICE_PROJECT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0]['addressLine2'] or '' if d else '')")

echo -e "${GREEN}Found project on device: serverId=$DEVICE_SERVER_ID, propertyId=$DEVICE_PROPERTY_ID${NC}"

# Query device property - try by propertyId first, then by address match
DEVICE_PROPERTY=$(sqlite3 -json "$DEVICE_DB" "
    SELECT * FROM offline_properties WHERE serverId = $DEVICE_PROPERTY_ID;
" 2>/dev/null)
# If not found by propertyId, try matching by address
if [ -z "$DEVICE_PROPERTY" ] || [ "$DEVICE_PROPERTY" = "[]" ]; then
    DEVICE_PROPERTY=$(sqlite3 -json "$DEVICE_DB" "
        SELECT * FROM offline_properties WHERE address LIKE '%${DEVICE_ADDR1}%' LIMIT 1;
    " 2>/dev/null)
fi

echo ""

# Query QA database
echo -e "${YELLOW}[4/5] Querying QA database...${NC}"
QA_PROJECT=$(mysql --defaults-group-suffix=qa -N -e "
    SELECT id, uid, alias, address_id
    FROM projects
    WHERE id = $DEVICE_SERVER_ID;
" 2>/dev/null)

if [ -z "$QA_PROJECT" ]; then
    echo -e "${RED}ERROR: Project not found in QA database${NC}"
    exit 1
fi

QA_ADDRESS_ID=$(echo "$QA_PROJECT" | awk '{print $4}')

QA_ADDRESS=$(mysql --defaults-group-suffix=qa -N -e "
    SELECT address, address_2, city, state, zip, country, latitude, longitude
    FROM addresses
    WHERE id = $QA_ADDRESS_ID;
" 2>/dev/null)

QA_ADDR=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $1}')
QA_ADDR2=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $2}')
QA_CITY=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $3}')
QA_STATE=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $4}')
QA_ZIP=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $5}')
QA_COUNTRY=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $6}')
QA_LAT=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $7}')
QA_LNG=$(echo "$QA_ADDRESS" | awk -F'\t' '{print $8}')

echo -e "${GREEN}Found project in QA: address_id=$QA_ADDRESS_ID${NC}"
echo ""

# Compare and display results
echo -e "${YELLOW}[5/5] Comparing data...${NC}"
echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                           COMPARISON RESULTS                                  ║${NC}"
echo -e "${BLUE}╠══════════════════════════════════════════════════════════════════════════════╣${NC}"

printf "${BLUE}║${NC} %-20s ${BLUE}│${NC} %-25s ${BLUE}│${NC} %-25s ${BLUE}║${NC}\n" "Field" "Device" "QA Database"
echo -e "${BLUE}╠══════════════════════════════════════════════════════════════════════════════╣${NC}"

# Compare each field
compare_field() {
    local field_name="$1"
    local device_val="$2"
    local qa_val="$3"

    if [ "$device_val" = "$qa_val" ]; then
        status="${GREEN}✓${NC}"
    elif [ -z "$device_val" ] && [ -n "$qa_val" ]; then
        status="${RED}✗ MISSING${NC}"
    else
        status="${YELLOW}≠${NC}"
    fi

    # Truncate long values
    device_display="${device_val:0:23}"
    qa_display="${qa_val:0:23}"

    printf "${BLUE}║${NC} %-20s ${BLUE}│${NC} %-25s ${BLUE}│${NC} %-25s ${BLUE}║${NC} %b\n" \
        "$field_name" "${device_display:-<empty>}" "${qa_display:-<empty>}" "$status"
}

compare_field "Address" "$DEVICE_ADDR1" "$QA_ADDR"
compare_field "Address 2" "$DEVICE_ADDR2" "$QA_ADDR2"

# Get property details from device
DEVICE_PROP_ADDR=""
DEVICE_PROP_CITY=""
DEVICE_PROP_STATE=""
DEVICE_PROP_ZIP=""
DEVICE_PROP_LAT=""
DEVICE_PROP_LNG=""

if [ -n "$DEVICE_PROPERTY" ] && [ "$DEVICE_PROPERTY" != "[]" ]; then
    DEVICE_PROP_ADDR=$(echo "$DEVICE_PROPERTY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0].get('address','') or '' if d else '')" 2>/dev/null)
    DEVICE_PROP_CITY=$(echo "$DEVICE_PROPERTY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0].get('city','') or '' if d else '')" 2>/dev/null)
    DEVICE_PROP_STATE=$(echo "$DEVICE_PROPERTY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0].get('state','') or '' if d else '')" 2>/dev/null)
    DEVICE_PROP_ZIP=$(echo "$DEVICE_PROPERTY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0].get('zipCode','') or '' if d else '')" 2>/dev/null)
    DEVICE_PROP_LAT=$(echo "$DEVICE_PROPERTY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0].get('latitude','') or '' if d else '')" 2>/dev/null)
    DEVICE_PROP_LNG=$(echo "$DEVICE_PROPERTY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d[0].get('longitude','') or '' if d else '')" 2>/dev/null)
fi

echo -e "${BLUE}╠══════════════════════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BLUE}║${NC}  ${YELLOW}Property Table (offline_properties)${NC}                                         ${BLUE}║${NC}"
echo -e "${BLUE}╠══════════════════════════════════════════════════════════════════════════════╣${NC}"

compare_field "Property Address" "$DEVICE_PROP_ADDR" "$QA_ADDR"
compare_field "City" "$DEVICE_PROP_CITY" "$QA_CITY"
compare_field "State" "$DEVICE_PROP_STATE" "$QA_STATE"
compare_field "Zip" "$DEVICE_PROP_ZIP" "$QA_ZIP"
compare_field "Latitude" "$DEVICE_PROP_LAT" "$QA_LAT"
compare_field "Longitude" "$DEVICE_PROP_LNG" "$QA_LNG"

echo -e "${BLUE}╚══════════════════════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Summary
echo -e "${BLUE}Summary:${NC}"
ISSUES=0

if [ -z "$DEVICE_PROPERTY" ] || [ "$DEVICE_PROPERTY" = "[]" ]; then
    echo -e "  ${RED}✗ Property row MISSING from device (serverId=$DEVICE_PROPERTY_ID)${NC}"
    ISSUES=$((ISSUES + 1))
fi

if [ -z "$DEVICE_PROP_CITY" ] && [ -n "$QA_CITY" ]; then
    echo -e "  ${RED}✗ City not synced to device${NC}"
    ISSUES=$((ISSUES + 1))
fi

if [ -z "$DEVICE_PROP_STATE" ] && [ -n "$QA_STATE" ]; then
    echo -e "  ${RED}✗ State not synced to device${NC}"
    ISSUES=$((ISSUES + 1))
fi

if [ -z "$DEVICE_PROP_LAT" ] && [ -n "$QA_LAT" ]; then
    echo -e "  ${RED}✗ Coordinates not synced to device${NC}"
    ISSUES=$((ISSUES + 1))
fi

if [ $ISSUES -eq 0 ]; then
    echo -e "  ${GREEN}✓ All data matches!${NC}"
else
    echo -e "\n  ${YELLOW}Total issues found: $ISSUES${NC}"
fi

echo ""
echo -e "${BLUE}Device DB saved to: $DEVICE_DB${NC}"
