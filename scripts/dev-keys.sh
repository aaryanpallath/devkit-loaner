#!/usr/bin/env bash
# Throwaway RSA key pair for local development and CI. The API verifies tokens with the public key; dev-token.sh
# signs them with the private key. Never used on AWS, where tokens come from the company identity provider.
set -euo pipefail
dir="$(cd "$(dirname "$0")/.." && pwd)/.dev-keys"
mkdir -p "$dir"
if [[ ! -f "$dir/private.pem" ]]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/private.pem" 2>/dev/null
  openssl pkey -in "$dir/private.pem" -pubout -out "$dir/public.pem"
  chmod 600 "$dir/private.pem"
fi
echo "$dir"
