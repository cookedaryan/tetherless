# tetherless

A decentralized end to end chat application.

Messages are encrypted on the sending device and decrypted on the receiving one. The relay in the
middle routes ciphertext and is assumed hostile: it never holds key material and never sees
plaintext.

| Module | What it is |
|---|---|
| `core-shared` | Protocol models, wire codec, and the AES / DH / RSA primitives. Platform-neutral. |
| `chat-server` | The relay. Routes ciphertext between clients; deliberately dumb. |
| `chat-desktop` | Java Swing + SQLite desktop client. |
| `chat-mobile` | Android + Room mobile client. |

```
        ┌────────────────────┐                      ┌────────────────────┐
        │   chat-desktop     │                      │    chat-mobile     │
        │  Swing + SQLite    │                      │  Android + Room    │
        └─────────┬──────────┘                      └─────────┬──────────┘
                  │                                           │
                  └──────────────┐             ┌──────────────┘
                                 ▼             ▼
                         ┌───────────────────────────┐
                         │       core-shared         │
                         │  codec · crypto · session │
                         └─────────────┬─────────────┘
                                       │  encrypt, sign, frame
                                       ▼
                      ═══════════ TLS 1.3, pinned ═══════════
                                       │
                         ┌─────────────▼─────────────┐
                         │        chat-server        │
                         │  routes by receiver id    │
                         │  stores nothing           │
                         │  cannot decrypt anything  │
                         └───────────────────────────┘
```

Both clients share one implementation of the protocol, which is the point of `core-shared`: a
desktop-versus-Android disagreement about the wire format is the defect class this design exists to
make impossible, and the frozen vectors in `ProtocolVectors` are run on both.

**[docs/security.md](docs/security.md)** describes what the system protects, what it does not, and
which tests back each claim. Read the limitations section before trusting it with anything that
matters.

| Document | What it covers |
|---|---|
| [docs/security.md](docs/security.md) | Threat model, guarantees, and — the part worth reading — the non-guarantees. |
| [docs/protocol.md](docs/protocol.md) | The wire format, byte by byte, and the handshake state machine. |
| [docs/adr.md](docs/adr.md) | Why the load-bearing decisions were made, including the ones now regretted. |
| [docs/qa_script.md](docs/qa_script.md) | The manual pass run before tagging a release. |
| [docs/deployment.md](docs/deployment.md) | Running a relay for real. |
| [docs/tls_provisioning.md](docs/tls_provisioning.md) | Issuing and pinning a certificate that is not the development one. |
| [CONTRIBUTING.md](CONTRIBUTING.md) | The rules a change has to follow, and why each exists. |
| [docs/development_plan.md](docs/development_plan.md) | The ticket-wise plan and definition of done. |

## Getting started

### 1. Generate the development TLS certificate

**Do this first.** The relay serves TLS 1.3 and the clients pin its certificate, so nothing can
connect until the certificate exists — and the TLS tests fail on a fresh clone without it:

```bash
./scripts/generate-dev-cert.sh
```

This writes a self-signed `localhost` keystore to
`chat-server/src/main/resources/dev-keystore.p12`. It is **not** committed — `.gitignore` excludes
`*.p12`, so every clone generates its own. You need `keytool`, which ships with the JDK.

The keystore password defaults to `changeit` and is read from
`TETHERLESS_TRUSTSTORE_PASSWORD` (or the `tetherless.truststore.password` system property) if you
set one; see `core-shared/.../network/TlsSupport.java`.

### 2. Enable the commit hooks

```bash
git config core.hooksPath scripts/git-hooks
```

One command, once per clone. The pre-commit hook refuses text files containing NUL bytes or a
UTF-16 byte-order mark.

This is not hypothetical housekeeping: several files in this repository were silently corrupted by
shell redirection writing UTF-16LE instead of UTF-8. On Windows, `cmd > file` and `cmd >> file` in
PowerShell can produce UTF-16, and the result does not look wrong in an editor. Prefer
`cmd | Out-File -Encoding utf8 file`, or redirect from bash.

### 3. Build

```bash
./gradlew build
```

Runs compilation, Checkstyle, SpotBugs (with find-sec-bugs), and the test suites across all four
modules. Building `chat-mobile` additionally needs the Android SDK.

### 4. Run

Start the relay:

```bash
./gradlew :chat-server:run
```

Then start a desktop client in another terminal:

```bash
./gradlew :chat-desktop:run
```

On first launch the client asks for a display name and a passphrase, generates your RSA identity,
and stores it under `~/.tetherless/`. Your peer id is under **Menu → Settings → Copy my id**; share
it so others can start a secure chat with you, and open one to someone else's with **Menu → New
chat…**. Once a conversation exists, its safety number is under the shield button in the chat
header.

To run two identities on one machine, point each at its own profile directory:

```bash
./gradlew :chat-desktop:run -Dtetherless.config.dir=C:/Temp/alice
```

### 5. The end-to-end harness

```bash
./gradlew :chat-desktop:integTest
```

Boots the relay on an ephemeral port, runs two client cores through a full conversation — handshake,
a hundred messages each way, disconnect — and asserts that no plaintext appears in anything the
relay routed. It is kept out of `build` because it opens real sockets; CI runs it as its own job.


## Packaging a release

```bash
./gradlew :chat-desktop:packageAppImage -PreleaseBuild
```

This produces a self-contained application in `chat-desktop/build/jpackage/` that bundles its own
Java runtime — users need no JDK. Use `packageInstaller` instead for a native `.msi`/`.dmg`/`.deb`
(the Windows target needs the [WiX Toolset](https://wixtoolset.org/)).

`-PreleaseBuild` is not optional, and the tasks refuse to run without it. It stamps the build as a
release, which is what makes the client **refuse to fall back to the development certificate** — a
certificate whose private key is reproducible by anyone with this repository. A packaged client
will not connect until you configure a real one.

**[docs/deployment.md](docs/deployment.md)** walks through generating a certificate, configuring the
relay and clients, and what is still missing before this is genuinely shippable.


## Deploying the relay

```bash
# A single runnable JAR with every dependency
./gradlew :chat-server:fatJar -PreleaseBuild

# Or a container
docker build -f chat-server/Dockerfile -t tetherless-relay:1.0.0 \
  --build-arg GIT_COMMIT="$(git rev-parse --short HEAD)" .
```

There is also `chat-server/docker-compose.yml` (unprivileged, read-only root filesystem, keystore
and password mounted as secrets) and a hardened systemd unit in `chat-server/deploy/`.

A packaged relay **refuses to start without a keystore** rather than serving the development one,
which is why `-PreleaseBuild` is required. The development keystore is excluded from both the fat
JAR and the image — it holds a private key that anyone with this repository can regenerate.

## Verifying a contact

Signatures only prove a key is consistent, not that it belongs to the right person. Open the shield
icon in the chat header to see the safety number for a conversation and compare it with your contact
over a channel you already trust. If a peer's identity key ever changes, the client blocks sending
and says so rather than silently accepting the new key.
