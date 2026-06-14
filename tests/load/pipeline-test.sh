#!/bin/bash
# ═══════════════════════════════════════════════════════
#  Pipeline Performance Test
#
#  Runs a load test and monitors every stage of the 
#  notification pipeline in real-time.
#
#  Usage: bash tests/load/pipeline-test.sh
# ═══════════════════════════════════════════════════════

# ── Colors ──
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ── Config ──
DB_CONTAINER="ndp-postgres"
DB_USER="dev"
DB_NAME="notification_platform"
POLL_INTERVAL=10

# Docker SQL query helper method
db_query() {
    local result
    result=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" "$DB_NAME" -t -c "$1" 2>/dev/null | xargs)
    echo "${result:-0}"  # default to 0 if empty (prevents arithmetic errors)
}

# Helper: query k6 REST API for a live metric count (returns 0 if k6 not running)
k6_metric_count() {
    local result
    result=$(curl -s "http://localhost:6565/v1/metrics/$1" 2>/dev/null \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['data']['attributes']['sample']['count']))" 2>/dev/null)
    echo "${result:-0}"
}

# ═══════════════════════════════════════════════════════
#  PHASE 1: PRE-FLIGHT
# ═══════════════════════════════════════════════════════
echo "" 
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  Pipeline Performance Test${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"

# Detect hardware
OS_INFO=$(sw_vers 2>/dev/null | head -2 | tr '\n' ' ' || uname -s -r)
CPU_INFO=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo "unknown")
RAM_GB=$(python3 -c "import os; print(round(os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES') / (1024**3), 1))" 2>/dev/null || echo "?")

echo ""
echo -e "  ${YELLOW}Environment${NC}"
echo -e "  Host:  $(hostname -s 2>/dev/null)"
echo -e "  CPU:   $CPU_INFO"
echo -e "  RAM:   ${RAM_GB} GB"
echo -e "  OS:    $OS_INFO"

