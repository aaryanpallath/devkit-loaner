#!/usr/bin/env bash
# End-to-end smoke test against a running API: create a device, book it, prove a conflicting booking is rejected,
# check it out and return it. Usage: scripts/smoke.sh [base-url]   (default http://localhost:8080)
set -euo pipefail

BASE="${1:-http://localhost:8080}"
TAG="SMOKE-$(date +%s)-$RANDOM"
pass=0

step() { printf '%-55s' "$1"; }
ok() { echo "ok"; pass=$((pass + 1)); }
fail() { echo "FAIL: $*"; exit 1; }

# request METHOD PATH [JSON] -> sets $code and $body
request() {
  local out
  out=$(curl -sS -w '\n%{http_code}' -X "$1" "$BASE$2" -H 'Content-Type: application/json' ${3:+-d "$3"})
  code=$(tail -n1 <<<"$out")
  body=$(sed '$d' <<<"$out")
}

json_field() { sed -E "s/.*\"$1\":\"([^\"]*)\".*/\1/" <<<"$2"; }

# booking window: tomorrow 09:00 to the day after, UTC (GNU date or BSD date)
if date -u -d tomorrow +%F >/dev/null 2>&1; then d1=$(date -u -d tomorrow +%F); d2=$(date -u -d '2 days' +%F)
else d1=$(date -u -v+1d +%F); d2=$(date -u -v+2d +%F); fi
START="${d1}T09:00:00Z"; END="${d2}T09:00:00Z"; MID="${d1}T18:00:00Z"

step "health endpoint is up"
request GET /actuator/health; [[ $code == 200 && $body == *UP* ]] && ok || fail "$code $body"

step "create device $TAG"
request POST /api/devices "{\"assetTag\":\"$TAG\",\"name\":\"Smoke dev kit\",\"kind\":\"DEV_KIT\",\"location\":\"CI\"}"
[[ $code == 201 ]] && ok || fail "$code $body"
DEVICE=$(json_field id "$body")

step "book $START .. $END"
request POST "/api/devices/$DEVICE/reservations" "{\"borrower\":\"ci@example.com\",\"start\":\"$START\",\"end\":\"$END\"}"
[[ $code == 201 ]] && ok || fail "$code $body"
RES=$(json_field id "$body")

step "overlapping booking is rejected (409)"
request POST "/api/devices/$DEVICE/reservations" "{\"borrower\":\"other@example.com\",\"start\":\"$MID\",\"end\":\"$END\"}"
[[ $code == 409 ]] && ok || fail "expected 409, got $code $body"

step "check out"
request POST "/api/devices/$DEVICE/reservations/$RES/checkout"
[[ $code == 200 && $body == *CHECKED_OUT* ]] && ok || fail "$code $body"

step "return"
request POST "/api/devices/$DEVICE/reservations/$RES/return"
[[ $code == 200 && $body == *RETURNED* ]] && ok || fail "$code $body"

echo "smoke test passed: $pass checks against $BASE"
