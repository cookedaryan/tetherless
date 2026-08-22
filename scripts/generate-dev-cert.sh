#!/usr/bin/env bash
set -e

# Generates a self-signed localhost certificate for local development
# Output: chat-server/src/main/resources/dev-keystore.p12

OUT_FILE="chat-server/src/main/resources/dev-keystore.p12"
PASSWORD="changeit"

echo "Generating self-signed certificate for localhost..."

keytool -genkeypair \
    -alias e2ee-relay \
    -keyalg EC \
    -keysize 256 \
    -sigalg SHA256withECDSA \
    -validity 3650 \
    -keystore "\" \
    -storetype PKCS12 \
    -storepass "\" \
    -keypass "\" \
    -dname "CN=localhost, OU=Dev, O=E2EE Chat, L=City, ST=State, C=US" \
    -ext "SAN=dns:localhost,ip:127.0.0.1"

echo "Successfully generated \"
