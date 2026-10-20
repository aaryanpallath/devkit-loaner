#!/usr/bin/env bash
# Local development: API on :8080 with an embedded MongoDB (no Docker needed) and the React app on :5173.
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/dev-keys.sh >/dev/null
(cd api && JWT_PUBLIC_KEY_FILE="$PWD/../.dev-keys/public.pem" ./mvnw -q spring-boot:test-run \
  -Dspring-boot.run.profiles=local) &
API_PID=$!
trap 'kill $API_PID 2>/dev/null' EXIT
until curl -sf http://localhost:8080/actuator/health >/dev/null; do sleep 1; done
echo "API ready on http://localhost:8080"
echo "Paste one of these into the app's sign-in box:"
echo "  borrower: $(scripts/dev-token.sh you@example.com)"
echo "  IT desk:  $(scripts/dev-token.sh desk@example.com ADMIN)"
cd web && npm run dev
