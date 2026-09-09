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

## 3. Deploy the relay

The relay reads its configuration from, in order: environment variable, system property, then
`server.properties` in the working directory.

| Setting | Env | Property |
|---|---|---|
| Listen port | `PORT` | `server.port` |
| Keystore path | `KEYSTORE_PATH` | `server.keystore_path` |
| Keystore password | `KEYSTORE_PASSWORD` | `server.keystore_password` |
| Keystore password file | `KEYSTORE_PASSWORD_FILE` | `server.keystore_password_file` |
| Max connections | `MAX_CONNECTIONS` | `server.max_connections` |
| Max connections per IP | `MAX_CONNECTIONS_PER_IP` | `server.max_connections_per_ip` |

**Prefer `KEYSTORE_PASSWORD_FILE`.** This password protects the relay's private key, and anything in
the environment is visible to `docker inspect` and inherited by every child process. The file form
is what Docker and Kubernetes secrets mount, and the value is trimmed, so a trailing newline from
shell redirection does no harm.

Like the client, **a packaged relay refuses to start with no keystore configured** rather than
falling back to the development keypair. The three artifacts below are all built with
`-PreleaseBuild`, which is what stamps `channel=release` and turns that refusal on.

### Option A - a single JAR

```bash
./gradlew :chat-server:fatJar -PreleaseBuild
```

Produces `chat-server/build/libs/tetherless-relay-<version>.jar`, bundling every dependency. The
development keystore is deliberately excluded from it.

```bash
KEYSTORE_PATH=/etc/tetherless/relay.p12 \
KEYSTORE_PASSWORD_FILE=/etc/tetherless/keystore-password \
PORT=8080 \
java -jar tetherless-relay-1.0.0.jar
```

### Option B - Docker

```bash
docker build -f chat-server/Dockerfile -t tetherless-relay:1.0.0 \
  --build-arg GIT_COMMIT="$(git rev-parse --short HEAD)" \
  --build-arg GIT_COMMIT_DATE="$(git log -1 --format=%cd --date=iso-strict)" .
```

**The image contains no certificate.** An image with a private key baked in is a private key
published to everyone who can pull it, so the keystore is mounted at run time. With compose:

```bash
export TETHERLESS_KEYSTORE=/etc/tetherless/relay.p12
export TETHERLESS_KEYSTORE_PASSWORD_FILE=/etc/tetherless/keystore-password
docker compose -f chat-server/docker-compose.yml up -d
```

The compose file runs the relay unprivileged with a read-only root filesystem and every capability
dropped, passes the password as a mounted secret rather than an environment variable, and binds the
metrics port to loopback only. Logs go to stdout, where the runtime collects them.

### Option C - systemd on bare metal

`chat-server/deploy/tetherless-relay.service` carries its own installation steps in a header
comment. It runs as a dedicated unprivileged user under `ProtectSystem=strict` with a syscall
filter and an empty capability bounding set.

If you move the relay to port 443, do **not** grant it `CAP_NET_BIND_SERVICE`. Put a reverse proxy
in front, or use socket activation, so the JVM never runs privileged.

### The metrics endpoint

The relay exposes Prometheus-style counters on **`PORT + 1`** at `/metrics`. It is unauthenticated
plaintext HTTP and must not be reachable from the internet.

**The relay binds it to `127.0.0.1` itself**, so a deployment that forgets a firewall rule is not
publishing it. That used to depend entirely on what was in front of the process: the endpoint was
bound to the wildcard address, so a bare-metal relay with no rule in place served its client count,
routed-message totals and rejection counters to anyone who asked.

To scrape it from another host, set `server.metrics_host` (or `METRICS_HOST`) and put
authentication in front of it — a reverse proxy, or a private network interface. The relay logs a
warning at startup whenever that setting is not loopback, because the endpoint has no
authentication of its own.

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
- [ ] The relay starts with **your** keystore, and **refuses** to start without one. If it starts
      with nothing configured, the artifact was not built with `-PreleaseBuild`.
- [ ] The relay's metrics port (`PORT + 1`) is not reachable from outside the host.
- [ ] The packaged client connects with the truststore configured, and **fails** without it.
- [ ] **Menu → About Tetherless** reports the version, the commit, `release`, and names your
      certificate.
- [ ] Two clients on different machines complete a handshake and exchange messages.
- [ ] Safety numbers match on both ends of a conversation.

---

## 7. What is still missing

Honesty about the edges, since this document is otherwise a set of instructions that imply
readiness:

- **No code signing.** Windows will show an unknown-publisher warning on the client installer, and
  macOS Gatekeeper will refuse the app outright without notarisation.
- **Only the Windows client package is verified.** The macOS and Linux jpackage configuration is
  written but has never been executed; jpackage cannot cross-compile.
- **No auto-update.** Shipping a fix means shipping a new installer and telling people.
- **The mobile release build is untouched.** No R8 keep rules for the reflectively-used Room and
  crypto classes, and no signed AAB. R8-only breakage is invisible until you build release.
- **No CI.** Nothing builds or tests this on a machine other than a developer's own, so
  "works here" is the only evidence any of it works. BUILD-04.
- **The relay is a single point of failure.** It cannot read messages, but it can deny service to
  everybody, and it observes all metadata. See the limitations in [security.md](security.md).
- **No rate limit on connection attempts per identity**, only per IP. See SERVER-03.