# Check health
HEALTH=$(curl -s http://localhost:8080/actuator/health | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','?'))" 2>/dev/null)
if [ "$HEALTH" = "UP" ]; then
    echo -e "  Health: ${GREEN}UP${NC}"
else
    echo -e "  Health: ${RED}$HEALTH${NC} — is the app running?"
    exit 1
fi

# Snapshot: how many notifications exist before the test
BEFORE_COUNT=$(db_query "SELECT COUNT(*) FROM notifications;")
TEST_START=$(db_query "SELECT now();")
echo ""
echo -e "  Notifications before test: $BEFORE_COUNT"
echo -e "  Test start (DB clock):     $TEST_START"

# ═══════════════════════════════════════════════════════
#  PHASE 2: LIVE PIPELINE MONITOR
# ═══════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  Starting k6 load test + live pipeline monitor${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"
echo ""

# Run k6 in background, capture its output to a temp file
K6_OUTPUT=$(mktemp)
k6 run --address localhost:6565 tests/load/load-test.js > "$K6_OUTPUT" 2>&1 &
K6_PID=$!

# Track previous delivered count to calculate throughput
PREV_DELIVERED=0

printf "Time: elapsed time since start of test\n"
printf "Accepted: messaged with status accepted\n"
printf "Queued: messaged with status queued or retry scheduled\n"
printf "Processing: worker processes the queued message\n"
printf "Delivered: worker delivered the message to the recipient\n"
printf "Failed: worker failed to deliver the message\n"

# Print header for the live monitor
printf "  ${YELLOW}%-6s │ %-8s │ %-8s │ %-8s │ %-8s │ %-10s │ %-10s │ %-7s${NC}\n" \
    "Time" "Sent" "Accepted" "Queued" "Proc." "Delivered" "Throughput" "Failed"
echo -e "  ${YELLOW}───────┼──────────┼──────────┼──────────┼──────────┼────────────┼────────────┼────────${NC}"


ELAPSED=0
while true; do
    sleep "$POLL_INTERVAL"
    ELAPSED=$((ELAPSED + POLL_INTERVAL))

    # Get total HTTP requests sent by k6 (from k6 REST API)
    SENT=$(k6_metric_count "http_reqs")

    # Count notifications by status (only from this test)
    ACCEPTED=$(db_query "SELECT COUNT(*) FROM notifications WHERE status = 'ACCEPTED' AND created_at >= '$TEST_START';")
    QUEUED=$(db_query "SELECT COUNT(*) FROM notifications WHERE status IN ('QUEUED', 'RETRY_SCHEDULED') AND created_at >= '$TEST_START';")
    PROCESSING=$(db_query "SELECT COUNT(*) FROM notifications WHERE status = 'PROCESSING' AND created_at >= '$TEST_START';")
    DELIVERED=$(db_query "SELECT COUNT(*) FROM notifications WHERE status = 'DELIVERED' AND created_at >= '$TEST_START';")
    FAILED=$(db_query "SELECT COUNT(*) FROM notifications WHERE status = 'FAILED' AND created_at >= '$TEST_START';")

    # Calculate throughput (deliveries per second since last check)
    NEW_DELIVERED=$((DELIVERED - PREV_DELIVERED))
    THROUGHPUT=$(python3 -c "print(f'{$NEW_DELIVERED / $POLL_INTERVAL:.0f}/s')" 2>/dev/null)
    PREV_DELIVERED=$DELIVERED

    # Color the failed count
    if [ "$FAILED" -gt 0 ] 2>/dev/null; then
        FAILED_DISPLAY="${RED}$FAILED${NC}"
    else
        FAILED_DISPLAY="${GREEN}$FAILED${NC}"
    fi

    # Print pipeline snapshot
    printf "  %-6s │ %-8s │ %-8s │ %-8s │ %-8s │ %-10s │ %-10s │ " \
        "${ELAPSED}s" "$SENT" "$ACCEPTED" "$QUEUED" "$PROCESSING" "$DELIVERED" "$THROUGHPUT"
    echo -e "$FAILED_DISPLAY"

    # Check if we're done: k6 finished AND all notifications processed
    # Only count truly in-progress statuses (FAILED is terminal, not pending)
    STILL_PENDING=$((ACCEPTED + QUEUED + PROCESSING))
    K6_RUNNING=true
    # kill sends signal 0 to check if process (k6 test in this case) is running, 
    # if process is not running, it will exit
    if ! kill -0 "$K6_PID" 2>/dev/null; then 
        K6_RUNNING=false
    fi

    # if k6 is not running and all notifications are processed, break the loop
    if [ "$K6_RUNNING" = false ] && [ "$STILL_PENDING" -eq 0 ] 2>/dev/null; then
        echo ""
        echo -e "  ${GREEN}✅ All notifications delivered!${NC} (${ELAPSED}s total)"
        break
    fi

    # Safety timeout: 10 minutes
    if [ "$ELAPSED" -ge 600 ]; then
        echo ""
        echo -e "  ${RED}⏰ Timeout after ${ELAPSED}s — $STILL_PENDING still pending${NC}"
        break
    fi
done

# Wait for k6 to fully finish (in case it's still running)
wait "$K6_PID" 2>/dev/null

# ═══════════════════════════════════════════════════════
#  PHASE 3: PIPELINE REPORT
# ═══════════════════════════════════════════════════════
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  Pipeline Report${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"

# Total counts
TOTAL=$(db_query "SELECT COUNT(*) FROM notifications WHERE created_at >= '$TEST_START';")
DELIVERED=$(db_query "SELECT COUNT(*) FROM notifications WHERE status = 'DELIVERED' AND created_at >= '$TEST_START';")
FAILED=$(db_query "SELECT COUNT(*) FROM notifications WHERE status = 'FAILED' AND created_at >= '$TEST_START';")

echo ""
echo -e "  ${YELLOW}── Delivery Results ──${NC}"
echo -e "  Total created:   $TOTAL"
echo -e "  Delivered:       ${GREEN}$DELIVERED${NC}"
echo -e "  Failed:          ${RED}$FAILED${NC}"
if [ "$TOTAL" -gt 0 ]; then
    RATE=$(python3 -c "print(f'{($DELIVERED / $TOTAL) * 100:.1f}%')")
    echo -e "  Delivery rate:   ${GREEN}$RATE${NC}"
fi

# End-to-end latency
echo ""
echo -e "  ${YELLOW}── End-to-End Latency (created → delivered) ──${NC}"
db_query "
    SELECT
        'Avg: ' || ROUND(EXTRACT(EPOCH FROM AVG(updated_at - created_at))::numeric, 1) || 's' AS stat
    FROM notifications
    WHERE status = 'DELIVERED' AND created_at >= '$TEST_START'
    UNION ALL
    SELECT
        'P95: ' || ROUND(EXTRACT(EPOCH FROM percentile_cont(0.95) WITHIN GROUP (ORDER BY updated_at - created_at))::numeric, 1) || 's'
    FROM notifications
    WHERE status = 'DELIVERED' AND created_at >= '$TEST_START'
    UNION ALL
    SELECT
        'Max: ' || ROUND(EXTRACT(EPOCH FROM MAX(updated_at - created_at))::numeric, 1) || 's'
    FROM notifications
    WHERE status = 'DELIVERED' AND created_at >= '$TEST_START';
" | while read -r line; do
    [ -n "$line" ] && echo -e "  $line"
done

# Worker delivery speed (from delivery_attempts table)
echo ""
echo -e "  ${YELLOW}── Worker Delivery Speed ──${NC}"
db_query "
    SELECT
        'Avg: ' || ROUND(AVG(duration_ms)::numeric, 1) || 'ms' AS stat
    FROM delivery_attempts
    WHERE created_at >= '$TEST_START'
    UNION ALL
    SELECT
        'P95: ' || ROUND(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)::numeric, 1) || 'ms'
    FROM delivery_attempts
    WHERE created_at >= '$TEST_START'
    UNION ALL
    SELECT
        'Max: ' || ROUND(MAX(duration_ms)::numeric, 1) || 'ms'
    FROM delivery_attempts
    WHERE created_at >= '$TEST_START';
" | while read -r line; do
    [ -n "$line" ] && echo -e "  $line"
done

# Failure details (if any)
FAIL_COUNT=$(db_query "SELECT COUNT(*) FROM delivery_attempts WHERE status = 'FAILED' AND created_at >= '$TEST_START';")
if [ "$FAIL_COUNT" -gt 0 ] 2>/dev/null; then
    echo ""
    echo -e "  ${RED}── Failures ──${NC}"
    db_query "
        SELECT error_message || ' (' || COUNT(*) || 'x)'
        FROM delivery_attempts
        WHERE status = 'FAILED' AND created_at >= '$TEST_START'
        GROUP BY error_message
        ORDER BY COUNT(*) DESC
        LIMIT 5;
    " | while read -r line; do
        [ -n "$line" ] && echo -e "  ${RED}• $line${NC}"
    done
else
    echo ""
    echo -e "  ${GREEN}── No failures ✅ ──${NC}"
fi

# Outbox status
UNPUBLISHED=$(db_query "SELECT COUNT(*) FROM outbox_events WHERE published = false AND created_at >= '$TEST_START';")
if [ "$UNPUBLISHED" -gt 0 ] 2>/dev/null; then
    echo ""
    echo -e "  ${RED}── Outbox: $UNPUBLISHED events still unpublished ──${NC}"
fi

# Show k6 output at the end
echo ""
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}  k6 Summary${NC}"
echo -e "${CYAN}═══════════════════════════════════════════════════════════════════${NC}"
echo ""
cat "$K6_OUTPUT"
rm -f "$K6_OUTPUT"

echo ""
