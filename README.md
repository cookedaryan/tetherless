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

See [docs/development_plan.md](docs/development_plan.md) for the ticket-wise plan, threat model,
and definition of done.

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
and stores it under `~/.tetherless/`. Your peer id is shown under **Menu → My identity**; share it
so others can start a secure chat with you.

## Verifying a contact

Signatures only prove a key is consistent, not that it belongs to the right person. Open the shield
icon in the chat header to see the safety number for a conversation and compare it with your contact
over a channel you already trust. If a peer's identity key ever changes, the client blocks sending
and says so rather than silently accepting the new key.
