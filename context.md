# Project Context: Tetherless E2EE Chat

## What is this?

An end-to-end encrypted chat application. Messages are encrypted on the sending device and
decrypted on the receiving one; the relay in the middle routes ciphertext and is assumed hostile.

- `core-shared`: protocol models, wire codec, crypto, and the session layer. Platform-neutral —
  it runs unchanged on the JVM and on Android, which is what stops the two clients drifting apart.
- `chat-server`: the relay. Routes by receiver id, stores nothing, holds no key material.
- `chat-desktop`: Java Swing + SQLite client.
- `chat-mobile`: Android + Room client.

## Key documents

| Document | What it covers |
|---|---|
| [README.md](README.md) | Building, running, and packaging. |
| [docs/security.md](docs/security.md) | Threat model, what is guaranteed, and — the part worth reading — what is not. |
| [docs/protocol.md](docs/protocol.md) | The wire format byte by byte, and the rules a receiver enforces. |
| [docs/adr.md](docs/adr.md) | Why the load-bearing decisions were made, including the ones that look wrong now. |
| [docs/deployment.md](docs/deployment.md) | Running a relay for real. |
| [docs/qa_script.md](docs/qa_script.md) | The manual pass before tagging. |
| [docs/development_plan.md](docs/development_plan.md) | The original ticket-wise plan. A record of what was intended, not a description of what exists. |

## Current state

All four modules are implemented and the protocol is frozen at version 2, pinned by seventeen
committed vectors that both clients run. The desktop client is packaged with `jpackage`; the relay
ships as a fat JAR, a container, and a systemd unit.

A full security audit was carried out against the codebase and its findings worked through.
Nineteen of twenty are closed, including every Critical, High and Medium. What remains open is
recorded in [docs/adr.md](docs/adr.md) under *Decisions still open* — chiefly that message
signatures give non-repudiation rather than deniability, which is a protocol change rather than a
patch.

Four defects the audit did not find were fixed alongside it: an unauthenticated registration path
that survived the first attempt at the fix, a relay queue-overflow that tore down one client's
session on another client's thread, a `.gitignore` rule that excluded a source file and left a
clean clone unable to compile, and a wire payload encoded with the platform default charset that a
blanket static-analysis suppression had been hiding.

## Architectural and security constraints

- **The relay is hostile.** It must never see plaintext or key material, and a client must not
  rely on it for anything it can check itself. Both ends verify signatures, recipient binding, and
  nonce direction on every frame they accept.
- **No Java serialization on the wire.** The protocol is an explicit length-prefixed binary codec;
  no wire type implements `Serializable`.
- **Crypto:** AES-256-GCM for messages, Diffie–Hellman over MODP Group 14 for key agreement,
  HKDF-SHA256 for derivation, SHA256withRSA for signatures.
- **Identity is the key.** A peer id is the first 16 bytes of SHA-256 over the identity public key.
  It cannot be chosen, and a frame claiming an id that is not the hash of the key it carries is
  refused — by the peer and, on registration, by the relay.
- **No payload logging.** Keys, IVs and message bodies are never logged. Peer ids are redacted.
- **Fail closed.** A configured-but-unreadable truststore, a damaged peer key store, a registration
  that cannot prove itself: each is refused rather than worked around. Silently continuing with
  less security than intended is the failure mode this codebase treats as worst.

## Working on it

- `./gradlew build` — compile, Checkstyle, SpotBugs with find-sec-bugs, and the test suites.
- `./gradlew :chat-desktop:integTest` — the end-to-end harness: two clients, a live relay, a
  hundred messages each way, asserting the relay learned nothing.
- Read [CONTRIBUTING.md](CONTRIBUTING.md) first. The rules there are short and each one exists
  because something went wrong without it.

One habit worth keeping, learned from the audit: **when a test is written for a fix, check that it
fails without the fix.** Three tests written during that work asserted things that could not fail —
they passed against the unfixed code — and each was caught only by reverting the change and
watching the test stay green.
