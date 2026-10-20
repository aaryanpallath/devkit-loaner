#!/usr/bin/env bash
# Local development: API on :8080 with an embedded MongoDB (no Docker needed) and the React app on :5173.
set -euo pipefail
cd "$(dirname "$0")/.."
(cd api && ./mvnw -q spring-boot:test-run) &
API_PID=$!
trap 'kill $API_PID 2>/dev/null' EXIT
until curl -sf http://localhost:8080/actuator/health >/dev/null; do sleep 1; done
echo "API ready on http://localhost:8080"
cd web && npm run dev
