#!/usr/bin/env bash
# End-to-end smoke test against a running API: create a device, book it, prove a conflicting booking is rejected,
# check it out and return it. Usage: scripts/smoke.sh [base-url]   (default http://localhost:8080)
set -euo pipefail

BASE="${1:-http://localhost:8080}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DESK=$("$HERE/dev-token.sh" desk@example.com ADMIN)      # IT desk: creates devices, hands them out
ANA=$("$HERE/dev-token.sh" ana@example.com)              # a borrower
BEN=$("$HERE/dev-token.sh" ben@example.com)              # another borrower
TAG="SMOKE-$(date +%s)-$RANDOM"
pass=0

step() { printf '%-55s' "$1"; }
ok() { echo "ok"; pass=$((pass + 1)); }
fail() { echo "FAIL: $*"; exit 1; }

# request TOKEN METHOD PATH [JSON] -> sets $code and $body
request() {
  local out auth=()
  [[ -n "$1" ]] && auth=(-H "Authorization: Bearer $1")
  out=$(curl -sS -w '\n%{http_code}' -X "$2" "$BASE$3" -H 'Content-Type: application/json' ${auth[@]+"${auth[@]}"} ${4:+-d "$4"})
  code=$(tail -n1 <<<"$out")
  body=$(sed '$d' <<<"$out")
}

json_field() { sed -E "s/.*\"$1\":\"([^\"]*)\".*/\1/" <<<"$2"; }

# booking window: tomorrow 09:00 to the day after, UTC (GNU date or BSD date)
if date -u -d tomorrow +%F >/dev/null 2>&1; then d1=$(date -u -d tomorrow +%F); d2=$(date -u -d '2 days' +%F)
else d1=$(date -u -v+1d +%F); d2=$(date -u -v+2d +%F); fi
START="${d1}T09:00:00Z"; END="${d2}T09:00:00Z"; MID="${d1}T18:00:00Z"

step "health endpoint is up"
request "" GET /actuator/health; [[ $code == 200 && $body == *UP* ]] && ok || fail "$code $body"

step "API refuses requests without a token (401)"
request "" GET /api/devices; [[ $code == 401 ]] && ok || fail "expected 401, got $code"

step "borrowers cannot create devices (403)"
request "$ANA" POST /api/devices "{\"assetTag\":\"$TAG\",\"name\":\"x\",\"kind\":\"DEV_KIT\",\"location\":\"CI\"}"
[[ $code == 403 ]] && ok || fail "expected 403, got $code"

step "IT desk creates device $TAG"
request "$DESK" POST /api/devices "{\"assetTag\":\"$TAG\",\"name\":\"Smoke dev kit\",\"kind\":\"DEV_KIT\",\"location\":\"CI\"}"
[[ $code == 201 ]] && ok || fail "$code $body"
DEVICE=$(json_field id "$body")

step "ana books $START .. $END"
request "$ANA" POST "/api/devices/$DEVICE/reservations" "{\"start\":\"$START\",\"end\":\"$END\"}"
[[ $code == 201 ]] && ok || fail "$code $body"
RES=$(json_field id "$body")

step "overlapping booking is rejected (409)"
request "$BEN" POST "/api/devices/$DEVICE/reservations" "{\"start\":\"$MID\",\"end\":\"$END\"}"
[[ $code == 409 ]] && ok || fail "expected 409, got $code $body"

step "ben cannot see ana's email in the listing"
request "$BEN" GET "/api/devices/$DEVICE"
[[ $code == 200 && $body != *ana@example.com* ]] && ok || fail "$code $body"

step "IT desk checks the device out"
request "$DESK" POST "/api/devices/$DEVICE/reservations/$RES/checkout"
[[ $code == 200 && $body == *CHECKED_OUT* ]] && ok || fail "$code $body"

step "IT desk checks it back in"
request "$DESK" POST "/api/devices/$DEVICE/reservations/$RES/return"
[[ $code == 200 && $body == *RETURNED* ]] && ok || fail "$code $body"

echo "smoke test passed: $pass checks against $BASE"
