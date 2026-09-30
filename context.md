# Project Context: Tetherless E2EE Chat

## What is this?

An end-to-end encrypted chat application. Messages are encrypted on the sending device and
decrypted on the receiving one; the relay in the middle routes ciphertext and is assumed hostile.

- `core-shared`: protocol models, wire codec, crypto, and the session layer. Platform-neutral —
  it runs unchanged on the JVM and on Android, which is what stops the two clients drifting apart.
- `chat-server`: the relay. Routes by receiver id, stores nothing, holds no key material.
- `chat-desktop`: Java Swing + SQLite client. Working, and being replaced — see below.
- `desktop-engine`: the desktop client with the window taken off. Speaks newline-delimited JSON
  over stdin/stdout and wraps the existing `ChatClient`.
- `chat-desktop-electron`: Electron + React front end over that engine. npm only; Gradle never
  builds it.
- `chat-mobile`: Android + Room client.
- `chat-desktop-compose`: a Compose Multiplatform scaffold. Abandoned in favour of Electron.

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
| [docs/brd.md](docs/brd.md) | Why the business is building this, and what counts as success. |
| [docs/mrd.md](docs/mrd.md) | The market, the alternatives, and where this sits among them. |
| [docs/prd.md](docs/prd.md) | What the product does, for whom, in what order. |
| [docs/srd.md](docs/srd.md) | Numbered, testable functional and non-functional requirements. |
| [docs/academic_improvement_roadmap.md](docs/academic_improvement_roadmap.md) | Where the cryptography should go next, ranked by impact. |

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

## The desktop client is being rebuilt on Electron

The Swing UI is being replaced by an Electron front end over a headless Java engine. The crypto and
the protocol are **not** rewritten: `desktop-engine` runs the existing `ChatClient` and
`core-shared` as a child process, and Electron talks to it over a pipe.

```
Electron renderer (React) ── preload bridge ── Electron main
                                                    │ newline-delimited JSON over a pipe
                                          desktop-engine (Java)
                                                    │ core-shared, TLS 1.3 pinned
                                              chat-server (relay)
```

**Why a pipe and not a localhost port.** The passphrase, every plaintext message and the identity
key cross that channel. A loopback socket is reachable by every other process running as the same
user; a pipe to a child is private to its parent by construction.

**Why the renderer is locked down.** Message text is attacker-controlled input being rendered by a
browser engine. In Electron an XSS in a transcript is remote code execution, so the renderer runs
sandboxed with no Node, under a `default-src 'none'` CSP, and never renders message text as HTML.
Keys and decryption stay in the Java process.

The plan is `docs/superpowers/plans/2026-09-29-electron-desktop-migration.md`; the palette it uses
is §11 of that document. Work happens on the `electron-desktop` branch.

### What is built (phases 0–2 of 6)

**`desktop-engine` — done.** A Gradle module wrapping the existing `ChatClient`. `EngineMain` owns
stdin and stdout; `Engine` owns the client and is testable without a pipe. The command surface
covers status, unlock, connect/disconnect, conversation list, history, send, session start and
renegotiate, fingerprints, verification, read receipts, typing, search and shutdown. Events cover
incoming messages, delivery status, typing, read receipts, connection state and errors.

Unlock mirrors `Main`'s bootstrap step for step — the PKCS#12 keystore, PBKDF2 derivation and the
migration off the old HKDF key — with failures returned as error codes instead of dialogs.
`:desktop-engine:check` is green: seven tests, Checkstyle, SpotBugs.

**`chat-desktop-electron` — scaffolded.** Main process, preload bridge, React renderer and the
shared protocol types, with the IPC contract typed in one file all three import. The security
baseline went in with the first commit rather than being retrofitted: sandbox and context isolation
on, Node off, `default-src 'none'` CSP with `connect-src 'none'`, navigation refused, and a preload
surface two functions wide. Both TypeScript projects type-check under `strict`.

**Verified working:** the engine drives a full command round trip as a real process over a real
pipe — identity created, fingerprint returned, unknown command refused, clean shutdown. Electron
launches it, the engine initialises its database, and every line of its logging lands on stderr,
leaving the frame stream on stdout clean. That last property is what the whole channel design rests
on.

**Since then (phase 3 and part of 4):** the chat UI, search and a settings panel are built. Two
engines against a live relay complete the signed key exchange and exchange messages intact —
quotes, newlines and emoji included — with delivery acks, persisted history, encrypted-at-rest
search and matching safety numbers on both sides. That is `npm run e2e` in `chat-desktop-electron`,
and it needs a relay on `localhost:8080`. The real window has also been driven over its debugging
port and screenshotted in both themes.

