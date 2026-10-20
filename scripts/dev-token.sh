#!/usr/bin/env bash
# Mint a signed RS256 JWT for local development: scripts/dev-token.sh <email> [ROLE ...]
# Example: scripts/dev-token.sh desk@example.com ADMIN
set -euo pipefail
email="${1:?usage: dev-token.sh <email> [ROLE ...]}"; shift
key="$(cd "$(dirname "$0")/.." && pwd)/.dev-keys/private.pem"
[[ -f "$key" ]] || { echo "no dev key: run scripts/dev-keys.sh" >&2; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
roles=""
for r in "$@"; do roles+="${roles:+,}\"$r\""; done
now=$(date +%s)
header=$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)
payload=$(printf '{"sub":"%s","email":"%s","roles":[%s],"iat":%d,"exp":%d}' \
  "$email" "$email" "$roles" "$now" "$((now + 3600))" | b64url)
sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key" | b64url)
printf '%s.%s.%s\n' "$header" "$payload" "$sig"
