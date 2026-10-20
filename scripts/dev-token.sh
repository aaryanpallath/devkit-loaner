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
# issuer and audience must match application-local.properties; sub is a stable id derived from the email for dev
sub="dev-$(printf '%s' "$email" | openssl dgst -sha256 | awk '{print substr($NF,1,16)}')"
payload=$(printf '{"iss":"https://dev-idp.local","aud":"loaner-api","sub":"%s","email":"%s","email_verified":true,"roles":[%s],"iat":%d,"exp":%d}' \
  "$sub" "$email" "$roles" "$now" "$((now + 3600))" | b64url)
sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$key" | b64url)
printf '%s.%s.%s\n' "$header" "$payload" "$sig"
