#!/usr/bin/env bash
# Never enable shell tracing here. Passwords enter only through Actions Secrets.
set -euo pipefail
trap 'echo "::error::Signing bootstrap failed at line $LINENO. No release was published."' ERR
umask 077
: "${RUNNER_TEMP:?Run inside GitHub Actions}"
if [ -z "${CALLSHIFT_STORE_PASSWORD:-}" ]; then
  echo '::error::CALLSHIFT_STORE_PASSWORD is unavailable. Add it as a Repository secret, not a Variable or Environment secret.'
  exit 1
fi
if [ "${#CALLSHIFT_STORE_PASSWORD}" -lt 32 ]; then
  echo '::error::CALLSHIFT_STORE_PASSWORD is too short. Use a randomly generated password of at least 32 characters.' >&2
  exit 1
fi
key="$RUNNER_TEMP/callshift-signing.p12"
test ! -e "$key"
mkdir -p signing-recovery
keytool -genkeypair -keystore "$key" -storetype PKCS12 \
  -storepass:env CALLSHIFT_STORE_PASSWORD -keypass:env CALLSHIFT_STORE_PASSWORD \
  -alias callshift -keyalg RSA -keysize 3072 -validity 10000 \
  -dname 'CN=CallShift, O=CallShift, C=RU' -noprompt
keytool -exportcert -rfc -keystore "$key" -storepass:env CALLSHIFT_STORE_PASSWORD \
  -alias callshift -file signing-recovery/certificate.pem
# Encrypt PKCS12 a second time with a high-cost password KDF. The recovery file
# is base64 ciphertext, suitable for pasting into a Repository Secret on a phone.
openssl enc -aes-256-cbc -salt -pbkdf2 -iter 600000 -md sha256 \
  -in "$key" -pass env:CALLSHIFT_STORE_PASSWORD | base64 -w0 > signing-recovery/CALLSHIFT_SIGNING_KEY.txt
printf '\n' >> signing-recovery/CALLSHIFT_SIGNING_KEY.txt
cat > signing-recovery/READ-ME.txt <<'TEXT'
This archive contains an ENCRYPTED private signing-key backup, not a plaintext key.
The password is CALLSHIFT_STORE_PASSWORD, which you saved separately.
Actions artifacts are not private vaults: confidentiality relies on encryption
and a strong random password. Download and retain this backup independently.
Do not send its contents or password in chat or put them in a public release.

On your phone, open CALLSHIFT_SIGNING_KEY.txt and copy its entire contents into
GitHub Settings > Secrets and variables > Actions > New repository secret:
Name: CALLSHIFT_SIGNING_KEY
Secret: the complete file contents.
Keep CALLSHIFT_STORE_PASSWORD unchanged. Losing either prevents restoration.
certificate.pem is PUBLIC; retain it to verify the signature of future APKs.

Recovery on a trusted machine (password in environment, never command arguments):
base64 -d CALLSHIFT_SIGNING_KEY.txt | openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -md sha256 -pass env:CALLSHIFT_STORE_PASSWORD -out callshift-signing.p12
TEXT