Search covers every conversation, with the match highlighted; selecting a hit opens that
conversation and scrolls to and flashes the message, loading deeper history if it is older than the
usual 200. Settings has theme, reduce-motion, identity (with the peer id and safety number), the
relay address and version. It deliberately has no update switch, because this client has no update
check to switch.

**Still missing compared with the Swing client:** reply quoting, pin/mute/archive, notifications,
and a packaged installer.

**Known gaps worth fixing:**

- The relay's `RECIPIENT_OFFLINE` error carries no message id, so it cannot be tied to one bubble.
  The UI shows a banner and the message keeps its clock or tick; whether it is later delivered
  depends on a session forming. A protocol change would let the client mark the right message.
- The engine handles one command at a time, so a search over a very large history can delay a send.
  Fine at demo scale; the fix is to run searches off the command loop.
- Nothing automated checks the renderer's sandbox or CSP settings.

**Verification in this stack:** `npm test` runs 14 unit tests on the pure helpers, and mutating the
code confirmed they fail when the logic is broken; `npm run typecheck` runs strict TypeScript over
both projects; `npm run e2e` is the two-party round trip.

**Design decisions worth knowing, each forced by something that actually broke:**

- The engine is spawned as a JVM with a wildcard classpath, not through Gradle's `.bat`. Node
  refuses to spawn batch files without a shell since the CVE-2024-27980 hardening, and enabling one
  would add quoting rules and a command-injection surface for nothing.
- Command names are validated before the unlock gate, because a misspelled command otherwise
  reported `locked` and sent the caller debugging the wrong thing.
- A failed spawn surfaces as an `engineDown` event rather than dying as an unhandled rejection
  behind a blank window.

### What comes next (phase 3 is done and phase 4 is partly done; see above for what remains)

| Phase | Work | Done when |
|---|---|---|
| **3 — Core UI** | Sign-in in all three modes, conversation list, transcript with grouping and date separators, composer, delivery ticks, typing indicator, connection banner | Two Electron clients hold a verified conversation, history surviving restart |
| **4 — The rest of the surface** | Chat info with the safety number, verification, renegotiate, search, settings, drawer, notifications, update banner | The parity checklist in the plan passes |
| **5 — Packaging** | `jlink` a minimal runtime, stage it beside the engine, electron-builder Windows installer | A clean Windows machine with no JDK installs and runs it |
| **6 — Cutover** | Work the QA script against the Electron build, then retire the Swing entry point | `docs/qa_script.md` passes end to end |

The immediate next step is the two-client round trip through a relay, because it is the one claim
the current work has not earned.

Two decisions remain open: whether `mono-theme` is abandoned (recommended — it repaints a client
phase 6 deletes, in a palette no longer in use; cherry-pick only its `:core-shared` fix), and
whether the Swing client stays buildable as a fallback until phase 6 passes (recommended).

## Working on it

- **Do not run a bare `./gradlew build`.** `chat-mobile` fails on two pre-existing `android:tint`
  lint errors that are nobody's current business, and it takes the whole build down with it. Use
  module-scoped tasks: `:core-shared:check`, `:chat-server:check`, `:chat-desktop:check`,
  `:desktop-engine:check`.
- `./gradlew :chat-desktop:integTest` — the end-to-end harness: two clients, a live relay, a
  hundred messages each way, asserting the relay learned nothing.
- Running the Electron client: `./gradlew :desktop-engine:installDist`, then
  `npm install && npm run build && npx electron .` in `chat-desktop-electron`.

### Three things that will cost you an hour each

- **`dev-keystore.p12` is gitignored.** A fresh clone or worktree has no development certificate,
  and every `ConnectionManagerTest` then fails at setup with an error that names none of this. Copy
  it from an existing checkout or regenerate it.
- **npm may block postinstall scripts.** Electron's binary never downloads and `electron .` then
  claims the install is broken. Run `node node_modules/electron/install.js` directly.
- **The engine is spawned as a JVM, not through Gradle's `.bat`.** Node refuses to spawn batch files
  without a shell since the CVE-2024-27980 hardening, and enabling a shell there would buy a
  command-injection surface for nothing.
- Read [CONTRIBUTING.md](CONTRIBUTING.md) first. The rules there are short and each one exists
  because something went wrong without it.

One habit worth keeping, learned from the audit: **when a test is written for a fix, check that it
fails without the fix.** Three tests written during that work asserted things that could not fail —
they passed against the unfixed code — and each was caught only by reverting the change and
watching the test stay green.
