#!/bin/bash
# ═══════════════════════════════════════════════════════
#  Full Load Test — k6 + delivery verification
#
#  Runs the k6 load test, then waits for all notifications
#  to be delivered before reporting final results.
#
#  Usage: bash tests/load/run-load-test.sh
# ═══════════════════════════════════════════════════════

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo ""
echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo -e "${CYAN}  Full Load Test (k6 + delivery check)${NC}"
echo -e "${CYAN}═══════════════════════════════════════════${NC}"

# ── Detect hardware ──
OS_INFO=$(sw_vers 2>/dev/null | head -2 | tr '\n' ' ' || uname -s -r)
CPU_INFO=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || lscpu 2>/dev/null | grep "Model name" | cut -d: -f2 | tr -d ' ' || echo "unknown")
RAM_GB=$(python3 -c "import os; print(round(os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES') / (1024**3), 1))" 2>/dev/null || echo "unknown")
HOSTNAME_VAL=$(hostname -s 2>/dev/null || echo "unknown")

echo ""
echo -e "  ${YELLOW}Environment${NC}"
echo -e "  Host:  $HOSTNAME_VAL"
echo -e "  OS:    $OS_INFO"
echo -e "  CPU:   $CPU_INFO"
echo -e "  RAM:   ${RAM_GB} GB"

# Snapshot notification count and timestamp before test
BEFORE=$(docker exec ndp-postgres psql -U dev notification_platform -t -c \
    "SELECT COUNT(*) FROM notifications;" 2>/dev/null | tr -d ' ')
# Capture the DB server's current time (avoids timezone mismatch between host and container)
TEST_START=$(docker exec ndp-postgres psql -U dev notification_platform -t -c \
    "SELECT now();" 2>/dev/null | xargs)
echo -e "  Notifications before test: ${YELLOW}$BEFORE${NC}"
echo -e "  Test start (DB clock): ${YELLOW}$TEST_START${NC}"

# ── Run k6 with hardware info as env vars ──
echo ""
echo -e "${CYAN}  Running k6 load test...${NC}"
echo ""
HOSTNAME="$HOSTNAME_VAL" OS_INFO="$OS_INFO" CPU_INFO="$CPU_INFO" RAM_GB="$RAM_GB" \
    k6 run tests/load/load-test.js

# ── Wait for delivery ──
echo ""
echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo -e "${CYAN}  Waiting for delivery to complete...${NC}"
echo -e "${CYAN}═══════════════════════════════════════════${NC}"

MAX_WAIT=400
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    QUEUED=$(docker exec ndp-postgres psql -U dev notification_platform -t -c \
        "SELECT COUNT(*) FROM notifications WHERE status IN ('ACCEPTED', 'QUEUED', 'PROCESSING') AND created_at >= '$TEST_START';" 2>/dev/null | tr -d ' ')
    
    if [ "$QUEUED" = "0" ]; then
        echo -e "  ${GREEN}All notifications processed!${NC} (waited ${WAITED}s)"
        break
    fi
    
    echo -e "  Still processing: ${YELLOW}$QUEUED${NC} notifications... (${WAITED}s)"
    sleep 3
    WAITED=$((WAITED + 3))
done

if [ $WAITED -ge $MAX_WAIT ]; then
    echo -e "  ${RED}Timeout — $QUEUED notifications still not delivered after ${MAX_WAIT}s${NC}"
fi

# ── Final stats ──
echo ""
echo -e "${CYAN}═══════════════════════════════════════════${NC}"
echo -e "${CYAN}  Delivery Results${NC}"
echo -e "${CYAN}═══════════════════════════════════════════${NC}"

AFTER=$(docker exec ndp-postgres psql -U dev notification_platform -t -c \
    "SELECT COUNT(*) FROM notifications;" 2>/dev/null | tr -d ' ')
NEW=$((AFTER - BEFORE))

# Use the captured start timestamp instead of a relative interval
STATS=$(docker exec ndp-postgres psql -U dev notification_platform -t -c \
    "SELECT status, COUNT(*) FROM notifications WHERE created_at >= '$TEST_START' GROUP BY status ORDER BY status;" 2>/dev/null)

echo -e "  New notifications: ${YELLOW}$NEW${NC}"
echo ""
echo "$STATS" | while read -r line; do
    if [ -n "$line" ]; then
        STATUS=$(echo "$line" | awk -F'|' '{print $1}' | tr -d ' ')
        COUNT=$(echo "$line" | awk -F'|' '{print $2}' | tr -d ' ')
        if [ "$STATUS" = "DELIVERED" ]; then
            echo -e "  ${GREEN}$STATUS: $COUNT${NC}"
        elif [ "$STATUS" = "FAILED" ]; then
            echo -e "  ${RED}$STATUS: $COUNT${NC}"
        else
            echo -e "  ${YELLOW}$STATUS: $COUNT${NC}"
        fi
    fi
done

# Calculate delivery rate
DELIVERED=$(docker exec ndp-postgres psql -U dev notification_platform -t -c \
    "SELECT COUNT(*) FROM notifications WHERE status = 'DELIVERED' AND created_at >= '$TEST_START';" 2>/dev/null | tr -d ' ')

if [ "$NEW" -gt 0 ]; then
    RATE=$(python3 -c "print(f'{($DELIVERED / $NEW) * 100:.2f}')")
    echo ""
    echo -e "  Delivery rate: ${GREEN}${RATE}%${NC} ($DELIVERED / $NEW)"
fi

echo ""

