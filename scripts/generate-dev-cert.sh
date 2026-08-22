#!/usr/bin/env bash
set -e

# Generates the self-signed localhost certificate the relay uses for TLS in local development.
# Output: chat-server/src/main/resources/dev-keystore.p12
#
# The keystore is deliberately not committed (.gitignore excludes *.p12), so every clone generates
# its own. Run this from the repository root before starting the server or running the TLS tests.

OUT_FILE="chat-server/src/main/resources/dev-keystore.p12"
PASSWORD="${TETHERLESS_TRUSTSTORE_PASSWORD:-changeit}"

mkdir -p "$(dirname "$OUT_FILE")"

echo "Generating self-signed certificate for localhost..."

keytool -genkeypair \
    -alias e2ee-relay \
    -keyalg EC \
    -groupname secp256r1 \
    -sigalg SHA256withECDSA \
    -validity 3650 \
    -keystore "$OUT_FILE" \
    -storetype PKCS12 \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=localhost, OU=Dev, O=E2EE Chat, L=City, ST=State, C=US" \
    -ext "SAN=dns:localhost,ip:127.0.0.1"

echo "Successfully generated $OUT_FILE"
