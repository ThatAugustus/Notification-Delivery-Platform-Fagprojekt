set -u

BASE="http://localhost:8080/api/v1"
ADMIN_KEY="dev-admin-key-change-me-in-prod"
pass=0; fail=0

# extract a top-level JSON field from stdin
jget() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))"; }

# return only the HTTP status code for a curl call
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# chk <label> <actual> <expected>
chk() {
  if [ "$2" = "$3" ]; then
    printf "  [OK]   %-32s %s\n" "$1" "$2"; pass=$((pass+1))
  else
    printf "  [FAIL] %-32s got %s, expected %s\n" "$1" "$2" "$3"; fail=$((fail+1))
  fi
}

notif() { # build a notification body with a unique idempotency key
  printf '{"channel":"EMAIL","recipient":"to@example.com","subject":"x","content":"x","idempotencyKey":"smoke-%s"}' "$RANDOM"
}

echo "1. Admin authentication"
chk "no admin key"      "$(code "$BASE/admin/tenants")"                         401
chk "wrong admin key"   "$(code -H 'X-Admin-Key: wrong' "$BASE/admin/tenants")" 401

echo "2. Create tenant"
RESP=$(curl -s -X POST "$BASE/admin/tenants" -H "X-Admin-Key: $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Smoke $RANDOM\",\"defaultFromEmail\":\"smoke@test.com\"}")
TENANT_ID=$(printf '%s' "$RESP" | jget id)
printf "  -> tenant id: %s\n" "$TENANT_ID"
[ -z "$TENANT_ID" ] && { echo "  Could not create tenant. Is the app running on :8080?"; exit 1; }

echo "3. Tenant reads"
chk "get existing"  "$(code -H "X-Admin-Key: $ADMIN_KEY" "$BASE/admin/tenants/$TENANT_ID")" 200
chk "get bogus"     "$(code -H "X-Admin-Key: $ADMIN_KEY" "$BASE/admin/tenants/00000000-0000-0000-0000-000000000000")" 404
chk "patch"         "$(code -X PATCH -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' -d '{"name":"Renamed"}' "$BASE/admin/tenants/$TENANT_ID")" 200

echo "4. Create API key"
RESP=$(curl -s -X POST "$BASE/admin/tenants/$TENANT_ID/api-keys" -H "X-Admin-Key: $ADMIN_KEY" \
  -H "Content-Type: application/json" -d '{"name":"smoke-key"}')
API_KEY=$(printf '%s' "$RESP" | jget rawKey)
printf "  -> raw key: %s\n" "$API_KEY"

echo "5. Send a notification with the key"
chk "valid key -> 202" "$(code -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' -d "$(notif)" "$BASE/notifications")" 202

echo "6. Soft delete + zombie-key rejection"
chk "delete -> 204"        "$(code -X DELETE -H "X-Admin-Key: $ADMIN_KEY" "$BASE/admin/tenants/$TENANT_ID")" 204
chk "get deleted -> 404"   "$(code -H "X-Admin-Key: $ADMIN_KEY" "$BASE/admin/tenants/$TENANT_ID")" 404
chk "zombie key -> 401"    "$(code -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' -d "$(notif)" "$BASE/notifications")" 401

echo "7. Restore brings the key back"
chk "restore -> 200"       "$(code -X POST -H "X-Admin-Key: $ADMIN_KEY" "$BASE/admin/tenants/$TENANT_ID/restore")" 200
chk "key works again -> 202" "$(code -X POST -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' -d "$(notif)" "$BASE/notifications")" 202

echo ""
printf "Result: %d passed, %d failed\n" "$pass" "$fail"
echo "(Check delivered emails at http://localhost:8025)"
