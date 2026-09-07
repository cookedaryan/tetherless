# Deploying the desktop client

This describes what changes between running from a Gradle checkout and shipping something to
people. The short version: **a packaged client will not connect until you give it a certificate to
pin**, and that is deliberate.

Read [security.md](security.md) alongside this. It lists what the system does not protect against,
which is the part that matters when deciding whether to deploy it at all.

---

## 1. Why a packaged build refuses to start talking

In development, the relay and the clients share a self-signed certificate produced by
`scripts/generate-dev-cert.sh`. That is fine on a laptop and useless in the world: the script is in
the repository, so anybody can regenerate the same keypair and impersonate your relay. A client that
trusted it would have TLS in name only.

So the build distinguishes the two cases and refuses to blur them:

| Build | `channel` stamp | With no truststore configured |
|---|---|---|
| `./gradlew run` | `dev` | Falls back to the development certificate, logging a warning |
| `./gradlew packageAppImage -PreleaseBuild` | `release` | **Refuses to connect**, naming the setting to fix it |

The stamp lives in `build-info.properties`, generated at build time and read by
`core.build.BuildInfo`. Anything unrecognised or missing reads as `dev`, so a corrupted stamp can
never promote an artifact into being trusted as a packaged one. The packaging tasks refuse to run
without `-PreleaseBuild` for the same reason — it would produce an installer whose contents trust a
public private key.

---

## 2. Give the relay a real certificate

Two options, depending on whether the relay has a public hostname.

### A public relay with a DNS name

Get a certificate the normal way (Let's Encrypt or any CA), then convert it to the PKCS#12 the
server reads:

```bash
openssl pkcs12 -export \
  -in fullchain.pem -inkey privkey.pem \
  -out relay.p12 -name relay \
  -passout pass:CHOOSE_A_PASSWORD
```

### A private relay with no public name

A self-signed certificate is fine here — pinning is what provides the security, not a CA signature.
Generate one **with its own key**, not the development one:

```bash
keytool -genkeypair -alias relay \
  -keyalg RSA -keysize 2048 -validity 825 \
  -dname "CN=relay.internal" \
  -ext "SAN=dns:relay.internal,ip:10.0.0.5" \
  -keystore relay.p12 -storetype PKCS12 \
  -storepass CHOOSE_A_PASSWORD
```

The `SAN` must list every name or address clients will use. A certificate without a matching SAN
fails the handshake regardless of pinning.

Keep `relay.p12` off the internet and out of the repository. It holds the relay's private key.

---

## 3. Configure the relay

`ServerConfig` reads, in order: environment variable, system property, then `server.properties`.

| Setting | Env | Property |
|---|---|---|
| Listen port | `PORT` | `server.port` |
| Keystore path | `KEYSTORE_PATH` | `server.keystore_path` |
| Keystore password | `KEYSTORE_PASSWORD` | `server.keystore_password` |

```bash
export KEYSTORE_PATH=/etc/tetherless/relay.p12
export KEYSTORE_PASSWORD='...'
export PORT=8080
./gradlew :chat-server:run
```

The keystore password is a real secret here — unlike the client side, this file contains the private
key. Put it in the environment or a secrets manager, not in `server.properties` next to the file it
protects.

---

## 4. Give clients the certificate to pin

Clients need the **public** certificate only, never the relay's private key. Export it:

```bash
keytool -exportcert -alias relay -keystore relay.p12 \
  -storepass CHOOSE_A_PASSWORD -rfc -file relay.crt

keytool -importcert -noprompt -alias relay -file relay.crt \
  -keystore relay-truststore.p12 -storetype PKCS12 \
  -storepass changeit
```

Distribute `relay-truststore.p12` with the client. It contains no secret, so it can ship inside the
installer or sit beside the config file.

Then point the client at it, in `~/.tetherless/config.properties`:

```properties
host = relay.example.org
port = 8080

# Relative paths resolve against this directory, so this finds
# ~/.tetherless/relay-truststore.p12
truststore = relay-truststore.p12
truststore.password = changeit
```

A launcher can override any of it with `-Dtetherless.truststore=...`, `-Dtetherless.host=...`,
`-Dtetherless.port=...`, or the `TETHERLESS_TRUSTSTORE` environment variable. System properties beat
the file; command-line arguments (`Tetherless <host> <port>`) beat everything.

Users can confirm what took effect under **Menu → About Tetherless**, which names the pinned
certificate — or says in plain words that the connection is not secure if none is configured.

### On the truststore password

It protects a file of public certificates. It guards integrity — stopping someone appending a rogue
certificate to the file — not confidentiality, and leaks nothing if disclosed. `changeit` is an
acceptable value. The relay's *keystore* password is a different matter entirely.

---

## 5. Build the client

```bash
# A runnable directory, no installer tooling required
./gradlew :chat-desktop:packageAppImage -PreleaseBuild

# A native installer
./gradlew :chat-desktop:packageInstaller -PreleaseBuild
```

Output lands in `chat-desktop/build/jpackage/`. The application bundles its own Java runtime, so
users need no JDK.

`packageInstaller` produces `.msi` on Windows, `.dmg` on macOS and `.deb` on Linux. Each needs its
platform's tooling — **the Windows target requires the [WiX Toolset](https://wixtoolset.org/)**, and
jpackage cannot cross-compile, so each installer must be built on its own platform.

---

## 6. Before you hand it to anyone

- [ ] `./gradlew clean build` passes — Checkstyle, SpotBugs with find-sec-bugs, and all tests.
- [ ] The relay is running with your own keystore, not `dev-keystore.p12`.
- [ ] The packaged client connects with the truststore configured, and **fails** without it. If it
      connects with nothing configured, the artifact was not built with `-PreleaseBuild`.
- [ ] **Menu → About Tetherless** reports the version, the commit, `release`, and names your
      certificate.
- [ ] Two clients on different machines complete a handshake and exchange messages.
- [ ] Safety numbers match on both ends of a conversation.

---

## 7. What is still missing

Honesty about the edges, since this document is otherwise a set of instructions that imply
readiness:

- **The relay has no packaging story yet.** It runs from Gradle. REL-02 calls for a fat JAR, a
  Dockerfile and a systemd unit; none exist.
- **No auto-update.** Shipping a fix means shipping a new installer and telling people.
- **No code signing.** Windows will show an unknown-publisher warning, and macOS Gatekeeper will
  refuse the app outright without notarisation.
- **Only the Windows package is verified.** The macOS and Linux jpackage configuration is written
  but has never been executed.
- **One relay.** It cannot read messages, but it can deny service to everybody, and it observes all
  metadata. See the limitations in [security.md](security.md).
