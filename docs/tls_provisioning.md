# TLS Provisioning

The Tetherless E2EE Chat server uses strict TLS 1.3 to ensure transport-layer metadata privacy and connection integrity between the client and the relay server.

## Local Development

For local development and testing, a script is provided to generate a self-signed ECDSA certificate for `localhost` and `127.0.0.1`.

1. Run the script:
   ```bash
   ./scripts/generate-dev-cert.sh
   ```
2. This generates a PKCS12 keystore at `chat-server/src/main/resources/dev-keystore.p12` with the password `changeit`.
3. The `ServerConfig` is configured by default to load this keystore.

Clients (like `TestClient`) MUST explicitly load this keystore as their TrustStore. A "trust-all" `TrustManager` must NEVER be used, even in development, as they invariably leak into production.

## Production (Let's Encrypt)

In a production environment, you should use a valid CA-signed certificate (e.g., Let's Encrypt).

1. Obtain a certificate using Certbot:
   ```bash
   certbot certonly --standalone -d relay.yourdomain.com
   ```
2. Convert the PEM files to a PKCS12 keystore:
   ```bash
   openssl pkcs12 -export -in /etc/letsencrypt/live/relay.yourdomain.com/fullchain.pem \
       -inkey /etc/letsencrypt/live/relay.yourdomain.com/privkey.pem \
       -out prod-keystore.p12 \
       -name e2ee-relay \
       -passout pass:your_secure_password
   ```
3. Update `server.properties` or environment variables to point to the production keystore:
   ```properties
   server.keystore_path=/path/to/prod-keystore.p12
   server.keystore_password=your_secure_password
   ```

Clients in production should pin the Let's Encrypt Root CA or the intermediate CA that signed your certificate, rather than relying on the system default trust store, to mitigate DNS spoofing or rogue CAs.
