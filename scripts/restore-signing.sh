#!/usr/bin/env bash
# Reuses an owner-backed-up identity. No generation/fallback and no secret output.
set -euo pipefail
umask 077
trap 'echo "::error::Signing-key restoration failed. Check both repository secrets; do not create another key."' ERR
: "${RUNNER_TEMP:?Run inside GitHub Actions}"
if [ -z "${CALLSHIFT_SIGNING_KEY:-}" ] || [ -z "${CALLSHIFT_STORE_PASSWORD:-}" ]; then
  echo '::error::Both CALLSHIFT_SIGNING_KEY and CALLSHIFT_STORE_PASSWORD must be Repository secrets.'
  exit 1
fi
key="$RUNNER_TEMP/callshift-signing.p12"
test ! -e "$key"
printf '%s' "$CALLSHIFT_SIGNING_KEY" | tr -d '\r\n\t ' | base64 -d | \
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 \
    -pass env:CALLSHIFT_STORE_PASSWORD -out "$key"
# Verifies the PKCS12 password/MAC and alias; exports only the public certificate.
keytool -exportcert -rfc -keystore "$key" -storepass:env CALLSHIFT_STORE_PASSWORD \
  -alias callshift -file "$RUNNER_TEMP/callshift-certificate.pem"
